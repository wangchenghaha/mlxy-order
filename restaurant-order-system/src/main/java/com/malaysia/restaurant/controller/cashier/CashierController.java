package com.malaysia.restaurant.controller.cashier;

import com.malaysia.restaurant.common.enums.PaymentMethod;
import com.malaysia.restaurant.common.enums.Role;
import com.malaysia.restaurant.common.result.ApiResponse;
import com.malaysia.restaurant.entity.Domain;
import com.malaysia.restaurant.service.AuthService;
import com.malaysia.restaurant.service.InMemoryStore;
import com.malaysia.restaurant.service.OrderService;
import com.malaysia.restaurant.service.PrinterService;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/cashier")
public class CashierController {
    private final AuthService auth;
    private final OrderService orders;
    private final InMemoryStore store;
    private final PrinterService printerService;

    public CashierController(AuthService auth, OrderService orders, InMemoryStore store, PrinterService printerService) {
        this.auth = auth;
        this.orders = orders;
        this.store = store;
        this.printerService = printerService;
    }

    @GetMapping("/tables")
    public ApiResponse<List<TableBillView>> tables(@RequestHeader("Authorization") String token) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        List<TableBillView> views = orders.tables(user.requiredMerchantId(), user.storeId()).stream()
                .map(table -> {
                    Domain.Order order = table.currentOrderId() == null ? null : store.queryOrderById(table.currentOrderId());
                    return new TableBillView(table, order);
                })
                .toList();
        return ApiResponse.ok(views);
    }

    @GetMapping("/orders")
    public ApiResponse<List<Domain.Order>> orders(@RequestHeader("Authorization") String token,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String tableNo,
                                                  @RequestParam(required = false) Integer limit,
                                                  @RequestParam(required = false) Integer offset) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.orders(user.requiredMerchantId(), user.storeId(), status, null, tableNo, limit, offset));
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(@RequestHeader("Authorization") String token) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(store.stats(user.requiredMerchantId(), user.storeId()));
    }

    @GetMapping("/payment-methods")
    public ApiResponse<List<PaymentMethod>> paymentMethods(@RequestHeader("Authorization") String token) {
        auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(Arrays.asList(PaymentMethod.values()));
    }

    @PostMapping("/orders/{id}/checkout")
    public ApiResponse<Domain.Payment> checkout(@RequestHeader("Authorization") String token,
                                                @PathVariable long id,
                                                @RequestBody CheckoutRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.checkout(user.requiredMerchantId(), id, request.method(), request.referenceNo(), user.id()));
    }

    @PostMapping("/tables/transfer")
    public ApiResponse<Domain.DiningTable> transfer(@RequestHeader("Authorization") String token,
                                                    @RequestBody TransferRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.transferTable(user.requiredMerchantId(), request.fromTableId(), request.toTableId(), user.id()));
    }

    @PostMapping("/tables/{id}/clean-complete")
    public ApiResponse<Domain.DiningTable> cleanComplete(@RequestHeader("Authorization") String token,
                                                         @PathVariable long id) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.completeCleaning(user.requiredMerchantId(), id, user.id()));
    }

    @PostMapping("/tables/{id}/reserve")
    public ApiResponse<Domain.DiningTable> reserve(@RequestHeader("Authorization") String token,
                                                   @PathVariable long id,
                                                   @RequestBody ReserveRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.reserveTable(user.requiredMerchantId(), id, request.name(), request.phone(),
                request.arrivalTime(), user.id()));
    }

    @PostMapping("/tables/{id}/reservation/cancel")
    public ApiResponse<Domain.DiningTable> cancelReservation(@RequestHeader("Authorization") String token,
                                                             @PathVariable long id) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.cancelReservation(user.requiredMerchantId(), id, user.id()));
    }

    @GetMapping("/categories")
    public ApiResponse<List<Domain.DishCategory>> categories(@RequestHeader("Authorization") String token,
                                                             @RequestParam(required = false) Long storeId) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.categories(user.requiredMerchantId(), scopedStoreId(user, storeId)));
    }

    @GetMapping("/dishes")
    public ApiResponse<List<Domain.Dish>> dishes(@RequestHeader("Authorization") String token,
                                                 @RequestParam(required = false) Long storeId) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.dishes(user.requiredMerchantId(), scopedStoreId(user, storeId), true));
    }

    @PostMapping("/orders")
    public ApiResponse<Domain.Order> assistOrder(@RequestHeader("Authorization") String token,
                                                 @RequestBody OrderService.SubmitOrderRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.cashierAssistOrder(user.requiredMerchantId(), user.id(), user.displayName(), request));
    }

    @PostMapping("/register/checkout")
    public ApiResponse<Domain.Order> registerCheckout(@RequestHeader("Authorization") String token,
                                                      @RequestBody OrderService.DirectCheckoutRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.directCheckout(user.requiredMerchantId(), scopedStoreId(user, request.storeId()),
                user.id(), user.displayName(), request));
    }

    @PutMapping("/orders/{id}")
    public ApiResponse<Domain.Order> modifyOrder(@RequestHeader("Authorization") String token,
                                                 @PathVariable long id,
                                                 @RequestBody OrderService.SubmitOrderRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.modifyOrder(user.requiredMerchantId(), id, request, user.id()));
    }

    @PostMapping("/orders/{id}/items/{itemId}/return")
    public ApiResponse<Domain.Order> returnDish(@RequestHeader("Authorization") String token,
                                                @PathVariable long id,
                                                @PathVariable long itemId,
                                                @RequestBody ReturnDishRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.returnOrderItem(user.requiredMerchantId(), id, itemId, request.quantity(), user.id()));
    }

    @PostMapping("/orders/{id}/bill-print")
    public ApiResponse<String> billPrint(@RequestHeader("Authorization") String token, @PathVariable long id) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        Domain.Order order = orders.requireOrder(user.requiredMerchantId(), id);
        printerService.printBill(order);
        return ApiResponse.ok("bill print submitted");
    }

    @PostMapping("/orders/{id}/reprint")
    public ApiResponse<String> reprint(@RequestHeader("Authorization") String token, @PathVariable long id) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        Domain.Order order = orders.requireOrder(user.requiredMerchantId(), id);
        Domain.Payment payment = store.queryPaymentByOrderId(id);
        if (payment == null) {
            throw new IllegalArgumentException("未找到支付记录");
        }
        printerService.printReceipt(order, payment);
        return ApiResponse.ok("reprint submitted");
    }

    @PostMapping("/printers/{id}/test")
    public ApiResponse<Domain.PrintTask> testPrint(@RequestHeader("Authorization") String token, @PathVariable long id) {
        AuthService.AuthContext user = auth.require(token, Role.CASHIER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(printerService.testPrint(user.requiredMerchantId(), id));
    }

    public record CheckoutRequest(PaymentMethod method, String referenceNo) {
    }

    public record TransferRequest(long fromTableId, long toTableId) {
    }

    public record ReserveRequest(String name, String phone, String arrivalTime) {
    }

    public record ReturnDishRequest(int quantity) {
    }

    public record TableBillView(Domain.DiningTable table, Domain.Order order) {
        public TableBillView {
            if (order != null && !Objects.equals(table.currentOrderId(), order.id())) {
                throw new IllegalArgumentException("桌台订单不匹配");
            }
        }
    }

    private Long scopedStoreId(AuthService.AuthContext user, Long requestedStoreId) {
        if (user.storeId() != null) {
            if (requestedStoreId != null && !Objects.equals(user.storeId(), requestedStoreId)) {
                throw new IllegalArgumentException("无权访问该门店");
            }
            return user.storeId();
        }
        return requestedStoreId;
    }
}
