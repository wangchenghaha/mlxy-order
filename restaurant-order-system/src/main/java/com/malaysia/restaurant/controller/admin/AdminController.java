package com.malaysia.restaurant.controller.admin;

import com.malaysia.restaurant.common.enums.*;
import com.malaysia.restaurant.common.result.ApiResponse;
import com.malaysia.restaurant.entity.Domain;
import com.malaysia.restaurant.service.ArchiveService;
import com.malaysia.restaurant.service.AuthService;
import com.malaysia.restaurant.service.I18nService;
import com.malaysia.restaurant.service.InMemoryStore;
import com.malaysia.restaurant.service.OrderService;
import com.malaysia.restaurant.service.PrinterService;
import com.malaysia.restaurant.service.RealtimeEventService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AuthService auth;
    private final AuthService.PasswordHasher hasher;
    private final InMemoryStore store;
    private final OrderService orders;
    private final I18nService i18n;
    private final PrinterService printerService;
    private final ArchiveService archiveService;
    private final RealtimeEventService realtime;

    public AdminController(AuthService auth, AuthService.PasswordHasher hasher, InMemoryStore store,
                           OrderService orders, I18nService i18n, PrinterService printerService,
                           ArchiveService archiveService, RealtimeEventService realtime) {
        this.auth = auth;
        this.hasher = hasher;
        this.store = store;
        this.orders = orders;
        this.i18n = i18n;
        this.printerService = printerService;
        this.archiveService = archiveService;
        this.realtime = realtime;
    }

    @GetMapping("/me")
    public ApiResponse<AdminMe> me(@RequestHeader("Authorization") String token) {
        AuthService.AuthContext user = requireAdmin(token);
        return ApiResponse.ok(new AdminMe(user.user(), user.membership(), membershipViews(user.id()),
                merchantViewsFor(user), storeViewsFor(user), isPlatform(user.role()), routeMenusFor(user.role()),
                permissionsFor(user.role())));
    }

    @PostMapping("/context")
    public ApiResponse<AuthService.LoginResult> switchContext(@RequestHeader("Authorization") String token,
                                                              @RequestBody SwitchContextRequest request) {
        return ApiResponse.ok(auth.switchMembership(token, request.membershipId()));
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(@RequestHeader("Authorization") String token,
                                                      @RequestParam(required = false) Long merchantId,
                                                      @RequestParam(required = false) Long storeId) {
        AuthService.AuthContext user = requireAdmin(token);
        if (!isPlatform(user.role()) && merchantId == null && storeId == null) {
            return ApiResponse.ok(store.stats(user.requiredMerchantId(), user.storeId()));
        }
        Scope scope = readScope(user, merchantId, storeId);
        return ApiResponse.ok(store.stats(scope.merchantId(), scope.storeId()));
    }

    @GetMapping("/merchants")
    public ApiResponse<List<Domain.Merchant>> merchants(@RequestHeader("Authorization") String token) {
        AuthService.AuthContext user = requireAdmin(token);
        return ApiResponse.ok(merchantViewsFor(user).stream()
                .map(m -> store.merchants.get(m.id()))
                .filter(this::notDeleted)
                .toList());
    }

    @PostMapping("/merchants")
    public ApiResponse<Domain.Merchant> saveMerchant(@RequestHeader("Authorization") String token,
                                                     @RequestBody MerchantRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.PLATFORM_ADMIN);
        long id = request.id() == null ? store.nextId() : request.id();
        Domain.Merchant merchant = new Domain.Merchant(id, request.nameZh(), request.nameEn(), request.nameMs(),
                request.phone(), request.address(), emptyTo(request.status(), "ACTIVE"));
        store.saveMerchant(merchant);
        store.log(null, null, user.id(), "SAVE_MERCHANT", merchant.nameZh());
        return ApiResponse.ok(merchant);
    }

    @GetMapping("/stores")
    public ApiResponse<List<Domain.Store>> stores(@RequestHeader("Authorization") String token,
                                                  @RequestParam(required = false) Long merchantId) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = !isPlatform(user.role()) && merchantId == null ? new Scope(null, null) : readScope(user, merchantId, null);
        return ApiResponse.ok(store.stores.values().stream()
                .filter(this::notDeleted)
                .filter(s -> scope.merchantId() == null || s.merchantId() == scope.merchantId())
                .filter(s -> canAccessStore(user, s))
                .sorted(Comparator.comparing(Domain.Store::merchantId).thenComparing(Domain.Store::id))
                .toList());
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleView>> roles(@RequestHeader("Authorization") String token) {
        AuthService.AuthContext user = requireAdmin(token);
        Set<String> availableRoleCodes = availableRoleCodes(user.role());
        return ApiResponse.ok(store.roles.values().stream()
                .filter(Domain.SysRole::active)
                .filter(role -> availableRoleCodes.contains(role.code()))
                .sorted(Comparator.comparing(Domain.SysRole::code))
                .map(this::roleView)
                .toList());
    }

    @PostMapping("/roles")
    public ApiResponse<RoleView> saveRole(@RequestHeader("Authorization") String token,
                                          @RequestBody RoleRequest request) {
        auth.require(token, Role.PLATFORM_ADMIN);
        Domain.SysRole role = new Domain.SysRole(request.code(), request.name(), request.scope(),
                request.description(), emptyTo(request.dataScope(), "STORE"), emptyTo(request.status(), "ACTIVE"));
        store.saveRole(role);
        return ApiResponse.ok(roleView(role));
    }

    @DeleteMapping("/roles/{code}")
    public ApiResponse<Boolean> deleteRole(@RequestHeader("Authorization") String token,
                                           @PathVariable String code) {
        auth.require(token, Role.PLATFORM_ADMIN);
        store.deleteRole(code);
        return ApiResponse.ok(true);
    }

    @PutMapping("/roles/{code}/menus")
    public ApiResponse<RoleView> assignRoleMenus(@RequestHeader("Authorization") String token,
                                                 @PathVariable String code,
                                                 @RequestBody RoleMenuRequest request) {
        auth.require(token, Role.PLATFORM_ADMIN);
        if (!store.roles.containsKey(code)) {
            throw new IllegalArgumentException("角色不存在");
        }
        store.saveRoleMenus(code, request.menuCodes());
        store.saveRolePermissions(code, request.permissions());
        return ApiResponse.ok(roleView(store.roles.get(code)));
    }

    @PutMapping("/roles/{code}/data-scope")
    public ApiResponse<RoleView> assignRoleDataScope(@RequestHeader("Authorization") String token,
                                                     @PathVariable String code,
                                                     @RequestBody DataScopeRequest request) {
        auth.require(token, Role.PLATFORM_ADMIN);
        Domain.SysRole role = store.roles.get(code);
        if (role == null) {
            throw new IllegalArgumentException("角色不存在");
        }
        Domain.SysRole next = new Domain.SysRole(role.code(), role.name(), role.scope(), role.description(),
                emptyTo(request.dataScope(), role.dataScope()), role.status());
        store.saveRole(next);
        return ApiResponse.ok(roleView(next));
    }

    @GetMapping("/menus")
    public ApiResponse<List<MenuView>> menus(@RequestHeader("Authorization") String token) {
        auth.require(token, Role.PLATFORM_ADMIN);
        return ApiResponse.ok(store.menus.values().stream()
                .sorted(Comparator.comparing(Domain.SysMenu::sortNo).thenComparing(Domain.SysMenu::code))
                .map(this::menuView)
                .toList());
    }

    @PostMapping("/menus")
    public ApiResponse<MenuView> saveMenu(@RequestHeader("Authorization") String token,
                                          @RequestBody MenuRequest request) {
        auth.require(token, Role.PLATFORM_ADMIN);
        Domain.SysMenu menu = new Domain.SysMenu(request.code(), request.name(), request.parentCode(),
                request.sortNo(), request.icon(), request.visible(), request.path(), request.component(),
                request.componentName(), request.keepAlive(), request.permission());
        store.saveMenu(menu);
        return ApiResponse.ok(menuView(menu));
    }

    @DeleteMapping("/menus/{code}")
    public ApiResponse<Boolean> deleteMenu(@RequestHeader("Authorization") String token,
                                           @PathVariable String code) {
        auth.require(token, Role.PLATFORM_ADMIN);
        deleteMenuWithChildren(code);
        return ApiResponse.ok(true);
    }

    @GetMapping("/departments")
    public ApiResponse<List<DepartmentView>> departments(@RequestHeader("Authorization") String token) {
        auth.require(token, Role.PLATFORM_ADMIN);
        List<DepartmentView> children = store.merchants.values().stream()
                .sorted(Comparator.comparing(Domain.Merchant::id))
                .filter(this::notDeleted)
                .map(merchant -> new DepartmentView("merchant-" + merchant.id(), merchant.nameZh(), "商户",
                        "platform", merchant.status(), null, store.stores.values().stream()
                        .filter(this::notDeleted)
                        .filter(s -> s.merchantId() == merchant.id())
                        .sorted(Comparator.comparing(Domain.Store::id))
                        .map(s -> new DepartmentView("store-" + s.id(), s.name(), "门店", "merchant-" + merchant.id(),
                                s.status(), store.memberships.values().stream()
                                .filter(Domain.UserMembership::active)
                                .filter(m -> m.storeId() != null && m.storeId() == s.id())
                                .count(), List.<DepartmentView>of()))
                        .toList()))
                .toList();
        return ApiResponse.ok(List.of(new DepartmentView("platform", "平台总部", "平台", null,
                "ACTIVE", store.memberships.values().stream()
                .filter(Domain.UserMembership::active)
                .filter(m -> m.merchantId() == null)
                .count(), children)));
    }

    @PostMapping("/stores")
    public ApiResponse<Domain.Store> saveStore(@RequestHeader("Authorization") String token,
                                               @RequestBody StoreRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.PLATFORM_ADMIN);
        long id = request.id() == null ? store.nextId() : request.id();
        Domain.Merchant merchant = store.merchants.get(request.merchantId());
        if (merchant == null || !notDeleted(merchant)) {
            throw new IllegalArgumentException("商户不存在");
        }
        Domain.Store storeItem = new Domain.Store(id, request.merchantId(), request.code(), request.name(),
                request.phone(), request.address(), emptyTo(request.status(), "ACTIVE"));
        store.saveStore(storeItem);
        store.log(request.merchantId(), id, user.id(), "SAVE_STORE", storeItem.name());
        return ApiResponse.ok(storeItem);
    }

    @GetMapping("/users")
    public ApiResponse<List<UserView>> users(@RequestHeader("Authorization") String token,
                                             @RequestParam(required = false) Long merchantId,
                                             @RequestParam(required = false) Long storeId) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = !isPlatform(user.role()) && merchantId == null && storeId == null
                ? new Scope(null, null)
                : readScope(user, merchantId, storeId);
        return ApiResponse.ok(store.memberships.values().stream()
                .filter(Domain.UserMembership::active)
                .filter(m -> m.merchantId() != null)
                .filter(m -> !isPlatformRole(m.role()))
                .filter(m -> !isReservedPlatformUser(m.userId()))
                .filter(m -> scope.merchantId() == null || (m.merchantId() != null && m.merchantId() == scope.merchantId()))
                .filter(m -> scope.storeId() == null || (m.storeId() != null && m.storeId() == scope.storeId()))
                .filter(m -> canManageMembership(user, m))
                .sorted(Comparator.comparing(Domain.UserMembership::id))
                .map(this::userView)
                .toList());
    }

    @PostMapping("/users")
    public ApiResponse<UserView> createUser(@RequestHeader("Authorization") String token,
                                            @RequestBody UserRequest request) {
        AuthService.AuthContext operator = requireAdmin(token);
        Role role = request.role() == null ? Role.WAITER : request.role();
        Scope scope = requiredMembershipScope(operator, role, request.merchantId(), request.storeId());
        long id = store.nextId();
        String phone = request.phone() == null || request.phone().isBlank() ? "+60" + id : request.phone();
        Domain.User user = new Domain.User(id, phone, request.username(), hasher.hash(request.password()),
                request.displayName(), emptyTo(request.avatarUrl(), defaultAvatar(request.username())), true, null, 0);
        Domain.UserMembership membership = new Domain.UserMembership(store.nextId(), id, scope.merchantId(),
                scope.storeId(), role, "ACTIVE");
        store.saveUser(user);
        store.saveMembership(membership);
        store.log(operator.merchantId(), operator.storeId(), operator.id(), "CREATE_USER", request.username());
        return ApiResponse.ok(userView(membership));
    }

    @PutMapping("/users/{id}")
    public ApiResponse<UserView> updateUser(@RequestHeader("Authorization") String token,
                                            @PathVariable long id,
                                            @RequestBody UserRequest request) {
        AuthService.AuthContext operator = requireAdmin(token);
        Domain.User existing = store.users.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        Domain.UserMembership membership = membershipForUpdate(id, request.membershipId());
        if (!canManageMembership(operator, membership)) {
            throw new IllegalArgumentException("无权限操作该员工");
        }
        Role role = request.role() == null ? membership.role() : request.role();
        Scope scope = requiredMembershipScope(operator, role, request.merchantId(), request.storeId());
        String passwordHash = request.password() == null || request.password().isBlank()
                ? existing.passwordHash()
                : hasher.hash(request.password());
        Domain.User next = new Domain.User(id, emptyTo(request.phone(), existing.phone()),
                emptyTo(request.username(), existing.username()), passwordHash,
                emptyTo(request.displayName(), existing.displayName()), emptyTo(request.avatarUrl(), existing.avatarUrl()),
                existing.enabled(),
                existing.lockedUntil(), existing.failCount());
        Domain.UserMembership nextMembership = new Domain.UserMembership(membership.id(), id, scope.merchantId(),
                scope.storeId(), role, "ACTIVE");
        store.saveUser(next);
        store.saveMembership(nextMembership);
        store.log(operator.merchantId(), operator.storeId(), operator.id(), "UPDATE_USER", next.username());
        return ApiResponse.ok(userView(nextMembership));
    }

    @PutMapping("/users/{id}/password")
    public ApiResponse<UserView> resetPassword(@RequestHeader("Authorization") String token,
                                                  @PathVariable long id,
                                                  @RequestBody PasswordRequest request) {
        AuthService.AuthContext operator = requireAdmin(token);
        Domain.User user = store.users.get(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        Domain.UserMembership membership = membershipForUpdate(id, request.membershipId());
        if (membership.merchantId() == null || isPlatformRole(membership.role())) {
            throw new IllegalArgumentException("员工管理不允许操作平台账号");
        }
        if (!canManageMembership(operator, membership)) {
            throw new IllegalArgumentException("无权限操作该员工");
        }
        readScope(operator, membership.merchantId(), membership.storeId());
        String rawPassword = request.password() == null || request.password().isBlank() ? "Aa123456" : request.password();
        Domain.User next = user.withPasswordHash(hasher.hash(rawPassword));
        store.saveUser(next);
        store.log(membership.merchantId(), membership.storeId(), operator.id(), "RESET_PASSWORD", user.username());
        return ApiResponse.ok(userView(membership));
    }

    @DeleteMapping("/users/{id}")
    public ApiResponse<Boolean> deleteUser(@RequestHeader("Authorization") String token,
                                           @PathVariable long id,
                                           @RequestParam(required = false) Long membershipId) {
        AuthService.AuthContext operator = requireAdmin(token);
        Domain.UserMembership membership = membershipForUpdate(id, membershipId);
        if (!canManageMembership(operator, membership)) {
            throw new IllegalArgumentException("无权限操作该员工");
        }
        store.saveMembership(new Domain.UserMembership(membership.id(), membership.userId(), membership.merchantId(),
                membership.storeId(), membership.role(), "DELETED"));
        boolean hasActiveMembership = store.memberships.values().stream()
                .anyMatch(m -> m.userId() == id && m.active());
        Domain.User user = store.users.get(id);
        if (user != null && !hasActiveMembership) {
            store.saveUser(user.withEnabled(false));
        }
        store.log(operator.merchantId(), operator.storeId(), operator.id(), "DELETE_USER",
                user == null ? String.valueOf(id) : user.username());
        return ApiResponse.ok(true);
    }

    @PutMapping("/users/{id}/enabled")
    public ApiResponse<Domain.User> enabled(@RequestHeader("Authorization") String token,
                                            @PathVariable long id,
                                            @RequestBody EnabledRequest request) {
        requireAdmin(token);
        Domain.User user = store.users.get(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        Domain.User next = user.withEnabled(request.enabled());
        store.saveUser(next);
        return ApiResponse.ok(next);
    }

    @DeleteMapping("/merchants/{id}")
    public ApiResponse<Boolean> deleteMerchant(@RequestHeader("Authorization") String token,
                                               @PathVariable long id) {
        AuthService.AuthContext user = auth.require(token, Role.PLATFORM_ADMIN);
        Domain.Merchant merchant = store.merchants.get(id);
        if (merchant == null) {
            throw new IllegalArgumentException("商户不存在");
        }
        store.saveMerchant(new Domain.Merchant(merchant.id(), merchant.nameZh(), merchant.nameEn(), merchant.nameMs(),
                merchant.phone(), merchant.address(), "DELETED"));
        store.stores.values().stream()
                .filter(s -> s.merchantId() == id)
                .forEach(s -> store.saveStore(new Domain.Store(s.id(), s.merchantId(), s.code(), s.name(),
                        s.phone(), s.address(), "DELETED")));
        store.log(null, null, user.id(), "DELETE_MERCHANT", merchant.nameZh());
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/stores/{id}")
    public ApiResponse<Boolean> deleteStore(@RequestHeader("Authorization") String token,
                                            @PathVariable long id) {
        AuthService.AuthContext user = auth.require(token, Role.PLATFORM_ADMIN);
        Domain.Store storeItem = store.stores.get(id);
        if (storeItem == null) {
            throw new IllegalArgumentException("门店不存在");
        }
        store.saveStore(new Domain.Store(storeItem.id(), storeItem.merchantId(), storeItem.code(), storeItem.name(),
                storeItem.phone(), storeItem.address(), "DELETED"));
        store.log(storeItem.merchantId(), id, user.id(), "DELETE_STORE", storeItem.name());
        return ApiResponse.ok(true);
    }

    @GetMapping("/tables")
    public ApiResponse<List<Domain.DiningTable>> tables(@RequestHeader("Authorization") String token,
                                                        @RequestParam(required = false) Long merchantId,
                                                        @RequestParam(required = false) Long storeId) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = !isPlatform(user.role()) && merchantId == null && storeId == null
                ? new Scope(null, null)
                : readScope(user, merchantId, storeId);
        return ApiResponse.ok(store.queryTables(scope.merchantId(), scope.storeId(), null).stream()
                .filter(t -> scope.merchantId() == null || t.merchantId() == scope.merchantId())
                .filter(t -> scope.storeId() == null || t.storeId() == scope.storeId())
                .filter(t -> canAccess(user, t.merchantId(), t.storeId()))
                .sorted(Comparator.comparing(Domain.DiningTable::merchantId).thenComparing(Domain.DiningTable::storeId)
                        .thenComparing(Domain.DiningTable::tableNo))
                .toList());
    }

    @PostMapping("/tables")
    public ApiResponse<Domain.DiningTable> saveTable(@RequestHeader("Authorization") String token,
                                                     @RequestBody TableRequest request) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = requiredStoreScope(user, request.merchantId(), request.storeId());
        long id = request.id() == null ? store.nextId() : request.id();
        Domain.DiningTable existing = request.id() == null ? null : store.queryTableById(request.id());
        int currentPeople = existing == null ? 0 : existing.currentPeople();
        java.time.LocalDateTime openedAt = existing == null ? null : existing.openedAt();
        Long currentOrderId = existing == null ? null : existing.currentOrderId();
        String reservationName = existing == null ? null : existing.reservationName();
        String reservationPhone = existing == null ? null : existing.reservationPhone();
        String reservationArrivalTime = existing == null ? null : existing.reservationArrivalTime();
        Domain.DiningTable table = new Domain.DiningTable(id, scope.merchantId(), scope.storeId(), request.area(), request.tableNo(),
                request.maxPeople(), request.status() == null ? TableStatus.EMPTY : request.status(), currentPeople, openedAt,
                currentOrderId, reservationName, reservationPhone, reservationArrivalTime);
        store.saveTable(table);
        return ApiResponse.ok(table);
    }

    @DeleteMapping("/tables/{id}")
    public ApiResponse<Boolean> deleteTable(@RequestHeader("Authorization") String token,
                                            @PathVariable long id) {
        AuthService.AuthContext user = requireAdmin(token);
        Domain.DiningTable table = store.queryTableById(id);
        if (table == null) {
            throw new IllegalArgumentException("桌台不存在");
        }
        Scope scope = readScope(user, table.merchantId(), table.storeId());
        if (scope.storeId() == null || scope.storeId() != table.storeId()) {
            throw new IllegalArgumentException("无权限删除该桌台");
        }
        if (table.currentOrderId() != null) {
            throw new IllegalArgumentException("桌台存在关联订单，不能删除");
        }
        store.deleteTable(id);
        return ApiResponse.ok(true);
    }

    @GetMapping("/categories")
    public ApiResponse<List<Domain.DishCategory>> categories(@RequestHeader("Authorization") String token,
                                                            @RequestParam(required = false) Long merchantId,
                                                            @RequestParam(required = false) Long storeId) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = !isPlatform(user.role()) && merchantId == null && storeId == null
                ? new Scope(null, null)
                : readScope(user, merchantId, storeId);
        return ApiResponse.ok(store.queryCategories(scope.merchantId(), scope.storeId()).stream()
                .filter(c -> scope.merchantId() == null || c.merchantId() == scope.merchantId())
                .filter(c -> scope.storeId() == null || c.storeId() == scope.storeId())
                .filter(c -> canAccess(user, c.merchantId(), c.storeId()))
                .sorted(Comparator.comparing(Domain.DishCategory::merchantId).thenComparing(Domain.DishCategory::storeId)
                        .thenComparing(Domain.DishCategory::sortNo))
                .toList());
    }

    @PostMapping("/categories")
    public ApiResponse<Domain.DishCategory> saveCategory(@RequestHeader("Authorization") String token,
                                                         @RequestBody CategoryRequest request) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = requiredStoreScope(user, request.merchantId(), request.storeId());
        long id = request.id() == null ? store.nextId() : request.id();
        Domain.DishCategory category = new Domain.DishCategory(id, scope.merchantId(), scope.storeId(), request.nameZh(), request.nameEn(),
                request.nameMs(), request.sortNo());
        store.saveCategory(category);
        return ApiResponse.ok(category);
    }

    @DeleteMapping("/categories/{id}")
    public ApiResponse<Boolean> deleteCategory(@RequestHeader("Authorization") String token,
                                               @PathVariable long id) {
        AuthService.AuthContext user = requireAdmin(token);
        Domain.DishCategory category = store.queryCategoryById(id);
        if (category == null) {
            throw new IllegalArgumentException("分类不存在");
        }
        readScope(user, category.merchantId(), category.storeId());
        boolean used = store.countCategoryUsage(id) > 0;
        if (used) {
            throw new IllegalArgumentException("分类下存在菜品，不能删除");
        }
        store.deleteCategory(id);
        return ApiResponse.ok(true);
    }

    @GetMapping("/dishes")
    public ApiResponse<List<Domain.Dish>> dishes(@RequestHeader("Authorization") String token,
                                                 @RequestParam(required = false) Long merchantId,
                                                 @RequestParam(required = false) Long storeId,
                                                 @RequestParam(required = false) Boolean enabled) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = !isPlatform(user.role()) && merchantId == null && storeId == null
                ? new Scope(null, null)
                : readScope(user, merchantId, storeId);
        return ApiResponse.ok(store.queryDishes(scope.merchantId(), scope.storeId(), enabled).stream()
                .filter(d -> scope.merchantId() == null || d.merchantId() == scope.merchantId())
                .filter(d -> scope.storeId() == null || d.storeId() == scope.storeId())
                .filter(d -> canAccess(user, d.merchantId(), d.storeId()))
                .sorted(Comparator.comparing(Domain.Dish::merchantId).thenComparing(Domain.Dish::storeId)
                        .thenComparing(Domain.Dish::categoryId).thenComparing(Domain.Dish::id))
                .toList());
    }

    @PostMapping("/dishes")
    public ApiResponse<Domain.Dish> saveDish(@RequestHeader("Authorization") String token,
                                             @RequestBody DishRequest request) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = requiredStoreScope(user, request.merchantId(), request.storeId());
        Domain.DishCategory category = store.queryCategoryById(request.categoryId());
        if (category == null || category.merchantId() != scope.merchantId() || category.storeId() != scope.storeId()) {
            throw new IllegalArgumentException("菜品分类不存在或不属于当前门店");
        }
        long id = request.id() == null ? store.nextId() : request.id();
        Domain.Dish dish = new Domain.Dish(id, scope.merchantId(), scope.storeId(), request.categoryId(), request.nameZh(), request.nameEn(),
                request.nameMs(), request.descriptionZh(), request.descriptionEn(), request.descriptionMs(),
                request.imageUrl(), request.price(), request.spec(), request.stock(), request.enabled());
        store.saveDish(dish);
        return ApiResponse.ok(dish);
    }

    @DeleteMapping("/dishes/{id}")
    public ApiResponse<Boolean> deleteDish(@RequestHeader("Authorization") String token,
                                           @PathVariable long id) {
        AuthService.AuthContext user = requireAdmin(token);
        Domain.Dish dish = store.queryDishById(id);
        if (dish == null) {
            throw new IllegalArgumentException("菜品不存在");
        }
        readScope(user, dish.merchantId(), dish.storeId());
        boolean used = store.countDishUsage(id) > 0;
        if (used) {
            throw new IllegalArgumentException("菜品已被订单使用，不能删除");
        }
        store.deleteDish(id);
        return ApiResponse.ok(true);
    }

    @PutMapping("/dishes/batch-enabled")
    public ApiResponse<List<Domain.Dish>> batchEnabled(@RequestHeader("Authorization") String token,
                                                       @RequestParam(required = false) Long merchantId,
                                                       @RequestParam(required = false) Long storeId,
                                                       @RequestBody BatchEnabledRequest request) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = readScope(user, merchantId, storeId);
        request.ids().forEach(id -> {
            Domain.Dish dish = store.queryDishById(id);
            if (dish != null && (scope.merchantId() == null || dish.merchantId() == scope.merchantId())
                    && (scope.storeId() == null || dish.storeId() == scope.storeId())
                    && canAccess(user, dish.merchantId(), dish.storeId())) {
                store.saveDish(dish.withEnabled(request.enabled()));
            }
        });
        return ApiResponse.ok(store.queryDishes(scope.merchantId(), scope.storeId(), null).stream()
                .filter(d -> scope.merchantId() == null || d.merchantId() == scope.merchantId())
                .filter(d -> scope.storeId() == null || d.storeId() == scope.storeId())
                .filter(d -> canAccess(user, d.merchantId(), d.storeId()))
                .toList());
    }

    @GetMapping("/orders")
    public ApiResponse<List<Domain.Order>> allOrders(@RequestHeader("Authorization") String token,
                                                     @RequestParam(required = false) Long merchantId,
                                                     @RequestParam(required = false) Long storeId,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String tableNo,
                                                     @RequestParam(required = false) Integer limit,
                                                     @RequestParam(required = false) Integer offset) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = !isPlatform(user.role()) && merchantId == null && storeId == null
                ? new Scope(null, null)
                : readScope(user, merchantId, storeId);
        int safeLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 200));
        int safeOffset = Math.max(0, offset == null ? 0 : offset);
        return ApiResponse.ok(store.queryOrders(scope.merchantId(), scope.storeId(), status, null, tableNo, safeLimit, safeOffset).stream()
                .filter(o -> scope.merchantId() == null || o.merchantId() == scope.merchantId())
                .filter(o -> scope.storeId() == null || o.storeId() == scope.storeId())
                .filter(o -> canAccess(user, o.merchantId(), o.storeId()))
                .sorted(Comparator.comparing(Domain.Order::createdAt).reversed())
                .toList());
    }

    @PostMapping("/orders")
    public ApiResponse<Domain.Order> saveOrder(@RequestHeader("Authorization") String token,
                                               @RequestBody OrderRequest request) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = requiredStoreScope(user, request.merchantId(), request.storeId());
        Domain.DiningTable table = store.queryTableById(request.tableId());
        if (table == null || table.merchantId() != scope.merchantId() || table.storeId() != scope.storeId()) {
            throw new IllegalArgumentException("桌台不存在或不属于当前门店");
        }
        Domain.Order existing = request.id() == null ? null : store.queryOrderById(request.id());
        long id = request.id() == null ? store.nextId() : request.id();
        BigDecimal totalAmount = request.totalAmount() == null
                ? (existing == null ? BigDecimal.ZERO : existing.totalAmount())
                : request.totalAmount();
        java.time.LocalDateTime createdAt = existing == null ? java.time.LocalDateTime.now() : existing.createdAt();
        Domain.Order order = new Domain.Order(id, scope.merchantId(), scope.storeId(), table.id(), table.tableNo(),
                existing == null ? Math.max(1, table.currentPeople()) : existing.people(),
                existing == null ? user.id() : existing.waiterId(), existing == null ? user.user().displayName() : existing.waiterName(),
                request.status() == null ? (existing == null ? OrderStatus.DRAFT : existing.status()) : request.status(),
                totalAmount, request.remark(), request.cancelReason(), createdAt, java.time.LocalDateTime.now(),
                existing == null ? List.of() : existing.items());
        store.saveOrder(order);
        if (existing == null && table.currentOrderId() == null) {
            store.saveTable(table.withStatus(TableStatus.DINING, Math.max(1, table.currentPeople()), table.openedAt() == null
                    ? java.time.LocalDateTime.now() : table.openedAt(), order.id()));
            realtime.tableChanged(scope.merchantId(), table.storeId(), table.id(), "ADMIN_ORDER_CREATED");
        } else if (isClosedOrder(order.status()) && table.currentOrderId() != null && table.currentOrderId() == order.id()) {
            store.saveTable(table.withStatus(TableStatus.EMPTY, 0, null, null));
            realtime.tableChanged(scope.merchantId(), table.storeId(), table.id(), "ADMIN_ORDER_CLOSED");
        }
        realtime.orderChanged(scope.merchantId(), order.storeId(), order.id(), "ADMIN_ORDER_SAVED");
        return ApiResponse.ok(order);
    }

    @DeleteMapping("/orders/{id}")
    public ApiResponse<Boolean> deleteOrder(@RequestHeader("Authorization") String token,
                                            @PathVariable long id) {
        AuthService.AuthContext user = requireAdmin(token);
        Domain.Order order = store.queryOrderById(id);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        readScope(user, order.merchantId(), order.storeId());
        store.deleteOrder(id);
        Domain.DiningTable table = store.queryTableById(order.tableId());
        if (table != null && table.currentOrderId() != null && table.currentOrderId() == id) {
            store.saveTable(table.withStatus(TableStatus.EMPTY, 0, null, null));
            realtime.tableChanged(order.merchantId(), table.storeId(), table.id(), "ADMIN_ORDER_DELETED");
        }
        realtime.orderChanged(order.merchantId(), order.storeId(), order.id(), "ADMIN_ORDER_DELETED");
        return ApiResponse.ok(true);
    }

    @GetMapping("/printers")
    public ApiResponse<List<Domain.Printer>> printers(@RequestHeader("Authorization") String token,
                                                      @RequestParam(required = false) Long merchantId,
                                                      @RequestParam(required = false) Long storeId) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = !isPlatform(user.role()) && merchantId == null && storeId == null
                ? new Scope(null, null)
                : readScope(user, merchantId, storeId);
        return ApiResponse.ok(store.queryPrinters(scope.merchantId(), scope.storeId()).stream()
                .filter(p -> scope.merchantId() == null || p.merchantId() == scope.merchantId())
                .filter(p -> scope.storeId() == null || p.storeId() == scope.storeId())
                .filter(p -> canAccess(user, p.merchantId(), p.storeId()))
                .toList());
    }

    @PostMapping("/printers")
    public ApiResponse<Domain.Printer> savePrinter(@RequestHeader("Authorization") String token,
                                                   @RequestBody PrinterRequest request) {
        AuthService.AuthContext user = requireAdmin(token);
        Scope scope = requiredStoreScope(user, request.merchantId(), request.storeId());
        long id = request.id() == null ? store.nextId() : request.id();
        Domain.Printer printer = new Domain.Printer(id, scope.merchantId(), scope.storeId(), request.name(), request.type(), request.ip(),
                request.port(), request.enabled());
        store.savePrinter(printer);
        store.log(scope.merchantId(), scope.storeId(), user.id(), "SAVE_PRINTER", printer.name());
        return ApiResponse.ok(printer);
    }

    @DeleteMapping("/printers/{id}")
    public ApiResponse<Boolean> deletePrinter(@RequestHeader("Authorization") String token,
                                              @PathVariable long id) {
        AuthService.AuthContext user = requireAdmin(token);
        Domain.Printer printer = store.queryPrinterById(id);
        if (printer == null) {
            throw new IllegalArgumentException("打印机不存在");
        }
        readScope(user, printer.merchantId(), printer.storeId());
        store.deletePrinter(id);
        store.log(printer.merchantId(), printer.storeId(), user.id(), "DELETE_PRINTER", printer.name());
        return ApiResponse.ok(true);
    }

    @PostMapping("/printers/{id}/test")
    public ApiResponse<Domain.PrintTask> testPrinter(@RequestHeader("Authorization") String token,
                                                     @PathVariable long id) {
        AuthService.AuthContext user = requireAdmin(token);
        Domain.Printer printer = store.queryPrinterById(id);
        if (printer == null) {
            throw new IllegalArgumentException("打印机不存在");
        }
        readScope(user, printer.merchantId(), printer.storeId());
        Domain.PrintTask task = printerService.testPrint(printer.merchantId(), id);
        store.log(printer.merchantId(), printer.storeId(), user.id(), "TEST_PRINTER", printer.name());
        return ApiResponse.ok(task);
    }

    @GetMapping("/i18n")
    public ApiResponse<List<Domain.SysI18n>> i18n(@RequestHeader("Authorization") String token) {
        auth.require(token, Role.PLATFORM_ADMIN);
        return ApiResponse.ok(store.queryI18n());
    }

    @PostMapping("/i18n")
    public ApiResponse<Domain.SysI18n> saveI18n(@RequestHeader("Authorization") String token,
                                                @RequestBody I18nRequest request) {
        AuthService.AuthContext user = auth.require(token, Role.PLATFORM_ADMIN);
        Domain.SysI18n item = i18n.save(request.key(), request.zhCn(), request.enUs(), request.msMy(), request.remark());
        store.log(null, null, user.id(), "SAVE_I18N", item.key());
        return ApiResponse.ok(item);
    }

    @DeleteMapping("/i18n/{id}")
    public ApiResponse<Boolean> deleteI18n(@RequestHeader("Authorization") String token,
                                           @PathVariable long id) {
        AuthService.AuthContext user = auth.require(token, Role.PLATFORM_ADMIN);
        Domain.SysI18n item = store.i18n.get(id);
        if (item == null) {
            throw new IllegalArgumentException("多语言文案不存在");
        }
        store.deleteI18n(id);
        store.log(null, null, user.id(), "DELETE_I18N", item.key());
        return ApiResponse.ok(true);
    }

    @GetMapping("/logs")
    public ApiResponse<List<Domain.OperationLog>> logs(@RequestHeader("Authorization") String token,
                                                       @RequestParam(required = false) Long merchantId,
                                                       @RequestParam(required = false) Long storeId,
                                                       @RequestParam(required = false) Integer limit,
                                                       @RequestParam(required = false) Integer offset) {
        AuthService.AuthContext user = auth.require(token, Role.PLATFORM_ADMIN);
        Scope scope = readScope(user, merchantId, storeId);
        int safeLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 500));
        int safeOffset = Math.max(0, offset == null ? 0 : offset);
        return ApiResponse.ok(store.queryLogs(scope.merchantId(), scope.storeId(), safeLimit, safeOffset));
    }

    @PostMapping("/archive/run")
    public ApiResponse<ArchiveService.ArchiveResult> runArchive(@RequestHeader("Authorization") String token) {
        auth.require(token, Role.PLATFORM_ADMIN, Role.PLATFORM_SUPER_ADMIN);
        return ApiResponse.ok(archiveService.archiveOnce());
    }

    private AuthService.AuthContext requireAdmin(String token) {
        return auth.require(token, Role.PLATFORM_ADMIN, Role.MERCHANT_OWNER, Role.MERCHANT_ADMIN, Role.STORE_MANAGER);
    }

    private Scope readScope(AuthService.AuthContext user, Long merchantId, Long storeId) {
        if (storeId != null) {
            Domain.Store selectedStore = store.stores.get(storeId);
            if (selectedStore == null) {
                throw new IllegalArgumentException("门店不存在");
            }
            if (merchantId != null && selectedStore.merchantId() != merchantId) {
                throw new IllegalArgumentException("门店不属于所选商户");
            }
            merchantId = selectedStore.merchantId();
        }
        if (isPlatform(user.role())) {
            return new Scope(merchantId, storeId);
        }
        Set<Long> allowedMerchantIds = store.memberships.values().stream()
                .filter(m -> m.userId() == user.id() && m.active() && m.merchantId() != null)
                .map(Domain.UserMembership::merchantId)
                .collect(Collectors.toSet());
        if (allowedMerchantIds.isEmpty()) {
            throw new IllegalArgumentException("当前身份未绑定商户");
        }
        Long baseMerchantId = merchantId == null
                ? (allowedMerchantIds.size() == 1 ? allowedMerchantIds.iterator().next() : user.requiredMerchantId())
                : merchantId;
        if (!allowedMerchantIds.contains(baseMerchantId)) {
            throw new IllegalArgumentException("无权限查看该商户数据");
        }
        List<Domain.UserMembership> scopedMemberships = store.memberships.values().stream()
                .filter(m -> m.userId() == user.id() && m.active() && Objects.equals(m.merchantId(), baseMerchantId))
                .toList();
        boolean merchantWide = scopedMemberships.stream().anyMatch(m -> m.storeId() == null);
        Set<Long> allowedStoreIds = scopedMemberships.stream()
                .map(Domain.UserMembership::storeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!merchantWide && storeId != null && !allowedStoreIds.contains(storeId)) {
            throw new IllegalArgumentException("无权限查看该门店数据");
        }
        Long actualStoreId = !merchantWide && storeId == null && allowedStoreIds.size() == 1
                ? allowedStoreIds.iterator().next()
                : storeId;
        if (actualStoreId != null) {
            Domain.Store selectedStore = store.stores.get(actualStoreId);
            if (selectedStore == null || selectedStore.merchantId() != baseMerchantId) {
                throw new IllegalArgumentException("无权限查看该门店数据");
            }
        }
        return new Scope(baseMerchantId, actualStoreId);
    }

    private Scope requiredStoreScope(AuthService.AuthContext user, Long merchantId, Long storeId) {
        Scope scope = readScope(user, merchantId, storeId);
        if (scope.merchantId() == null) {
            throw new IllegalArgumentException("请选择商户");
        }
        if (scope.storeId() == null) {
            throw new IllegalArgumentException("请选择门店");
        }
        return scope;
    }

    private boolean canAccessStore(AuthService.AuthContext user, Domain.Store storeItem) {
        return storeItem != null && canAccess(user, storeItem.merchantId(), storeItem.id());
    }

    private boolean canManageMembership(AuthService.AuthContext user, Domain.UserMembership membership) {
        if (isPlatform(user.role())) {
            return true;
        }
        if (membership.merchantId() == null) {
            return false;
        }
        return canAccess(user, membership.merchantId(), membership.storeId());
    }

    private boolean canAccess(AuthService.AuthContext user, long merchantId, Long storeId) {
        if (isPlatform(user.role())) {
            return true;
        }
        return store.memberships.values().stream()
                .filter(m -> m.userId() == user.id() && m.active())
                .filter(m -> m.merchantId() != null && m.merchantId() == merchantId)
                .anyMatch(m -> m.storeId() == null || Objects.equals(m.storeId(), storeId));
    }

    private Scope requiredMembershipScope(AuthService.AuthContext user, Role role, Long merchantId, Long storeId) {
        if (isPlatformRole(role)) {
            throw new IllegalArgumentException("员工管理不允许创建平台角色");
        }
        Scope scope = readScope(user, merchantId, storeId);
        if (scope.merchantId() == null) {
            throw new IllegalArgumentException("请选择商户");
        }
        if (!isMerchantRole(role) && scope.storeId() == null) {
            throw new IllegalArgumentException("请选择门店");
        }
        return scope;
    }

    private boolean isMerchantRole(Role role) {
        return role == Role.MERCHANT_OWNER || role == Role.MERCHANT_ADMIN;
    }

    private Set<String> availableRoleCodes(Role role) {
        if (isPlatform(role)) {
            return store.roles.keySet();
        }
        if (role == Role.MERCHANT_OWNER || role == Role.MERCHANT_ADMIN) {
            return Set.of(Role.MERCHANT_ADMIN.name(), Role.STORE_MANAGER.name(), Role.CASHIER.name(),
                    Role.WAITER.name(), Role.KITCHEN.name());
        }
        if (role == Role.STORE_MANAGER) {
            return Set.of(Role.CASHIER.name(), Role.WAITER.name(), Role.KITCHEN.name());
        }
        return Set.of(role.name());
    }

    private Domain.UserMembership membershipForUpdate(long userId, Long membershipId) {
        Domain.UserMembership membership = membershipId == null
                ? store.memberships.values().stream()
                .filter(m -> m.userId() == userId && m.active())
                .filter(m -> m.merchantId() != null && !isPlatformRole(m.role()))
                .findFirst()
                .orElse(null)
                : store.memberships.get(membershipId);
        if (membership == null || membership.userId() != userId || !membership.active()) {
            throw new IllegalArgumentException("用户角色关系不存在");
        }
        if (membership.merchantId() == null || isPlatformRole(membership.role())) {
            throw new IllegalArgumentException("员工管理不允许操作平台账号");
        }
        return membership;
    }

    private List<MembershipView> membershipViews(long userId) {
        return store.memberships.values().stream()
                .filter(m -> m.userId() == userId && m.active())
                .sorted(Comparator.comparing(Domain.UserMembership::id))
                .map(m -> new MembershipView(m.id(), m.merchantId(), merchantName(m.merchantId()),
                        m.storeId(), storeName(m.storeId()), m.role()))
                .toList();
    }

    private RoleView roleView(Domain.SysRole role) {
        return new RoleView(role.code(), role.name(), role.scope(), role.description(), role.dataScope(), role.status(),
                roleMenuCodes(role.code()), permissionsFor(role.code()));
    }

    private MenuView menuView(Domain.SysMenu menu) {
        return new MenuView(menu.code(), menu.name(), menu.parentCode(), menu.sortNo(), menu.icon(), menu.visible(),
                menu.path(), menu.component(), menu.componentName(), menu.keepAlive(), menu.permission());
    }

    private Set<String> roleMenuCodes(String roleCode) {
        return new LinkedHashSet<>(store.roleMenus.getOrDefault(roleCode, Set.of()));
    }

    private List<String> permissionsFor(Role role) {
        return permissionsFor(role.name());
    }

    private List<String> permissionsFor(String roleCode) {
        if (Role.PLATFORM_SUPER_ADMIN.name().equals(roleCode)) {
            return List.of("*:*:*");
        }
        return new ArrayList<>(store.rolePermissions.getOrDefault(roleCode, Set.of()));
    }

    private List<MenuRouteView> routeMenusFor(Role role) {
        Set<String> allowed = Role.PLATFORM_SUPER_ADMIN == role
                ? store.menus.values().stream().map(Domain.SysMenu::code).collect(Collectors.toCollection(LinkedHashSet::new))
                : roleMenuCodes(role.name());
        Set<String> withParents = new LinkedHashSet<>(allowed);
        for (String code : allowed) {
            Domain.SysMenu menu = store.menus.get(code);
            while (menu != null && menu.parentCode() != null) {
                withParents.add(menu.parentCode());
                menu = store.menus.get(menu.parentCode());
            }
        }
        return buildMenuRoutes(null, withParents);
    }

    private List<MenuRouteView> buildMenuRoutes(String parentCode, Set<String> allowedCodes) {
        return store.menus.values().stream()
                .filter(Domain.SysMenu::visible)
                .filter(menu -> allowedCodes.contains(menu.code()))
                .filter(menu -> parentCode == null ? menu.parentCode() == null : parentCode.equals(menu.parentCode()))
                .sorted(Comparator.comparing(Domain.SysMenu::sortNo).thenComparing(Domain.SysMenu::code))
                .map(menu -> new MenuRouteView(menuId(menu.code()), parentCode == null ? 0 : menuId(parentCode),
                        menu.path(), menu.name(), menu.component(), menu.componentName(), menu.icon(), true,
                        menu.keepAlive(), buildMenuRoutes(menu.code(), allowedCodes)))
                .toList();
    }

    private long menuId(String code) {
        return Integer.toUnsignedLong(code.hashCode());
    }

    private void deleteMenuWithChildren(String code) {
        store.menus.values().stream()
                .filter(menu -> code.equals(menu.parentCode()))
                .map(Domain.SysMenu::code)
                .toList()
                .forEach(this::deleteMenuWithChildren);
        store.deleteMenu(code);
    }

    private List<MerchantView> merchantViewsFor(AuthService.AuthContext user) {
        if (isPlatform(user.role())) {
            return store.merchants.values().stream()
                    .filter(this::notDeleted)
                    .sorted(Comparator.comparing(Domain.Merchant::id))
                    .map(this::merchantView)
                    .toList();
        }
        return store.memberships.values().stream()
                .filter(m -> m.userId() == user.id() && m.active() && m.merchantId() != null)
                .map(Domain.UserMembership::merchantId)
                .distinct()
                .sorted()
                .map(store.merchants::get)
                .filter(this::notDeleted)
                .map(this::merchantView)
                .toList();
    }

    private List<StoreView> storeViewsFor(AuthService.AuthContext user) {
        if (isPlatform(user.role())) {
            return store.stores.values().stream()
                    .filter(this::notDeleted)
                    .sorted(Comparator.comparing(Domain.Store::merchantId).thenComparing(Domain.Store::id))
                    .map(this::storeView)
                    .toList();
        }
        return store.stores.values().stream()
                .filter(this::notDeleted)
                .filter(s -> canAccessStore(user, s))
                .sorted(Comparator.comparing(Domain.Store::merchantId).thenComparing(Domain.Store::id))
                .map(this::storeView)
                .toList();
    }

    private UserView userView(Domain.UserMembership membership) {
        Domain.User user = store.users.get(membership.userId());
        return new UserView(user.id(), user.phone(), user.username(), user.displayName(), user.avatarUrl(), user.enabled(),
                membership.role(), membership.merchantId(), merchantName(membership.merchantId()),
                membership.storeId(), storeName(membership.storeId()), membership.id());
    }

    private MerchantView merchantView(Domain.Merchant merchant) {
        return new MerchantView(merchant.id(), merchant.nameZh(), merchant.nameEn(), merchant.nameMs(), merchant.phone(),
                merchant.address(), merchant.status());
    }

    private StoreView storeView(Domain.Store storeItem) {
        return new StoreView(storeItem.id(), storeItem.merchantId(), merchantName(storeItem.merchantId()), storeItem.code(),
                storeItem.name(), storeItem.phone(), storeItem.address(), storeItem.status());
    }

    private String merchantName(Long merchantId) {
        Domain.Merchant merchant = merchantId == null ? null : store.merchants.get(merchantId);
        return merchant == null ? null : merchant.nameZh();
    }

    private String storeName(Long storeId) {
        Domain.Store storeItem = storeId == null ? null : store.stores.get(storeId);
        return storeItem == null ? null : storeItem.name();
    }

    private boolean isPlatform(Role role) {
        return role == Role.PLATFORM_ADMIN || role == Role.PLATFORM_SUPER_ADMIN;
    }

    private boolean isPlatformRole(Role role) {
        return role == Role.PLATFORM_ADMIN || role == Role.PLATFORM_SUPER_ADMIN;
    }

    private boolean isReservedPlatformUser(long userId) {
        Domain.User user = store.users.get(userId);
        if (user == null || user.username() == null) {
            return false;
        }
        String username = user.username().toLowerCase(java.util.Locale.ROOT);
        return "admin".equals(username) || "platform".equals(username);
    }

    private boolean notDeleted(Domain.Merchant merchant) {
        return merchant != null && !"DELETED".equalsIgnoreCase(merchant.status());
    }

    private boolean notDeleted(Domain.Store storeItem) {
        return storeItem != null && !"DELETED".equalsIgnoreCase(storeItem.status());
    }

    private boolean isClosedOrder(OrderStatus status) {
        return status == OrderStatus.PAID || status == OrderStatus.CANCELLED;
    }

    private String emptyTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String defaultAvatar(String username) {
        return "/assets/avatar/" + (username == null || username.isBlank() ? "user" : username) + ".png";
    }

    public record AdminMe(Domain.User user, Domain.UserMembership currentMembership, List<MembershipView> memberships,
                          List<MerchantView> merchants, List<StoreView> stores, boolean platform,
                          List<MenuRouteView> menus, List<String> permissions) {
    }

    public record MembershipView(long id, Long merchantId, String merchantName, Long storeId, String storeName,
                                 Role role) {
    }

    public record MerchantView(long id, String nameZh, String nameEn, String nameMs, String phone, String address,
                               String status) {
    }

    public record StoreView(long id, long merchantId, String merchantName, String code, String name, String phone,
                            String address, String status) {
    }

    public record RoleView(String code, String name, String scope, String description, String dataScope, String status,
                           Set<String> menuCodes, List<String> permissions) {
    }

    public record MenuView(String code, String name, String parentCode, int sortNo, String icon, boolean visible,
                           String path, String component, String componentName, boolean keepAlive, String permission) {
    }

    public record MenuRouteView(long id, long parentId, String path, String name, String component,
                                String componentName, String icon, boolean visible, boolean keepAlive,
                                List<MenuRouteView> children) {
    }

    public record DepartmentView(String id, String name, String typeName, String parentId, String status,
                                 Long userCount, List<DepartmentView> children) {
    }

    public record UserView(long id, String phone, String username, String displayName, String avatarUrl, boolean enabled, Role role,
                           Long merchantId, String merchantName, Long storeId, String storeName, long membershipId) {
    }

    public record Scope(Long merchantId, Long storeId) {
    }

    public record SwitchContextRequest(long membershipId) {
    }

    public record MerchantRequest(Long id, String nameZh, String nameEn, String nameMs, String phone, String address,
                                  String status) {
    }

    public record StoreRequest(Long id, long merchantId, String code, String name, String phone, String address,
                               String status) {
    }

    public record UserRequest(Long membershipId, String phone, String username, String password, String displayName,
                              String avatarUrl, Role role, Long merchantId, Long storeId) {
    }

    public record RoleRequest(String code, String name, String scope, String description, String dataScope, String status) {
    }

    public record RoleMenuRequest(List<String> menuCodes, List<String> permissions) {
    }

    public record DataScopeRequest(String dataScope) {
    }

    public record MenuRequest(String code, String name, String parentCode, int sortNo, String icon, boolean visible,
                              String path, String component, String componentName, boolean keepAlive, String permission) {
    }

    public record EnabledRequest(boolean enabled) {
    }

    public record PasswordRequest(String password, Long membershipId) {
    }

    public record TableRequest(Long id, Long merchantId, Long storeId, String area, String tableNo, int maxPeople,
                               TableStatus status) {
    }

    public record CategoryRequest(Long id, Long merchantId, Long storeId, String nameZh, String nameEn, String nameMs,
                                  int sortNo) {
    }

    public record DishRequest(Long id, Long merchantId, Long storeId, long categoryId, String nameZh, String nameEn,
                              String nameMs, String descriptionZh, String descriptionEn, String descriptionMs,
                              String imageUrl, BigDecimal price, String spec, int stock, boolean enabled) {
    }

    public record OrderRequest(Long id, Long merchantId, Long storeId, long tableId, OrderStatus status,
                               BigDecimal totalAmount, String remark, String cancelReason) {
    }

    public record BatchEnabledRequest(List<Long> ids, boolean enabled) {
    }

    public record PrinterRequest(Long id, Long merchantId, Long storeId, String name, PrinterType type,
                                 String ip, int port, boolean enabled) {
    }

    public record I18nRequest(String key, String zhCn, String enUs, String msMy, String remark) {
    }
}
