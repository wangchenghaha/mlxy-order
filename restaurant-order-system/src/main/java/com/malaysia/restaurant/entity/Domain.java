package com.malaysia.restaurant.entity;

import com.malaysia.restaurant.common.enums.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class Domain {
    private Domain() {
    }

    public record Merchant(long id, String nameZh, String nameEn, String nameMs, String phone, String address,
                           String status) {
    }

    public record Store(long id, long merchantId, String code, String name, String phone, String address, String status) {
    }

    public record User(long id, String phone, String username, String passwordHash, String displayName,
                       String avatarUrl, boolean enabled, LocalDateTime lockedUntil, int failCount) {
        public User withFailCount(int count, LocalDateTime locked) {
            return new User(id, phone, username, passwordHash, displayName, avatarUrl, enabled, locked, count);
        }

        public User withEnabled(boolean nextEnabled) {
            return new User(id, phone, username, passwordHash, displayName, avatarUrl, nextEnabled, lockedUntil, failCount);
        }

        public User withPasswordHash(String nextPasswordHash) {
            return new User(id, phone, username, nextPasswordHash, displayName, avatarUrl, enabled, lockedUntil, failCount);
        }

        public User withProfile(String nextPhone, String nextDisplayName, String nextAvatarUrl) {
            return new User(id, nextPhone, username, passwordHash, nextDisplayName, nextAvatarUrl, enabled, lockedUntil, failCount);
        }
    }

    public record UserMembership(long id, long userId, Long merchantId, Long storeId, Role role, String status) {
        public boolean active() {
            return "ACTIVE".equalsIgnoreCase(status);
        }
    }

    public record SysRole(String code, String name, String scope, String description, String dataScope, String status) {
        public boolean active() {
            return !"DELETED".equalsIgnoreCase(status);
        }
    }

    public record SysMenu(String code, String name, String parentCode, int sortNo, String icon, boolean visible,
                          String path, String component, String componentName, boolean keepAlive, String permission) {
    }

    public record DiningTable(long id, long merchantId, long storeId, String area, String tableNo, int maxPeople,
                              TableStatus status, int currentPeople, LocalDateTime openedAt, Long currentOrderId,
                              String reservationName, String reservationPhone, String reservationArrivalTime) {
        public DiningTable withStatus(TableStatus next, int people, LocalDateTime openedAt, Long orderId) {
            return new DiningTable(id, merchantId, storeId, area, tableNo, maxPeople, next, people, openedAt, orderId,
                    next == TableStatus.RESERVED ? reservationName : null,
                    next == TableStatus.RESERVED ? reservationPhone : null,
                    next == TableStatus.RESERVED ? reservationArrivalTime : null);
        }

        public DiningTable reserved(String name, String phone, String arrivalTime) {
            return new DiningTable(id, merchantId, storeId, area, tableNo, maxPeople, TableStatus.RESERVED, 0,
                    null, null, name, phone, arrivalTime);
        }
    }

    public record DishCategory(long id, long merchantId, long storeId, String nameZh, String nameEn, String nameMs,
                               int sortNo) {
    }

    public record Dish(long id, long merchantId, long storeId, long categoryId, String nameZh, String nameEn, String nameMs,
                       String descriptionZh, String descriptionEn, String descriptionMs, String imageUrl,
                       BigDecimal price, String spec, int stock, boolean enabled) {
        public Dish withEnabled(boolean next) {
            return new Dish(id, merchantId, storeId, categoryId, nameZh, nameEn, nameMs, descriptionZh, descriptionEn,
                    descriptionMs, imageUrl, price, spec, stock, next);
        }
    }

    public record Order(long id, long merchantId, long storeId, long tableId, String tableNo, int people, long waiterId, String waiterName,
                        OrderStatus status, BigDecimal totalAmount, String remark, String cancelReason,
                        LocalDateTime createdAt, LocalDateTime updatedAt, List<OrderItem> items) {
        public Order {
            items = items == null ? new ArrayList<>() : new ArrayList<>(items);
        }

        public Order withStatus(OrderStatus next) {
            return new Order(id, merchantId, storeId, tableId, tableNo, people, waiterId, waiterName, next, totalAmount, remark,
                    cancelReason, createdAt, LocalDateTime.now(), items);
        }

        public Order withItems(List<OrderItem> nextItems, BigDecimal total) {
            return new Order(id, merchantId, storeId, tableId, tableNo, people, waiterId, waiterName, status, total, remark,
                    cancelReason, createdAt, LocalDateTime.now(), nextItems);
        }

        public Order cancelled(String reason) {
            return new Order(id, merchantId, storeId, tableId, tableNo, people, waiterId, waiterName, OrderStatus.CANCELLED,
                    totalAmount, remark, reason, createdAt, LocalDateTime.now(), items);
        }
    }

    public record OrderItem(long id, long orderId, long dishId, String dishNameZh, String dishNameEn, String dishNameMs,
                            String imageUrl, int quantity, BigDecimal unitPrice, String remark, OrderItemStatus status) {
        public BigDecimal subtotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }

        public OrderItem withStatus(OrderItemStatus next) {
            return new OrderItem(id, orderId, dishId, dishNameZh, dishNameEn, dishNameMs, imageUrl, quantity, unitPrice, remark, next);
        }

        public OrderItem withQuantity(int nextQuantity) {
            return new OrderItem(id, orderId, dishId, dishNameZh, dishNameEn, dishNameMs, imageUrl, nextQuantity, unitPrice, remark, status);
        }
    }

    public record Payment(long id, long merchantId, long storeId, long orderId, PaymentMethod method, BigDecimal amount,
                          String referenceNo, LocalDateTime paidAt, long cashierId) {
    }

    public record Printer(long id, long merchantId, long storeId, String name, PrinterType type, String ip, int port,
                          boolean enabled) {
    }

    public record PrintTask(long id, long merchantId, long storeId, Long orderId, long printerId, PrintScene scene,
                            String content, PrintStatus status, int retryCount, String lastError,
                            LocalDateTime nextRetryAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public PrintTask printed() {
            return new PrintTask(id, merchantId, storeId, orderId, printerId, scene, content, PrintStatus.PRINTED, retryCount,
                    null, null, createdAt, LocalDateTime.now());
        }

        public PrintTask failed(String error, LocalDateTime retryAt) {
            return new PrintTask(id, merchantId, storeId, orderId, printerId, scene, content, PrintStatus.FAILED,
                    retryCount + 1, error, retryAt, createdAt, LocalDateTime.now());
        }
    }

    public record OperationLog(long id, Long merchantId, Long storeId, long operatorId, String action, String detail,
                               LocalDateTime createdAt) {
    }

    public record SysI18n(long id, String key, String zhCn, String enUs, String msMy, String remark) {
        public String value(String lang) {
            return switch (lang == null ? "ms_my" : lang) {
                case "zh_cn" -> zhCn;
                case "en_us" -> enUs;
                default -> msMy;
            };
        }
    }
}
