package com.malaysia.restaurant.controller.waiter;

import com.malaysia.restaurant.common.enums.Role;
import com.malaysia.restaurant.common.result.ApiResponse;
import com.malaysia.restaurant.entity.Domain;
import com.malaysia.restaurant.service.AuthService;
import com.malaysia.restaurant.service.IdempotencyService;
import com.malaysia.restaurant.service.OrderService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/waiter")
public class WaiterController {
    private final AuthService auth;
    private final OrderService orders;
    private final IdempotencyService idempotency;

    public WaiterController(AuthService auth, OrderService orders, IdempotencyService idempotency) {
        this.auth = auth;
        this.orders = orders;
        this.idempotency = idempotency;
    }

    @GetMapping("/tables")
    public ApiResponse<List<Domain.DiningTable>> tables(@RequestHeader("Authorization") String token) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.tables(user.requiredMerchantId(), user.storeId()));
    }

    @PostMapping("/tables/{id}/open")
    public ApiResponse<Domain.DiningTable> open(@RequestHeader("Authorization") String token,
                                                @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                                @PathVariable long id,
                                                @RequestBody OpenTableRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(idempotency.run(idempotencyKey, user, "waiter-open-table",
                () -> orders.openTable(user.requiredMerchantId(), id, request.people()), Domain.DiningTable.class));
    }

    @PostMapping("/tables/transfer")
    public ApiResponse<Domain.DiningTable> transfer(@RequestHeader("Authorization") String token,
                                                    @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                                    @RequestBody TransferRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(idempotency.run(idempotencyKey, user, "waiter-transfer-table",
                () -> orders.transferTable(user.requiredMerchantId(), request.fromTableId(), request.toTableId(), user.id()), Domain.DiningTable.class));
    }

    @PostMapping("/tables/{id}/clean-complete")
    public ApiResponse<Domain.DiningTable> cleanComplete(@RequestHeader("Authorization") String token,
                                                         @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                                         @PathVariable long id) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(idempotency.run(idempotencyKey, user, "waiter-clean-complete",
                () -> orders.completeCleaning(user.requiredMerchantId(), id, user.id()), Domain.DiningTable.class));
    }

    @GetMapping("/dishes")
    public ApiResponse<List<Domain.Dish>> dishes(@RequestHeader("Authorization") String token) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.dishes(user.requiredMerchantId(), user.storeId(), true));
    }

    @GetMapping("/categories")
    public ApiResponse<List<Domain.DishCategory>> categories(@RequestHeader("Authorization") String token) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.categories(user.requiredMerchantId(), user.storeId()));
    }

    @PostMapping("/orders")
    public ApiResponse<Domain.Order> submit(@RequestHeader("Authorization") String token,
                                            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                            @RequestBody OrderService.SubmitOrderRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(idempotency.run(idempotencyKey, user, "waiter-submit-order",
                () -> orders.submitOrder(user.requiredMerchantId(), user.id(), user.displayName(), request), Domain.Order.class));
    }

    @PutMapping("/orders/{id}")
    public ApiResponse<Domain.Order> modify(@RequestHeader("Authorization") String token,
                                            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                            @PathVariable long id,
                                            @RequestBody OrderService.SubmitOrderRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(idempotency.run(idempotencyKey, user, "waiter-modify-order",
                () -> orders.modifyOrder(user.requiredMerchantId(), id, request, user.id()), Domain.Order.class));
    }

    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<Domain.Order> cancel(@RequestHeader("Authorization") String token,
                                            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                            @PathVariable long id,
                                            @RequestBody CancelRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(idempotency.run(idempotencyKey, user, "waiter-cancel-order",
                () -> orders.cancelOrder(user.requiredMerchantId(), id, request.reason(), user.id()), Domain.Order.class));
    }

    @PostMapping("/orders/{id}/served")
    public ApiResponse<Domain.Order> served(@RequestHeader("Authorization") String token,
                                            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                            @PathVariable long id,
                                            @RequestBody ServedRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(idempotency.run(idempotencyKey, user, "waiter-mark-served",
                () -> orders.markServed(user.requiredMerchantId(), id, request.itemId()), Domain.Order.class));
    }

    @PostMapping("/orders/{id}/checkout-request")
    public ApiResponse<Domain.Order> checkoutRequest(@RequestHeader("Authorization") String token,
                                                     @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                                     @PathVariable long id) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(idempotency.run(idempotencyKey, user, "waiter-checkout-request",
                () -> orders.requestCheckout(user.requiredMerchantId(), id), Domain.Order.class));
    }

    @GetMapping("/orders")
    public ApiResponse<List<Domain.Order>> myOrders(@RequestHeader("Authorization") String token,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                                    @RequestParam(required = false) Integer limit,
                                                    @RequestParam(required = false) Integer offset) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        LocalDateTime startAt = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime endExclusiveAt = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        return ApiResponse.ok(orders.orders(user.requiredMerchantId(), user.storeId(), status,
                user.role() == Role.WAITER ? user.id() : null, null, startAt, endExclusiveAt, limit, offset));
    }

    @GetMapping("/tables/{id}/current-order")
    public ApiResponse<Domain.Order> currentOrder(@RequestHeader("Authorization") String token,
                                                  @PathVariable long id) {
        AuthService.AuthContext user = auth.require(token, Role.WAITER, Role.MERCHANT_ADMIN);
        return ApiResponse.ok(orders.currentOrder(user.requiredMerchantId(), id));
    }

    public record OpenTableRequest(int people) {
    }

    public record TransferRequest(long fromTableId, long toTableId) {
    }

    public record CancelRequest(String reason) {
    }

    public record ServedRequest(long itemId) {
    }
}
