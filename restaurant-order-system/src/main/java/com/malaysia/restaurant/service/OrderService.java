package com.malaysia.restaurant.service;

import com.malaysia.restaurant.common.enums.*;
import com.malaysia.restaurant.entity.Domain;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private final InMemoryStore store;
    private final PrinterService printerService;
    private final StringRedisTemplate redis;
    private final RealtimeEventService realtime;

    public OrderService(InMemoryStore store, PrinterService printerService, StringRedisTemplate redis,
                        RealtimeEventService realtime) {
        this.store = store;
        this.printerService = printerService;
        this.redis = redis;
        this.realtime = realtime;
    }

    public List<Domain.DiningTable> tables(long merchantId, Long storeId) {
        return store.queryTables(merchantId, storeId, null);
    }

    public Domain.DiningTable openTable(long merchantId, long tableId, int people) {
        return withLocks(List.of(tableLock(merchantId, tableId)), () -> {
            return openTableInternal(merchantId, tableId, people);
        });
    }

    public Domain.DiningTable transferTable(long merchantId, long fromTableId, long toTableId, long operatorId) {
        return withLocks(List.of(tableLock(merchantId, fromTableId), tableLock(merchantId, toTableId)), () -> {
            Domain.DiningTable from = requireTable(merchantId, fromTableId);
            Domain.DiningTable to = requireTable(merchantId, toTableId);
            // Transfer keeps the same order but rewrites its table binding; target table must be empty.
            if (from.currentOrderId() == null || from.status() == TableStatus.EMPTY) {
                throw new IllegalArgumentException("原桌台没有可转移订单");
            }
            if (to.status() != TableStatus.EMPTY) {
                throw new IllegalArgumentException("目标桌台必须为空闲");
            }
            Domain.Order order = requireOrder(merchantId, from.currentOrderId());
            Domain.Order moved = new Domain.Order(order.id(), order.merchantId(), to.storeId(), to.id(), to.tableNo(), order.people(), order.waiterId(),
                    order.waiterName(), order.status(), order.totalAmount(), order.remark(), order.cancelReason(),
                    order.createdAt(), LocalDateTime.now(), order.items());
            store.saveOrder(moved);
            store.saveTable(from.withStatus(TableStatus.CLEANING, 0, null, null));
            store.saveTable(to.withStatus(TableStatus.DINING, from.currentPeople(), from.openedAt(), moved.id()));
            store.log(merchantId, to.storeId(), operatorId, "TRANSFER_TABLE", from.tableNo() + " -> " + to.tableNo() + ", order " + order.id());
            realtime.orderChanged(merchantId, to.storeId(), moved.id(), "TABLE_TRANSFERRED");
            realtime.tableChanged(merchantId, from.storeId(), from.id(), "TABLE_TRANSFERRED_FROM");
            realtime.tableChanged(merchantId, to.storeId(), to.id(), "TABLE_TRANSFERRED_TO");
            return requireTable(merchantId, to.id());
        });
    }

    public Domain.DiningTable completeCleaning(long merchantId, long tableId, long operatorId) {
        return withLocks(List.of(tableLock(merchantId, tableId)), () -> {
            Domain.DiningTable table = requireTable(merchantId, tableId);
            if (table.status() != TableStatus.CLEANING) {
                throw new IllegalArgumentException("仅清理中的桌台可标记为清理完毕");
            }
            Domain.DiningTable next = table.withStatus(TableStatus.EMPTY, 0, null, null);
            store.saveTable(next);
            store.log(merchantId, table.storeId(), operatorId, "CLEAN_COMPLETE", table.tableNo());
            realtime.tableChanged(merchantId, table.storeId(), table.id(), "CLEAN_COMPLETE");
            return next;
        });
    }

    public Domain.DiningTable reserveTable(long merchantId, long tableId, String name, String phone,
                                           String arrivalTime, long operatorId) {
        return withLocks(List.of(tableLock(merchantId, tableId)), () -> {
            Domain.DiningTable table = requireTable(merchantId, tableId);
            if (table.status() != TableStatus.EMPTY && table.status() != TableStatus.RESERVED) {
                throw new IllegalArgumentException("仅空闲桌台可预约");
            }
            String safeName = name == null ? "" : name.trim();
            String safePhone = phone == null ? "" : phone.trim();
            String safeArrivalTime = arrivalTime == null ? "" : arrivalTime.trim();
            if (safeName.isEmpty() || safePhone.isEmpty()) {
                throw new IllegalArgumentException("预约人和预约电话不能为空");
            }
            Domain.DiningTable next = table.reserved(safeName, safePhone, safeArrivalTime);
            store.saveTable(next);
            store.log(merchantId, table.storeId(), operatorId, "RESERVE_TABLE",
                    table.tableNo() + " " + safeName + " " + safePhone + " " + safeArrivalTime);
            realtime.tableChanged(merchantId, table.storeId(), table.id(), "TABLE_RESERVED");
            return next;
        });
    }

    public Domain.DiningTable cancelReservation(long merchantId, long tableId, long operatorId) {
        return withLocks(List.of(tableLock(merchantId, tableId)), () -> {
            Domain.DiningTable table = requireTable(merchantId, tableId);
            if (table.status() != TableStatus.RESERVED) {
                throw new IllegalArgumentException("仅已预约桌台可取消预约");
            }
            Domain.DiningTable next = table.withStatus(TableStatus.EMPTY, 0, null, null);
            store.saveTable(next);
            store.log(merchantId, table.storeId(), operatorId, "CANCEL_RESERVATION", table.tableNo());
            realtime.tableChanged(merchantId, table.storeId(), table.id(), "RESERVATION_CANCELLED");
            return next;
        });
    }

    public List<Domain.DishCategory> categories(long merchantId, Long storeId) {
        return store.queryCategories(merchantId, storeId);
    }

    public List<Domain.Dish> dishes(long merchantId, Long storeId, Boolean enabled) {
        return store.queryDishes(merchantId, storeId, enabled);
    }

    public Domain.Order submitOrder(long merchantId, long waiterId, String waiterName, SubmitOrderRequest request) {
        return withLocks(List.of(tableLock(merchantId, request.tableId())), () -> {
            Domain.DiningTable table = requireTable(merchantId, request.tableId());
            // Allow one-step "open table + submit order" from the waiter APP.
            if (table.status() == TableStatus.EMPTY || table.status() == TableStatus.RESERVED) {
                table = openTableInternal(merchantId, table.id(), request.people());
            }
            if (table.status() != TableStatus.DINING) {
                throw new IllegalArgumentException("当前桌台状态不可下单");
            }
            long orderId = store.nextId();
            List<Domain.OrderItem> items = buildItems(merchantId, table.storeId(), orderId, request.items());
            BigDecimal total = items.stream().map(Domain.OrderItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            int people = table.currentPeople() == 0 ? request.people() : table.currentPeople();
            Domain.Order order = new Domain.Order(orderId, merchantId, table.storeId(), table.id(), table.tableNo(), people, waiterId, waiterName,
                    OrderStatus.PENDING_KITCHEN, total, request.remark(), null, LocalDateTime.now(), LocalDateTime.now(), items);
            store.saveOrder(order);
            store.saveTable(table.withStatus(TableStatus.DINING, people,
                    table.openedAt() == null ? LocalDateTime.now() : table.openedAt(), order.id()));
            store.log(merchantId, table.storeId(), waiterId, "SUBMIT_ORDER", "order " + order.id());
            realtime.orderChanged(merchantId, table.storeId(), order.id(), "ORDER_SUBMITTED");
            realtime.tableChanged(merchantId, table.storeId(), table.id(), "TABLE_ORDERED");
            // APP never talks to printers. Backend creates kitchen print tasks after the order is accepted.
            printerService.printKitchenOrder(order, PrintScene.KITCHEN_ORDER);
            return order;
        });
    }

    public Domain.Order cashierAssistOrder(long merchantId, long cashierId, String cashierName, SubmitOrderRequest request) {
        return submitOrder(merchantId, cashierId, cashierName, request);
    }

    public Domain.Order directCheckout(long merchantId, Long storeId, long cashierId, String cashierName,
                                       DirectCheckoutRequest request) {
        Long saleStoreId = storeId == null ? request.storeId() : storeId;
        long resolvedStoreId = resolveDirectSaleStoreId(merchantId, saleStoreId, request.items());
        PaymentMethod method = request.method() == null ? PaymentMethod.CASH : request.method();
        if (method != PaymentMethod.CASH && (request.referenceNo() == null || request.referenceNo().isBlank())) {
            throw new IllegalArgumentException("非现金支付必须填写交易参考号");
        }
        long orderId = store.nextId();
        List<Domain.OrderItem> items = buildItems(merchantId, resolvedStoreId, orderId, request.items());
        BigDecimal total = items.stream().map(Domain.OrderItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        Domain.Order order = new Domain.Order(orderId, merchantId, resolvedStoreId, 0L, "收银台", 0, cashierId, cashierName,
                OrderStatus.PAID, total, request.remark(), null, LocalDateTime.now(), LocalDateTime.now(), items);
        store.saveOrder(order);
        Domain.Payment payment = new Domain.Payment(store.nextId(), merchantId, resolvedStoreId, order.id(), method,
                total, request.referenceNo(), LocalDateTime.now(), cashierId);
        store.savePayment(payment);
        store.log(merchantId, resolvedStoreId, cashierId, "DIRECT_CHECKOUT", "order " + order.id());
        realtime.orderChanged(merchantId, resolvedStoreId, order.id(), "DIRECT_CHECKOUT");
        printerService.printReceipt(order, payment);
        return order;
    }

    public Domain.Order modifyOrder(long merchantId, long orderId, SubmitOrderRequest request, long operatorId) {
        Domain.Order initial = requireOrder(merchantId, orderId);
        return withLocks(List.of(orderLock(merchantId, orderId), tableLock(merchantId, initial.tableId())), () -> {
            Domain.Order order = requireOrder(merchantId, orderId);
            if (order.status() == OrderStatus.PAID || order.status() == OrderStatus.CANCELLED || order.status() == OrderStatus.PENDING_CHECKOUT) {
                throw new IllegalArgumentException("仅未结账订单可加菜/退菜");
            }
            List<Domain.OrderItem> previousItems = order.items();
            List<Domain.OrderItem> items = buildItems(merchantId, order.storeId(), orderId, request.items());
            BigDecimal total = items.stream().map(Domain.OrderItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            OrderStatus nextStatus = order.status() == OrderStatus.SERVED ? OrderStatus.COOKING : order.status();
            Domain.Order next = new Domain.Order(order.id(), order.merchantId(), order.storeId(), order.tableId(), order.tableNo(), order.people(),
                    order.waiterId(), order.waiterName(), nextStatus, total, request.remark(), order.cancelReason(),
                    order.createdAt(), LocalDateTime.now(), items);
            store.saveOrder(next);
            store.log(merchantId, order.storeId(), operatorId, "MODIFY_ORDER", "order " + order.id());
            realtime.orderChanged(merchantId, order.storeId(), order.id(), "ORDER_ITEMS_CHANGED");
            List<Domain.OrderItem> addedItems = addedItems(previousItems, items);
            if (!addedItems.isEmpty()) {
                printerService.printKitchenAdditions(next, addedItems);
            } else {
                printerService.printKitchenOrder(next, PrintScene.KITCHEN_ADD_OR_RETURN);
            }
            return next;
        });
    }

    public Domain.Order cancelOrder(long merchantId, long orderId, String reason, long operatorId) {
        Domain.Order initial = requireOrder(merchantId, orderId);
        return withLocks(List.of(orderLock(merchantId, orderId), tableLock(merchantId, initial.tableId())), () -> {
            Domain.Order order = requireOrder(merchantId, orderId);
            // Once kitchen starts processing, cancellation must go through exception handling, not normal cancel.
            if (order.status() != OrderStatus.PENDING_KITCHEN) {
                throw new IllegalArgumentException("仅后厨未接单订单可取消");
            }
            Domain.Order next = order.cancelled(reason);
            store.saveOrder(next);
            Domain.DiningTable table = requireTable(merchantId, order.tableId());
            store.saveTable(table.withStatus(TableStatus.CLEANING, 0, null, null));
            store.log(merchantId, order.storeId(), operatorId, "CANCEL_ORDER", "order " + order.id() + ": " + reason);
            realtime.orderChanged(merchantId, order.storeId(), order.id(), "ORDER_CANCELLED");
            realtime.tableChanged(merchantId, table.storeId(), table.id(), "TABLE_CLEANING");
            return next;
        });
    }

    public Domain.Order returnOrderItem(long merchantId, long orderId, long itemId, int quantity, long operatorId) {
        Domain.Order initial = requireOrder(merchantId, orderId);
        return withLocks(List.of(orderLock(merchantId, orderId), tableLock(merchantId, initial.tableId())), () -> {
            Domain.Order order = requireOrder(merchantId, orderId);
            if (order.status() == OrderStatus.PAID || order.status() == OrderStatus.CANCELLED || order.status() == OrderStatus.PENDING_CHECKOUT) {
                throw new IllegalArgumentException("当前订单不可退餐");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("退餐数量必须大于0");
            }
            Domain.OrderItem target = order.items().stream()
                    .filter(item -> item.id() == itemId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("订单菜品不存在"));
            if (quantity > target.quantity()) {
                throw new IllegalArgumentException("退餐数量不能超过已点数量");
            }
            List<Domain.OrderItem> returnedItems = List.of(target.withQuantity(quantity).withStatus(OrderItemStatus.CANCELLED));
            List<Domain.OrderItem> nextItems = order.items().stream()
                    .map(item -> item.id() == itemId ? item.withQuantity(item.quantity() - quantity) : item)
                    .filter(item -> item.quantity() > 0)
                    .toList();
            if (nextItems.isEmpty()) {
                throw new IllegalArgumentException("订单至少保留一个菜品");
            }
            BigDecimal total = nextItems.stream().map(Domain.OrderItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            Domain.Order next = order.withItems(nextItems, total);
            store.saveOrder(next);
            store.log(merchantId, order.storeId(), operatorId, "RETURN_DISH",
                    "order " + order.id() + ", item " + itemId + ", quantity " + quantity);
            realtime.orderChanged(merchantId, order.storeId(), order.id(), "ORDER_ITEM_RETURNED");
            printerService.printKitchenAdditions(next, returnedItems);
            return next;
        });
    }

    public Domain.Order markServed(long merchantId, long orderId, long itemId) {
        Domain.Order initial = requireOrder(merchantId, orderId);
        return withLocks(List.of(orderLock(merchantId, orderId), tableLock(merchantId, initial.tableId())), () -> {
            Domain.Order order = requireOrder(merchantId, orderId);
            List<Domain.OrderItem> items = order.items().stream()
                    .map(i -> itemId == 0 || i.id() == itemId ? i.withStatus(OrderItemStatus.SERVED) : i)
                    .toList();
            boolean allServed = items.stream().allMatch(i -> i.status() == OrderItemStatus.SERVED);
            Domain.Order next = new Domain.Order(order.id(), order.merchantId(), order.storeId(), order.tableId(), order.tableNo(), order.people(), order.waiterId(),
                    order.waiterName(), allServed ? OrderStatus.SERVED : order.status(), order.totalAmount(), order.remark(),
                    order.cancelReason(), order.createdAt(), LocalDateTime.now(), items);
            store.saveOrder(next);
            realtime.orderChanged(merchantId, order.storeId(), order.id(), "ORDER_SERVED");
            return next;
        });
    }

    public Domain.Order requestCheckout(long merchantId, long orderId) {
        Domain.Order initial = requireOrder(merchantId, orderId);
        return withLocks(List.of(orderLock(merchantId, orderId), tableLock(merchantId, initial.tableId())), () -> {
            Domain.Order order = requireOrder(merchantId, orderId);
            if (order.status() == OrderStatus.PAID || order.status() == OrderStatus.CANCELLED) {
                throw new IllegalArgumentException("订单不可结账");
            }
            Domain.Order next = order.withStatus(OrderStatus.PENDING_CHECKOUT);
            store.saveOrder(next);
            Domain.DiningTable table = requireTable(merchantId, order.tableId());
            store.saveTable(table.withStatus(TableStatus.PENDING_CHECKOUT, table.currentPeople(), table.openedAt(), order.id()));
            realtime.orderChanged(merchantId, order.storeId(), order.id(), "CHECKOUT_REQUESTED");
            realtime.tableChanged(merchantId, table.storeId(), table.id(), "TABLE_PENDING_CHECKOUT");
            return next;
        });
    }

    public Domain.Payment checkout(long merchantId, long orderId, PaymentMethod method, String referenceNo, long cashierId) {
        Domain.Order initial = requireOrder(merchantId, orderId);
        return withLocks(List.of(orderLock(merchantId, orderId), tableLock(merchantId, initial.tableId())), () -> {
            Domain.Order order = requireOrder(merchantId, orderId);
            if (order.status() == OrderStatus.PAID || order.status() == OrderStatus.CANCELLED) {
                throw new IllegalArgumentException("订单不可重复结账");
            }
            // Cash may have no reference number; e-wallet/card/QR payments must keep an audit reference.
            if (method != PaymentMethod.CASH && (referenceNo == null || referenceNo.isBlank())) {
                throw new IllegalArgumentException("非现金支付必须填写交易参考号");
            }
            Domain.Payment payment = new Domain.Payment(store.nextId(), merchantId, order.storeId(), order.id(), method, order.totalAmount(),
                    referenceNo, LocalDateTime.now(), cashierId);
            store.savePayment(payment);
            Domain.Order paid = order.withStatus(OrderStatus.PAID);
            store.saveOrder(paid);
            Domain.DiningTable table = requireTable(merchantId, order.tableId());
            store.saveTable(table.withStatus(TableStatus.CLEANING, 0, null, null));
            store.log(merchantId, order.storeId(), cashierId, "CHECKOUT", method + " order " + order.id());
            realtime.orderChanged(merchantId, order.storeId(), order.id(), "ORDER_PAID");
            realtime.tableChanged(merchantId, table.storeId(), table.id(), "TABLE_CLEANING");
            // Front desk receipt printing is also backend-dispatched to avoid hardware access from clients.
            printerService.printReceipt(paid, payment);
            return payment;
        });
    }

    public List<Domain.Order> orders(long merchantId, Long storeId, String status, Long waiterId, String tableNo) {
        return orders(merchantId, storeId, status, waiterId, tableNo, 100, 0);
    }

    public List<Domain.Order> orders(long merchantId, Long storeId, String status, Long waiterId, String tableNo,
                                     Integer limit, Integer offset) {
        return orders(merchantId, storeId, status, waiterId, tableNo, null, null, limit, offset);
    }

    public List<Domain.Order> orders(long merchantId, Long storeId, String status, Long waiterId, String tableNo,
                                     LocalDateTime startAt, LocalDateTime endAt, Integer limit, Integer offset) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 200));
        int safeOffset = Math.max(0, offset == null ? 0 : offset);
        return store.queryOrders(merchantId, storeId, status, waiterId, tableNo, startAt, endAt, safeLimit, safeOffset);
    }

    public Map<String, Object> stats(long merchantId, Long storeId) {
        return store.stats(merchantId, storeId);
    }

    private List<Domain.OrderItem> buildItems(long merchantId, long storeId, long orderId, List<OrderItemRequest> requestItems) {
        if (requestItems == null || requestItems.isEmpty()) {
            throw new IllegalArgumentException("订单菜品不能为空");
        }
        return requestItems.stream().map(item -> {
            Domain.Dish dish = store.dishes.get(item.dishId());
            if (dish == null) {
                dish = store.queryDishById(item.dishId());
            }
            // Multi-merchant isolation and on-shelf check happen before price/name are copied into order items.
            if (dish == null || dish.merchantId() != merchantId || dish.storeId() != storeId || !dish.enabled()) {
                throw new IllegalArgumentException("菜品不存在或已下架");
            }
            if (item.quantity() <= 0) {
                throw new IllegalArgumentException("菜品数量必须大于0");
            }
            return new Domain.OrderItem(store.nextId(), orderId, dish.id(), dish.nameZh(), dish.nameEn(), dish.nameMs(), dish.imageUrl(),
                    item.quantity(), dish.price(), item.remark(), OrderItemStatus.PENDING_KITCHEN);
        }).toList();
    }

    private long resolveDirectSaleStoreId(long merchantId, Long storeId, List<OrderItemRequest> requestItems) {
        if (storeId != null) {
            return storeId;
        }
        if (requestItems == null || requestItems.isEmpty()) {
            throw new IllegalArgumentException("订单菜品不能为空");
        }
        Domain.Dish dish = store.dishes.get(requestItems.get(0).dishId());
        if (dish == null) {
            dish = store.queryDishById(requestItems.get(0).dishId());
        }
        if (dish == null || dish.merchantId() != merchantId || !dish.enabled()) {
            throw new IllegalArgumentException("菜品不存在或已下架");
        }
        return dish.storeId();
    }

    public Domain.DiningTable requireTable(long merchantId, long tableId) {
        Domain.DiningTable table = store.queryTableById(tableId);
        if (table == null || table.merchantId() != merchantId) {
            throw new IllegalArgumentException("桌台不存在");
        }
        return table;
    }

    public Domain.Order requireOrder(long merchantId, long orderId) {
        Domain.Order order = store.queryOrderById(orderId);
        if (order == null || order.merchantId() != merchantId) {
            throw new IllegalArgumentException("订单不存在");
        }
        return order;
    }

    public Domain.Order currentOrder(long merchantId, long tableId) {
        return store.queryCurrentOrder(merchantId, tableId);
    }

    private Domain.DiningTable openTableInternal(long merchantId, long tableId, int people) {
        Domain.DiningTable table = requireTable(merchantId, tableId);
        // Business rule: only an empty table can be opened, and people cannot exceed configured capacity.
        if (table.status() != TableStatus.EMPTY && table.status() != TableStatus.RESERVED) {
            throw new IllegalArgumentException("仅空闲桌台可开台");
        }
        if (people < 1 || people > table.maxPeople()) {
            throw new IllegalArgumentException("就餐人数超出桌台容量");
        }
        Domain.DiningTable next = table.withStatus(TableStatus.DINING, people, LocalDateTime.now(), null);
        store.saveTable(next);
        realtime.tableChanged(merchantId, table.storeId(), table.id(), "TABLE_OPENED");
        return next;
    }

    private List<Domain.OrderItem> addedItems(List<Domain.OrderItem> previousItems, List<Domain.OrderItem> nextItems) {
        Map<Long, Integer> previousQuantities = new HashMap<>();
        for (Domain.OrderItem item : previousItems) {
            previousQuantities.put(item.dishId(), item.quantity());
        }
        return nextItems.stream()
                .filter(item -> item.quantity() > previousQuantities.getOrDefault(item.dishId(), 0))
                .toList();
    }

    private <T> T withLocks(List<String> keys, java.util.function.Supplier<T> action) {
        List<String> sortedKeys = keys.stream().filter(Objects::nonNull).distinct().sorted().toList();
        String token = UUID.randomUUID().toString();
        List<String> acquired = new java.util.ArrayList<>();
        try {
            for (String key : sortedKeys) {
                Boolean ok = redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(10));
                if (!Boolean.TRUE.equals(ok)) {
                    throw new IllegalStateException("系统繁忙，请稍后再试");
                }
                acquired.add(key);
            }
            return action.get();
        } finally {
            for (String key : acquired) {
                redis.execute(UNLOCK_SCRIPT, List.of(key), token);
            }
        }
    }

    private String tableLock(long merchantId, long tableId) {
        return "restaurant:lock:table:" + merchantId + ":" + tableId;
    }

    private String orderLock(long merchantId, long orderId) {
        return "restaurant:lock:order:" + merchantId + ":" + orderId;
    }

    public record SubmitOrderRequest(long tableId, int people, String remark, List<OrderItemRequest> items) {
    }

    public record DirectCheckoutRequest(Long storeId, PaymentMethod method, String referenceNo, String remark,
                                        List<OrderItemRequest> items) {
    }

    public record OrderItemRequest(long dishId, int quantity, String remark) {
    }
}
