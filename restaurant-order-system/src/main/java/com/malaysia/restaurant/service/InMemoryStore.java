package com.malaysia.restaurant.service;

import com.malaysia.restaurant.common.enums.*;
import com.malaysia.restaurant.entity.Domain;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class InMemoryStore {
    private final AtomicLong ids = new AtomicLong(1000);
    private final JdbcTemplate jdbc;
    private final ResourceLoader resourceLoader;
    private final AuthService.PasswordHasher hasher;

    /*
     * This class is a persistence-backed cache facade.
     *
     * Controllers/services can still read fast in-memory maps, while every saveXxx method writes the same
     * change to MySQL. On restart, reload() rebuilds all maps from MySQL, so order/payment/print data survives.
    */
    public final Map<Long, Domain.Merchant> merchants = new ConcurrentHashMap<>();
    public final Map<Long, Domain.Store> stores = new ConcurrentHashMap<>();
    public final Map<Long, Domain.User> users = new ConcurrentHashMap<>();
    public final Map<Long, Domain.UserMembership> memberships = new ConcurrentHashMap<>();
    public final Map<String, Domain.SysRole> roles = new ConcurrentHashMap<>();
    public final Map<String, Domain.SysMenu> menus = new ConcurrentHashMap<>();
    public final Map<String, Set<String>> roleMenus = new ConcurrentHashMap<>();
    public final Map<String, Set<String>> rolePermissions = new ConcurrentHashMap<>();
    public final Map<Long, Domain.DiningTable> tables = new ConcurrentHashMap<>();
    public final Map<Long, Domain.DishCategory> categories = new ConcurrentHashMap<>();
    public final Map<Long, Domain.Dish> dishes = new ConcurrentHashMap<>();
    public final Map<Long, Domain.Order> orders = new ConcurrentHashMap<>();
    public final Map<Long, Domain.Payment> payments = new ConcurrentHashMap<>();
    public final Map<Long, Domain.Printer> printers = new ConcurrentHashMap<>();
    public final Map<Long, Domain.PrintTask> printTasks = new ConcurrentHashMap<>();
    public final Map<Long, Domain.SysI18n> i18n = new ConcurrentHashMap<>();
    public final List<Domain.OperationLog> logs = Collections.synchronizedList(new ArrayList<>());

    public InMemoryStore(JdbcTemplate jdbc, ResourceLoader resourceLoader, AuthService.PasswordHasher hasher) {
        this.jdbc = jdbc;
        this.resourceLoader = resourceLoader;
        this.hasher = hasher;
    }

    @PostConstruct
    public void init() {
        // Idempotent startup: create/patch schema, load seed data, then hydrate the in-memory read model.
        executeSqlResource("classpath:db/schema.sql");
        patchExistingSchema();
        executeSqlResource("classpath:db/seed.sql");
        seedUsersIfMissing();
        seedSystemPermissionsIfMissing();
        reload();
    }

    public long nextId() {
        return ids.incrementAndGet();
    }

    public synchronized void reload() {
        // Rebuild the read model from MySQL. Keep this synchronized so no request observes half-loaded data.
        merchants.clear();
        stores.clear();
        users.clear();
        memberships.clear();
        roles.clear();
        menus.clear();
        roleMenus.clear();
        rolePermissions.clear();
        tables.clear();
        categories.clear();
        dishes.clear();
        orders.clear();
        payments.clear();
        printers.clear();
        printTasks.clear();
        i18n.clear();
        logs.clear();

        jdbc.query("select * from merchant", merchantMapper()).forEach(m -> merchants.put(m.id(), m));
        jdbc.query("select * from merchant_store", storeMapper()).forEach(s -> stores.put(s.id(), s));
        jdbc.query("select * from sys_user", userMapper()).forEach(u -> users.put(u.id(), u));
        jdbc.query("select * from user_membership", membershipMapper()).forEach(m -> memberships.put(m.id(), m));
        jdbc.query("select * from sys_role", roleMapper()).forEach(r -> roles.put(r.code(), r));
        jdbc.query("select * from sys_menu", menuMapper()).forEach(m -> menus.put(m.code(), m));
        jdbc.query("select role_code, menu_code from sys_role_menu", (RowCallbackHandler) rs ->
                roleMenus.computeIfAbsent(rs.getString("role_code"), ignored -> ConcurrentHashMap.newKeySet())
                        .add(rs.getString("menu_code")));
        jdbc.query("select role_code, permission from sys_role_permission", (RowCallbackHandler) rs ->
                rolePermissions.computeIfAbsent(rs.getString("role_code"), ignored -> ConcurrentHashMap.newKeySet())
                        .add(rs.getString("permission")));
        jdbc.query("select * from dining_table", tableMapper()).forEach(t -> tables.put(t.id(), t));
        jdbc.query("select * from dish_category", categoryMapper()).forEach(c -> categories.put(c.id(), c));
        jdbc.query("select * from dish", dishMapper()).forEach(d -> dishes.put(d.id(), d));
        jdbc.query("select * from printer", printerMapper()).forEach(p -> printers.put(p.id(), p));
        jdbc.query("select * from payment order by paid_at desc limit 5000", paymentMapper()).forEach(p -> payments.put(p.id(), p));
        jdbc.query("select * from print_task where status in ('WAITING', 'SENDING', 'FAILED') or updated_at >= date_sub(now(), interval 7 day)",
                printTaskMapper()).forEach(t -> printTasks.put(t.id(), t));
        jdbc.query("select * from sys_i18n", i18nMapper()).forEach(item -> i18n.put(item.id(), item));
        logs.addAll(jdbc.query("select * from operation_log order by created_at desc limit 5000", logMapper()));

        // Orders are split into header/detail tables in MySQL but exposed as one aggregate to the API.
        Map<Long, List<Domain.OrderItem>> itemsByOrder = new HashMap<>();
        jdbc.query("""
                        select oi.*
                        from order_item oi
                        join order_main o on o.id = oi.order_id
                        where o.status not in ('PAID', 'CANCELLED') or o.updated_at >= date_sub(now(), interval 7 day)
                        """, orderItemMapper())
                .forEach(item -> itemsByOrder.computeIfAbsent(item.orderId(), ignored -> new ArrayList<>()).add(item));
        jdbc.query("""
                        select *
                        from order_main
                        where status not in ('PAID', 'CANCELLED') or updated_at >= date_sub(now(), interval 7 day)
                        """, orderMapper(itemsByOrder))
                .forEach(o -> orders.put(o.id(), o));

        // IDs are application-generated for this demo; bump the counter beyond the current database max.
        long maxLogId = logs.stream()
                .mapToLong(Domain.OperationLog::id)
                .max()
                .orElse(1000L);
        long maxDbId = maxTableId("merchant", "id");
        maxDbId = Math.max(maxDbId, maxTableId("merchant_store", "id"));
        maxDbId = Math.max(maxDbId, maxTableId("sys_user", "id"));
        maxDbId = Math.max(maxDbId, maxTableId("user_membership", "id"));
        maxDbId = Math.max(maxDbId, maxTableId("dining_table", "id"));
        maxDbId = Math.max(maxDbId, maxTableId("dish_category", "id"));
        maxDbId = Math.max(maxDbId, maxTableId("dish", "id"));
        maxDbId = Math.max(maxDbId, maxTableId("order_main", "id"));
        maxDbId = Math.max(maxDbId, maxTableId("payment", "id"));
        maxDbId = Math.max(maxDbId, maxTableId("printer", "id"));
        maxDbId = Math.max(maxDbId, maxTableId("print_task", "id"));
        maxDbId = Math.max(maxDbId, maxTableId("operation_log", "id"));
        maxDbId = Math.max(maxDbId, maxTableId("sys_i18n", "id"));
        ids.set(Math.max(Math.max(1000L, maxLogId), maxDbId));
    }

    public void saveUser(Domain.User user) {
        // Update cache first so the current request sees its own write, then upsert the durable row.
        users.put(user.id(), user);
        jdbc.update("""
                        insert into sys_user (id, phone, username, password_hash, display_name, avatar_url, enabled, locked_until, fail_count)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update phone = values(phone), username = values(username),
                        password_hash = values(password_hash), display_name = values(display_name), avatar_url = values(avatar_url),
                        enabled = values(enabled), locked_until = values(locked_until), fail_count = values(fail_count)
                        """,
                user.id(), user.phone(), user.username(), user.passwordHash(), user.displayName(),
                user.avatarUrl(), user.enabled() ? 1 : 0, ts(user.lockedUntil()), user.failCount());
    }

    public void saveMerchant(Domain.Merchant merchant) {
        merchants.put(merchant.id(), merchant);
        jdbc.update("""
                        insert into merchant (id, name_zh, name_en, name_ms, phone, address, status)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update name_zh = values(name_zh), name_en = values(name_en),
                        name_ms = values(name_ms), phone = values(phone), address = values(address),
                        status = values(status)
                        """,
                merchant.id(), merchant.nameZh(), merchant.nameEn(), merchant.nameMs(), merchant.phone(),
                merchant.address(), merchant.status());
    }

    public void saveStore(Domain.Store storeItem) {
        stores.put(storeItem.id(), storeItem);
        jdbc.update("""
                        insert into merchant_store (id, merchant_id, code, name, phone, address, status)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update code = values(code), name = values(name), phone = values(phone),
                        address = values(address), status = values(status)
                        """,
                storeItem.id(), storeItem.merchantId(), storeItem.code(), storeItem.name(), storeItem.phone(),
                storeItem.address(), storeItem.status());
    }

    public void saveMembership(Domain.UserMembership membership) {
        memberships.put(membership.id(), membership);
        jdbc.update("""
                        insert into user_membership (id, user_id, merchant_id, store_id, role, status)
                        values (?, ?, ?, ?, ?, ?)
                        on duplicate key update merchant_id = values(merchant_id), store_id = values(store_id),
                        role = values(role), status = values(status)
                        """,
                membership.id(), membership.userId(), membership.merchantId(), membership.storeId(),
                membership.role().name(), membership.status());
    }

    public void saveRole(Domain.SysRole role) {
        roles.put(role.code(), role);
        jdbc.update("""
                        insert into sys_role (code, name, scope, description, data_scope, status)
                        values (?, ?, ?, ?, ?, ?)
                        on duplicate key update name = values(name), scope = values(scope),
                        description = values(description), data_scope = values(data_scope), status = values(status)
                        """,
                role.code(), role.name(), role.scope(), role.description(), role.dataScope(), role.status());
    }

    public void deleteRole(String code) {
        Domain.SysRole role = roles.get(code);
        if (role == null) return;
        saveRole(new Domain.SysRole(role.code(), role.name(), role.scope(), role.description(), role.dataScope(), "DELETED"));
    }

    public void saveMenu(Domain.SysMenu menu) {
        menus.put(menu.code(), menu);
        jdbc.update("""
                        insert into sys_menu (code, name, parent_code, sort_no, icon, visible, path, component,
                        component_name, keep_alive, permission)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update name = values(name), parent_code = values(parent_code),
                        sort_no = values(sort_no), icon = values(icon), visible = values(visible),
                        path = values(path), component = values(component), component_name = values(component_name),
                        keep_alive = values(keep_alive), permission = values(permission)
                        """,
                menu.code(), menu.name(), menu.parentCode(), menu.sortNo(), menu.icon(), menu.visible() ? 1 : 0,
                menu.path(), menu.component(), menu.componentName(), menu.keepAlive() ? 1 : 0, menu.permission());
    }

    public void deleteMenu(String code) {
        Domain.SysMenu menu = menus.get(code);
        if (menu == null) return;
        saveMenu(new Domain.SysMenu(menu.code(), menu.name(), menu.parentCode(), menu.sortNo(), menu.icon(), false,
                menu.path(), menu.component(), menu.componentName(), menu.keepAlive(), menu.permission()));
        jdbc.update("delete from sys_role_menu where menu_code = ?", code);
        roleMenus.values().forEach(set -> set.remove(code));
    }

    public void saveRoleMenus(String roleCode, Collection<String> menuCodes) {
        Set<String> next = new LinkedHashSet<>(menuCodes == null ? List.of() : menuCodes);
        roleMenus.put(roleCode, ConcurrentHashMap.newKeySet());
        roleMenus.get(roleCode).addAll(next);
        jdbc.update("delete from sys_role_menu where role_code = ?", roleCode);
        for (String menuCode : next) {
            jdbc.update("insert ignore into sys_role_menu (role_code, menu_code) values (?, ?)", roleCode, menuCode);
        }
    }

    public void saveRolePermissions(String roleCode, Collection<String> permissions) {
        Set<String> next = new LinkedHashSet<>(permissions == null ? List.of() : permissions);
        rolePermissions.put(roleCode, ConcurrentHashMap.newKeySet());
        rolePermissions.get(roleCode).addAll(next);
        jdbc.update("delete from sys_role_permission where role_code = ?", roleCode);
        for (String permission : next) {
            jdbc.update("insert ignore into sys_role_permission (role_code, permission) values (?, ?)", roleCode, permission);
        }
    }

    public void saveTable(Domain.DiningTable table) {
        tables.put(table.id(), table);
        jdbc.update("""
                        insert into dining_table (id, merchant_id, store_id, area, table_no, max_people, status, current_people, opened_at, current_order_id, reservation_name, reservation_phone, reservation_arrival_time)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update store_id = values(store_id), area = values(area), table_no = values(table_no), max_people = values(max_people),
                        status = values(status), current_people = values(current_people), opened_at = values(opened_at),
                        current_order_id = values(current_order_id), reservation_name = values(reservation_name),
                        reservation_phone = values(reservation_phone), reservation_arrival_time = values(reservation_arrival_time)
                        """,
                table.id(), table.merchantId(), table.storeId(), table.area(), table.tableNo(), table.maxPeople(), table.status().name(),
                table.currentPeople(), ts(table.openedAt()), table.currentOrderId(), table.reservationName(), table.reservationPhone(),
                table.reservationArrivalTime());
    }

    public void deleteTable(long id) {
        tables.remove(id);
        jdbc.update("delete from dining_table where id = ?", id);
    }

    public void saveCategory(Domain.DishCategory category) {
        categories.put(category.id(), category);
        jdbc.update("""
                        insert into dish_category (id, merchant_id, store_id, name_zh, name_en, name_ms, sort_no)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update store_id = values(store_id), name_zh = values(name_zh), name_en = values(name_en),
                        name_ms = values(name_ms), sort_no = values(sort_no)
                        """,
                category.id(), category.merchantId(), category.storeId(), category.nameZh(), category.nameEn(),
                category.nameMs(), category.sortNo());
    }

    public void deleteCategory(long id) {
        categories.remove(id);
        jdbc.update("delete from dish_category where id = ?", id);
    }

    public void saveDish(Domain.Dish dish) {
        dishes.put(dish.id(), dish);
        jdbc.update("""
                        insert into dish (id, merchant_id, store_id, category_id, name_zh, name_en, name_ms, description_zh, description_en,
                        description_ms, image_url, price, spec, stock, enabled)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update store_id = values(store_id), category_id = values(category_id), name_zh = values(name_zh),
                        name_en = values(name_en), name_ms = values(name_ms), description_zh = values(description_zh),
                        description_en = values(description_en), description_ms = values(description_ms),
                        image_url = values(image_url), price = values(price), spec = values(spec), stock = values(stock),
                        enabled = values(enabled)
                        """,
                dish.id(), dish.merchantId(), dish.storeId(), dish.categoryId(), dish.nameZh(), dish.nameEn(), dish.nameMs(),
                dish.descriptionZh(), dish.descriptionEn(), dish.descriptionMs(), dish.imageUrl(), dish.price(),
                dish.spec(), dish.stock(), dish.enabled() ? 1 : 0);
    }

    public void deleteDish(long id) {
        dishes.remove(id);
        jdbc.update("delete from dish where id = ?", id);
    }

    public void saveOrder(Domain.Order order) {
        orders.put(order.id(), order);
        jdbc.update("""
                        insert into order_main (id, merchant_id, store_id, table_id, table_no, people, waiter_id, waiter_name, status, total_amount,
                        remark, cancel_reason, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update store_id = values(store_id), table_id = values(table_id), table_no = values(table_no), waiter_id = values(waiter_id),
                        people = values(people), waiter_name = values(waiter_name), status = values(status), total_amount = values(total_amount),
                        remark = values(remark), cancel_reason = values(cancel_reason), updated_at = values(updated_at)
                        """,
                order.id(), order.merchantId(), order.storeId(), order.tableId(), order.tableNo(), order.people(), order.waiterId(), order.waiterName(),
                order.status().name(), order.totalAmount(), order.remark(), order.cancelReason(), ts(order.createdAt()),
                ts(order.updatedAt()));
        // Replacing details keeps add/return/edit order flows simple and avoids stale dish lines.
        jdbc.update("delete from order_item where order_id = ?", order.id());
        for (Domain.OrderItem item : order.items()) {
            saveOrderItem(item);
        }
    }

    public void deleteOrder(long id) {
        orders.remove(id);
        jdbc.update("delete from order_item where order_id = ?", id);
        jdbc.update("delete from order_main where id = ?", id);
    }

    public void savePayment(Domain.Payment payment) {
        payments.put(payment.id(), payment);
        jdbc.update("""
                        insert into payment (id, merchant_id, store_id, order_id, method, amount, reference_no, paid_at, cashier_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update store_id = values(store_id), method = values(method), amount = values(amount),
                        reference_no = values(reference_no), paid_at = values(paid_at), cashier_id = values(cashier_id)
                        """,
                payment.id(), payment.merchantId(), payment.storeId(), payment.orderId(), payment.method().name(), payment.amount(),
                payment.referenceNo(), ts(payment.paidAt()), payment.cashierId());
    }

    public void savePrinter(Domain.Printer printer) {
        printers.put(printer.id(), printer);
        jdbc.update("""
                        insert into printer (id, merchant_id, store_id, name, type, ip, port, enabled)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update store_id = values(store_id), name = values(name), type = values(type), ip = values(ip),
                        port = values(port), enabled = values(enabled)
                        """,
                printer.id(), printer.merchantId(), printer.storeId(), printer.name(), printer.type().name(), printer.ip(), printer.port(),
                printer.enabled() ? 1 : 0);
    }

    public void deletePrinter(long id) {
        printers.remove(id);
        jdbc.update("delete from printer where id = ?", id);
    }

    public void savePrintTask(Domain.PrintTask task) {
        printTasks.put(task.id(), task);
        jdbc.update("""
                        insert into print_task (id, merchant_id, store_id, order_id, printer_id, scene, content, status, retry_count,
                        last_error, next_retry_at, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update store_id = values(store_id), content = values(content), status = values(status),
                        retry_count = values(retry_count), last_error = values(last_error), next_retry_at = values(next_retry_at),
                        updated_at = values(updated_at)
                        """,
                task.id(), task.merchantId(), task.storeId(), task.orderId(), task.printerId(), task.scene().name(), task.content(),
                task.status().name(), task.retryCount(), task.lastError(), ts(task.nextRetryAt()), ts(task.createdAt()), ts(task.updatedAt()));
    }

    public List<Domain.DiningTable> queryTables(Long merchantId, Long storeId, String status) {
        StringBuilder sql = new StringBuilder("select * from dining_table where 1=1");
        List<Object> args = new ArrayList<>();
        if (merchantId != null) {
            sql.append(" and merchant_id = ?");
            args.add(merchantId);
        }
        if (storeId != null) {
            sql.append(" and store_id = ?");
            args.add(storeId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and status = ?");
            args.add(status);
        }
        sql.append(" order by merchant_id, store_id, table_no");
        return jdbc.query(sql.toString(), tableMapper(), args.toArray());
    }

    public Domain.DiningTable queryTableById(long tableId) {
        List<Domain.DiningTable> tables = jdbc.query("select * from dining_table where id = ?", tableMapper(), tableId);
        return tables.isEmpty() ? null : tables.get(0);
    }

    public Domain.DishCategory queryCategoryById(long categoryId) {
        List<Domain.DishCategory> categories = jdbc.query("select * from dish_category where id = ?", categoryMapper(), categoryId);
        return categories.isEmpty() ? null : categories.get(0);
    }

    public List<Domain.DishCategory> queryCategories(Long merchantId, Long storeId) {
        StringBuilder sql = new StringBuilder("select * from dish_category where 1=1");
        List<Object> args = new ArrayList<>();
        if (merchantId != null) {
            sql.append(" and merchant_id = ?");
            args.add(merchantId);
        }
        if (storeId != null) {
            sql.append(" and store_id = ?");
            args.add(storeId);
        }
        sql.append(" order by sort_no, id");
        return jdbc.query(sql.toString(), categoryMapper(), args.toArray());
    }

    public List<Domain.Dish> queryDishes(Long merchantId, Long storeId, Boolean enabled) {
        StringBuilder sql = new StringBuilder("select * from dish where 1=1");
        List<Object> args = new ArrayList<>();
        if (merchantId != null) {
            sql.append(" and merchant_id = ?");
            args.add(merchantId);
        }
        if (storeId != null) {
            sql.append(" and store_id = ?");
            args.add(storeId);
        }
        if (enabled != null) {
            sql.append(" and enabled = ?");
            args.add(enabled ? 1 : 0);
        }
        sql.append(" order by category_id, id");
        return jdbc.query(sql.toString(), dishMapper(), args.toArray());
    }

    public Domain.Dish queryDishById(long id) {
        List<Domain.Dish> dishes = jdbc.query("select * from dish where id = ?", dishMapper(), id);
        return dishes.isEmpty() ? null : dishes.get(0);
    }

    public Domain.Printer queryPrinterById(long id) {
        List<Domain.Printer> printers = jdbc.query("select * from printer where id = ?", printerMapper(), id);
        return printers.isEmpty() ? null : printers.get(0);
    }

    public List<Domain.Printer> queryPrinters(Long merchantId, Long storeId) {
        StringBuilder sql = new StringBuilder("select * from printer where 1=1");
        List<Object> args = new ArrayList<>();
        if (merchantId != null) {
            sql.append(" and merchant_id = ?");
            args.add(merchantId);
        }
        if (storeId != null) {
            sql.append(" and store_id = ?");
            args.add(storeId);
        }
        sql.append(" order by type, id");
        return jdbc.query(sql.toString(), printerMapper(), args.toArray());
    }

    public List<Domain.SysI18n> queryI18n() {
        return jdbc.query("select * from sys_i18n order by i18n_key", i18nMapper());
    }

    public List<Domain.OperationLog> queryLogs(Long merchantId, Long storeId, Integer limit, Integer offset) {
        StringBuilder sql = new StringBuilder("select * from operation_log where 1=1");
        List<Object> args = new ArrayList<>();
        if (merchantId != null) {
            sql.append(" and merchant_id = ?");
            args.add(merchantId);
        }
        if (storeId != null) {
            sql.append(" and store_id = ?");
            args.add(storeId);
        }
        sql.append(" order by created_at desc");
        if (limit != null) {
            sql.append(" limit ?");
            args.add(limit);
            sql.append(" offset ?");
            args.add(offset == null ? 0 : offset);
        }
        return jdbc.query(sql.toString(), logMapper(), args.toArray());
    }

    public List<Domain.Order> queryOrders(Long merchantId, Long storeId, String status, Long waiterId, String tableNo,
                                          LocalDateTime startAt, LocalDateTime endAt, Integer limit, Integer offset) {
        StringBuilder sql = new StringBuilder("select * from order_main where 1=1");
        List<Object> args = new ArrayList<>();
        if (merchantId != null) {
            sql.append(" and merchant_id = ?");
            args.add(merchantId);
        }
        if (storeId != null) {
            sql.append(" and store_id = ?");
            args.add(storeId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and status = ?");
            args.add(status);
        }
        if (waiterId != null) {
            sql.append(" and waiter_id = ?");
            args.add(waiterId);
        }
        if (tableNo != null && !tableNo.isBlank()) {
            sql.append(" and lower(table_no) like ?");
            args.add("%" + tableNo.toLowerCase(Locale.ROOT) + "%");
        }
        if (startAt != null) {
            sql.append(" and created_at >= ?");
            args.add(ts(startAt));
        }
        if (endAt != null) {
            sql.append(" and created_at < ?");
            args.add(ts(endAt));
        }
        sql.append(" order by created_at desc");
        if (limit != null) {
            sql.append(" limit ?");
            args.add(limit);
            sql.append(" offset ?");
            args.add(offset == null ? 0 : offset);
        }
        List<Domain.Order> headers = jdbc.query(sql.toString(), orderHeaderMapper(), args.toArray());
        if (headers.isEmpty()) {
            return headers;
        }
        List<Long> orderIds = headers.stream().map(Domain.Order::id).toList();
        StringBuilder itemSql = new StringBuilder("select * from order_item where order_id in (");
        itemSql.append(String.join(",", Collections.nCopies(orderIds.size(), "?"))).append(")");
        Map<Long, List<Domain.OrderItem>> itemsByOrder = new HashMap<>();
        jdbc.query(itemSql.toString(), orderItemMapper(), orderIds.toArray())
                .forEach(item -> itemsByOrder.computeIfAbsent(item.orderId(), ignored -> new ArrayList<>()).add(item));
        return headers.stream()
                .map(order -> new Domain.Order(order.id(), order.merchantId(), order.storeId(), order.tableId(), order.tableNo(),
                        order.people(), order.waiterId(), order.waiterName(), order.status(), order.totalAmount(),
                        order.remark(), order.cancelReason(), order.createdAt(), order.updatedAt(),
                        itemsByOrder.getOrDefault(order.id(), List.of())))
                .toList();
    }

    public List<Domain.Order> queryOrders(Long merchantId, Long storeId, String status, Long waiterId, String tableNo,
                                          Integer limit, Integer offset) {
        return queryOrders(merchantId, storeId, status, waiterId, tableNo, null, null, limit, offset);
    }

    public Domain.Order queryCurrentOrder(long merchantId, long tableId) {
        Domain.DiningTable table = queryTableById(tableId);
        if (table == null || table.merchantId() != merchantId) {
            return null;
        }
        if (table.status() == TableStatus.EMPTY || table.status() == TableStatus.RESERVED || table.status() == TableStatus.CLEANING) {
            return null;
        }
        if (table.currentOrderId() != null) {
            return queryOrderById(table.currentOrderId());
        }
        List<Domain.Order> orders = jdbc.query("""
                        select * from order_main
                        where merchant_id = ? and table_id = ? and status not in ('PAID', 'CANCELLED')
                        order by created_at desc
                        limit 1
                        """,
                orderHeaderMapper(), merchantId, tableId);
        if (orders.isEmpty()) {
            return null;
        }
        return queryOrderById(orders.get(0).id());
    }

    public Domain.Order queryOrderById(long orderId) {
        List<Domain.Order> orders = jdbc.query("select * from order_main where id = ?", orderHeaderMapper(), orderId);
        if (orders.isEmpty()) {
            return null;
        }
        Domain.Order header = orders.get(0);
        List<Domain.OrderItem> items = jdbc.query("select * from order_item where order_id = ? order by id", orderItemMapper(), orderId);
        return new Domain.Order(header.id(), header.merchantId(), header.storeId(), header.tableId(), header.tableNo(),
                header.people(), header.waiterId(), header.waiterName(), header.status(), header.totalAmount(),
                header.remark(), header.cancelReason(), header.createdAt(), header.updatedAt(), items);
    }

    public long countDishUsage(long dishId) {
        Long count = jdbc.queryForObject("select count(*) from order_item where dish_id = ?", Long.class, dishId);
        return count == null ? 0L : count;
    }

    public long countCategoryUsage(long categoryId) {
        Long count = jdbc.queryForObject("select count(*) from dish where category_id = ?", Long.class, categoryId);
        return count == null ? 0L : count;
    }

    public List<Domain.PrintTask> queryFailedPrintTasks(int maxRetry) {
        return jdbc.query("""
                        select * from print_task
                        where status = 'FAILED' and retry_count < ? and (next_retry_at is null or next_retry_at <= now())
                        order by updated_at asc
                        """,
                printTaskMapper(), maxRetry);
    }

    public List<Domain.PrintTask> queryStaleSendingPrintTasks(int seconds, int maxRetry) {
        return jdbc.query("""
                        select * from print_task
                        where status = 'SENDING' and retry_count < ? and updated_at < date_sub(now(), interval ? second)
                        order by updated_at asc
                        """,
                printTaskMapper(), maxRetry, seconds);
    }

    public List<Domain.PrintTask> queryPendingPrintTasks(int limit) {
        return jdbc.query("""
                        select * from print_task
                        where status = 'WAITING'
                        order by created_at asc
                        limit ?
                        """,
                printTaskMapper(), limit);
    }

    public Domain.PrintTask queryPrintTaskById(long id) {
        List<Domain.PrintTask> tasks = jdbc.query("select * from print_task where id = ?", printTaskMapper(), id);
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    public boolean claimPrintTask(long id, PrintStatus expectedStatus, PrintStatus nextStatus) {
        int updated = jdbc.update("""
                        update print_task
                        set status = ?, updated_at = now()
                        where id = ? and status = ?
                        """,
                nextStatus.name(), id, expectedStatus.name());
        if (updated > 0) {
            Domain.PrintTask current = printTasks.get(id);
            if (current != null) {
                printTasks.put(id, new Domain.PrintTask(current.id(), current.merchantId(), current.storeId(), current.orderId(),
                        current.printerId(), current.scene(), current.content(), nextStatus, current.retryCount(),
                        current.lastError(), null, current.createdAt(), LocalDateTime.now()));
            }
            return true;
        }
        return false;
    }

    public boolean failClaimedPrintTask(long id, String error, LocalDateTime nextRetryAt) {
        int updated = jdbc.update("""
                        update print_task
                        set status = 'FAILED', retry_count = retry_count + 1, last_error = ?, next_retry_at = ?, updated_at = now()
                        where id = ? and status = 'SENDING'
                        """,
                error, ts(nextRetryAt), id);
        if (updated > 0) {
            Domain.PrintTask current = queryPrintTaskById(id);
            if (current != null) {
                printTasks.put(id, current);
            }
            return true;
        }
        return false;
    }

    public Domain.Payment queryPaymentByOrderId(long orderId) {
        List<Domain.Payment> payments = jdbc.query("select * from payment where order_id = ? order by paid_at desc limit 1", paymentMapper(), orderId);
        return payments.isEmpty() ? null : payments.get(0);
    }

    public Map<String, Object> stats(Long merchantId, Long storeId) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1=1");
        if (merchantId != null) {
            where.append(" and o.merchant_id = ?");
            args.add(merchantId);
        }
        if (storeId != null) {
            where.append(" and o.store_id = ?");
            args.add(storeId);
        }
        String paidWhere = where + " and o.status = 'PAID'";
        String openWhere = where + " and o.status not in ('PAID', 'CANCELLED')";
        Object[] paidArgs = args.toArray();
        Long paidOrders = jdbc.queryForObject("select count(*) from order_main o" + paidWhere, Long.class, paidArgs);
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        List<Object> todayPaymentArgs = new ArrayList<>();
        StringBuilder paymentWhere = new StringBuilder(" where 1=1");
        if (merchantId != null) {
            paymentWhere.append(" and p.merchant_id = ?");
            todayPaymentArgs.add(merchantId);
        }
        if (storeId != null) {
            paymentWhere.append(" and p.store_id = ?");
            todayPaymentArgs.add(storeId);
        }
        todayPaymentArgs.add(todayStart);
        todayPaymentArgs.add(todayStart.plusDays(1));
        Long todayOrders = jdbc.queryForObject("select count(*) from payment p" + paymentWhere + " and p.paid_at >= ? and p.paid_at < ?",
                Long.class, todayPaymentArgs.toArray());
        BigDecimal todayRevenue = jdbc.queryForObject(
                "select coalesce(sum(p.amount), 0) from payment p" + paymentWhere + " and p.paid_at >= ? and p.paid_at < ?",
                BigDecimal.class, todayPaymentArgs.toArray());
        java.math.BigDecimal revenue = jdbc.queryForObject(
                "select coalesce(sum(o.total_amount), 0) from order_main o" + paidWhere,
                java.math.BigDecimal.class, paidArgs);
        Long openOrders = jdbc.queryForObject("select count(*) from order_main o" + openWhere, Long.class, args.toArray());
        BigDecimal pendingAmount = jdbc.queryForObject(
                "select coalesce(sum(o.total_amount), 0) from order_main o" + openWhere,
                BigDecimal.class, args.toArray());
        List<Map<String, Object>> dishRankRows = jdbc.queryForList("""
                        select oi.dish_name_en as dish_name_en, coalesce(sum(oi.quantity), 0) as qty
                        from order_main o
                        join order_item oi on oi.order_id = o.id
                        """ + paidWhere + """
                        group by oi.dish_name_en
                        order by qty desc
                        """, paidArgs);
        Map<String, Long> dishRank = new LinkedHashMap<>();
        for (Map<String, Object> row : dishRankRows) {
            dishRank.put(String.valueOf(row.get("dish_name_en")), ((Number) row.get("qty")).longValue());
        }
        List<Object> tableArgs = new ArrayList<>();
        StringBuilder tableWhere = new StringBuilder(" where 1=1");
        if (merchantId != null) {
            tableWhere.append(" and merchant_id = ?");
            tableArgs.add(merchantId);
        }
        if (storeId != null) {
            tableWhere.append(" and store_id = ?");
            tableArgs.add(storeId);
        }
        tableWhere.append(" and status <> 'EMPTY'");
        Long tableUsage = jdbc.queryForObject("select count(*) from dining_table" + tableWhere, Long.class, tableArgs.toArray());
        return Map.of("paidOrders", paidOrders == null ? 0L : paidOrders,
                "todayOrders", todayOrders == null ? 0L : todayOrders,
                "totalOrders", paidOrders == null ? 0L : paidOrders,
                "openOrders", openOrders == null ? 0L : openOrders,
                "revenue", revenue == null ? java.math.BigDecimal.ZERO : revenue,
                "totalRevenue", revenue == null ? java.math.BigDecimal.ZERO : revenue,
                "todayRevenue", todayRevenue == null ? BigDecimal.ZERO : todayRevenue,
                "pendingAmount", pendingAmount == null ? BigDecimal.ZERO : pendingAmount,
                "dishRank", dishRank,
                "tableUsage", tableUsage == null ? 0L : tableUsage);
    }

    public Domain.SysI18n saveI18n(String key, String zh, String en, String ms, String remark) {
        Domain.SysI18n existing = i18n.values().stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
        long id = existing == null ? nextId() : existing.id();
        Domain.SysI18n next = new Domain.SysI18n(id, key, zh, en, ms, remark);
        i18n.put(id, next);
        jdbc.update("""
                        insert into sys_i18n (id, i18n_key, zh_cn, en_us, ms_my, remark)
                        values (?, ?, ?, ?, ?, ?)
                        on duplicate key update zh_cn = values(zh_cn), en_us = values(en_us),
                        ms_my = values(ms_my), remark = values(remark)
                        """,
                next.id(), next.key(), next.zhCn(), next.enUs(), next.msMy(), next.remark());
        return next;
    }

    public void deleteI18n(long id) {
        i18n.remove(id);
        jdbc.update("delete from sys_i18n where id = ?", id);
    }

    public void addI18n(String key, String zh, String en, String ms, String remark) {
        saveI18n(key, zh, en, ms, remark);
    }

    public void log(Long merchantId, Long storeId, long operatorId, String action, String detail) {
        Domain.OperationLog log = new Domain.OperationLog(nextId(), merchantId, storeId, operatorId, action, detail, LocalDateTime.now());
        logs.add(log);
        jdbc.update("insert into operation_log (id, merchant_id, store_id, operator_id, action, detail, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                log.id(), log.merchantId(), log.storeId(), log.operatorId(), log.action(), log.detail(), ts(log.createdAt()));
    }

    private void saveOrderItem(Domain.OrderItem item) {
        jdbc.update("""
                        insert into order_item (id, order_id, dish_id, dish_name_zh, dish_name_en, dish_name_ms, image_url,
                        quantity, unit_price, remark, status)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                item.id(), item.orderId(), item.dishId(), item.dishNameZh(), item.dishNameEn(), item.dishNameMs(),
                item.imageUrl(), item.quantity(), item.unitPrice(), item.remark(), item.status().name());
    }

    private void seedUsersIfMissing() {
        // Password hashes are produced by Java code, so demo accounts are seeded here instead of seed.sql.
        saveDemoUser(1L, "+60100000001", "admin", "Admin@123", "Admin");
        saveDemoUser(2L, "+60100000002", "waiter", "Waiter@123", "Aminah");
        saveDemoUser(3L, "+60100000003", "cashier", "Cashier@123", "Siti");
        saveDemoUser(4L, "+60100000000", "platform", "Platform@123", "Platform Admin");
        saveDemoUser(5L, "+60100000005", "chain_admin", "Chain@123", "Multi Merchant Admin");
        saveDemoUser(6L, "+60100000006", "store_kl", "Store@123", "KL Store Manager");
        saveDemoUser(7L, "+60100000007", "store_pj", "Store@123", "PJ Store Manager");
        saveDemoUser(8L, "+60100000008", "merchant2_admin", "Merchant2@123", "Penang Merchant Admin");
        saveDemoUser(9L, "+60100000009", "penang_cashier", "Cashier@123", "Penang Cashier");
        saveMembership(new Domain.UserMembership(9001L, 1L, 1L, null, Role.MERCHANT_ADMIN, "ACTIVE"));
        saveMembership(new Domain.UserMembership(9002L, 2L, 1L, 11L, Role.WAITER, "ACTIVE"));
        saveMembership(new Domain.UserMembership(9003L, 3L, 1L, 11L, Role.CASHIER, "ACTIVE"));
        saveMembership(new Domain.UserMembership(9004L, 4L, null, null, Role.PLATFORM_SUPER_ADMIN, "ACTIVE"));
        saveMembership(new Domain.UserMembership(9005L, 5L, 1L, null, Role.MERCHANT_ADMIN, "ACTIVE"));
        saveMembership(new Domain.UserMembership(9006L, 5L, 2L, null, Role.MERCHANT_ADMIN, "ACTIVE"));
        saveMembership(new Domain.UserMembership(9007L, 6L, 1L, 11L, Role.STORE_MANAGER, "ACTIVE"));
        saveMembership(new Domain.UserMembership(9008L, 7L, 1L, 12L, Role.STORE_MANAGER, "ACTIVE"));
        saveMembership(new Domain.UserMembership(9009L, 8L, 2L, null, Role.MERCHANT_ADMIN, "ACTIVE"));
        saveMembership(new Domain.UserMembership(9010L, 9L, 2L, 21L, Role.CASHIER, "ACTIVE"));
    }

    private void saveDemoUser(long id, String phone, String username, String rawPassword, String displayName) {
        List<String> hashes = jdbc.query("select password_hash from sys_user where id = ?",
                (rs, rowNum) -> rs.getString("password_hash"), id);
        String passwordHash = hashes.isEmpty() ? hasher.hash(rawPassword) : hashes.get(0);
        String avatarUrl = "/assets/avatar/" + username + ".png";
        saveUser(new Domain.User(id, phone, username, passwordHash, displayName, avatarUrl, true, null, 0));
    }

    private void seedSystemPermissionsIfMissing() {
        Integer roleCount = jdbc.queryForObject("select count(*) from sys_role", Integer.class);
        if (roleCount == null || roleCount == 0) {
            defaultRoles().forEach(this::saveRole);
        }
        Integer menuCount = jdbc.queryForObject("select count(*) from sys_menu", Integer.class);
        if (menuCount == null || menuCount == 0) {
            defaultMenus().forEach(this::saveMenu);
        }
        syncStandaloneUtilityMenus();
        Integer roleMenuCount = jdbc.queryForObject("select count(*) from sys_role_menu", Integer.class);
        if (roleMenuCount == null || roleMenuCount == 0) {
            saveRoleMenus(Role.PLATFORM_SUPER_ADMIN.name(), defaultMenus().stream().map(Domain.SysMenu::code).toList());
            saveRoleMenus(Role.PLATFORM_ADMIN.name(), defaultMenus().stream().map(Domain.SysMenu::code).toList());
            saveRoleMenus(Role.MERCHANT_ADMIN.name(), List.of("employees", "restaurant", "tables", "categories", "dishes", "orders", "printers"));
            saveRoleMenus(Role.STORE_MANAGER.name(), List.of("employees", "restaurant", "tables", "categories", "dishes", "orders", "printers"));
            saveRoleMenus(Role.CASHIER.name(), List.of("restaurant", "tables", "orders", "printers"));
            saveRoleMenus(Role.WAITER.name(), List.of("restaurant", "tables", "categories", "dishes", "orders"));
            saveRoleMenus(Role.KITCHEN.name(), List.of("restaurant", "orders", "printers"));
            saveRoleMenus(Role.MERCHANT_OWNER.name(), List.of("employees", "restaurant", "tables", "categories", "dishes", "orders", "printers"));
        }
        Integer permissionCount = jdbc.queryForObject("select count(*) from sys_role_permission", Integer.class);
        if (permissionCount == null || permissionCount == 0) {
            saveRolePermissions(Role.PLATFORM_SUPER_ADMIN.name(), List.of("*:*:*"));
            saveRolePermissions(Role.PLATFORM_ADMIN.name(), allPermissions());
            saveRolePermissions(Role.MERCHANT_ADMIN.name(), merchantPermissions());
            saveRolePermissions(Role.MERCHANT_OWNER.name(), merchantPermissions());
            saveRolePermissions(Role.STORE_MANAGER.name(), merchantPermissions());
            saveRolePermissions(Role.CASHIER.name(), List.of("restaurant:tables:view", "restaurant:orders:view", "restaurant:orders:update", "restaurant:printers:view"));
            saveRolePermissions(Role.WAITER.name(), List.of("restaurant:tables:view", "restaurant:tables:update", "restaurant:orders:view", "restaurant:orders:create", "restaurant:orders:update", "restaurant:dishes:view", "restaurant:categories:view"));
            saveRolePermissions(Role.KITCHEN.name(), List.of("restaurant:orders:view", "restaurant:orders:update", "restaurant:printers:view"));
        }
    }

    private List<Domain.SysRole> defaultRoles() {
        return List.of(
                new Domain.SysRole(Role.PLATFORM_SUPER_ADMIN.name(), "超级管理员", "平台级", "拥有系统全部权限", "ALL", "ACTIVE"),
                new Domain.SysRole(Role.PLATFORM_ADMIN.name(), "平台管理员", "平台级", "管理入驻商户、用户、角色、菜单和日志", "ALL", "ACTIVE"),
                new Domain.SysRole(Role.MERCHANT_OWNER.name(), "商户负责人", "商户级", "拥有商户数据和门店管理权限", "MERCHANT", "ACTIVE"),
                new Domain.SysRole(Role.MERCHANT_ADMIN.name(), "商户管理员", "商户级", "管理名下商户、门店数据和员工", "MERCHANT", "ACTIVE"),
                new Domain.SysRole(Role.STORE_MANAGER.name(), "店长", "门店级", "管理当前门店桌台、菜品和员工", "STORE", "ACTIVE"),
                new Domain.SysRole(Role.CASHIER.name(), "收银员", "门店级", "处理桌台账单、结账和小票打印", "STORE", "ACTIVE"),
                new Domain.SysRole(Role.WAITER.name(), "服务员", "门店级", "负责点餐、加菜、退菜和开台", "STORE", "ACTIVE"),
                new Domain.SysRole(Role.KITCHEN.name(), "后厨", "门店级", "处理后厨订单和出品状态", "STORE", "ACTIVE")
        );
    }

    private List<Domain.SysMenu> defaultMenus() {
        return List.of(
                new Domain.SysMenu("system", "restaurant.system", null, 10, "ep:setting", true, "/system", "", "System", true, null),
                new Domain.SysMenu("users", "restaurant.user", "system", 11, "ep:user", true, "user", "system/user/index", "SystemUser", true, "system:user:view"),
                new Domain.SysMenu("roles", "restaurant.role", "system", 12, "ep:user-filled", true, "role", "system/role/index", "SystemRole", true, "system:role:view"),
                new Domain.SysMenu("menus", "restaurant.menu", "system", 13, "ep:menu", true, "menu", "system/menu/index", "SystemMenu", true, "system:menu:view"),
                new Domain.SysMenu("departments", "restaurant.dept", "system", 14, "ep:office-building", true, "dept", "system/dept/index", "SystemDept", true, "system:dept:view"),
                new Domain.SysMenu("merchants", "restaurant.merchant", "system", 15, "ep:shop", true, "merchant", "restaurant/admin/DataTable", "MerchantManage", true, "system:merchant:view"),
                new Domain.SysMenu("employees", "restaurant.employee", null, 16, "ep:user", true, "/employees", "system/user/index", "EmployeeManage", true, "system:user:view"),
                new Domain.SysMenu("restaurant", "restaurant.restaurant", null, 30, "ep:dish", true, "/restaurant", "", "Restaurant", true, null),
                new Domain.SysMenu("tables", "restaurant.tables", "restaurant", 31, "ep:grid", true, "tables", "restaurant/admin/DataTable", "RestaurantTables", true, "restaurant:tables:view"),
                new Domain.SysMenu("categories", "restaurant.categories", "restaurant", 32, "ep:collection", true, "categories", "restaurant/admin/DataTable", "RestaurantCategories", true, "restaurant:categories:view"),
                new Domain.SysMenu("dishes", "restaurant.dishes", "restaurant", 33, "ep:goods", true, "dishes", "restaurant/admin/DataTable", "RestaurantDishes", true, "restaurant:dishes:view"),
                new Domain.SysMenu("orders", "restaurant.orders", "restaurant", 34, "ep:tickets", true, "orders", "restaurant/admin/DataTable", "RestaurantOrders", true, "restaurant:orders:view"),
                new Domain.SysMenu("printers", "restaurant.printers", null, 90, "ep:printer", true, "/printers", "restaurant/admin/DataTable", "RestaurantPrinters", true, "restaurant:printers:view"),
                new Domain.SysMenu("i18n", "restaurant.i18n", null, 91, "ep:chat-line-square", true, "/i18n", "restaurant/admin/DataTable", "RestaurantI18n", true, "system:i18n:view"),
                new Domain.SysMenu("logs", "restaurant.logs", null, 92, "ep:document", true, "/logs", "restaurant/admin/DataTable", "RestaurantLogs", true, "system:logs:view")
        );
    }

    private void syncStandaloneUtilityMenus() {
        Set<String> utilityMenuCodes = Set.of("printers", "i18n", "logs");
        defaultMenus().stream()
                .filter(menu -> utilityMenuCodes.contains(menu.code()))
                .forEach(this::saveMenu);
    }

    private List<String> allPermissions() {
        return List.of("*:*:*");
    }

    private List<String> merchantPermissions() {
        return List.of(
                "system:user:view", "system:user:create", "system:user:update", "system:user:delete", "system:user:password",
                "restaurant:tables:view", "restaurant:tables:create", "restaurant:tables:update", "restaurant:tables:delete",
                "restaurant:categories:view", "restaurant:categories:create", "restaurant:categories:update", "restaurant:categories:delete",
                "restaurant:dishes:view", "restaurant:dishes:create", "restaurant:dishes:update", "restaurant:dishes:delete",
                "restaurant:orders:view", "restaurant:orders:create", "restaurant:orders:update", "restaurant:orders:delete",
                "restaurant:printers:view", "restaurant:printers:create", "restaurant:printers:update", "restaurant:printers:delete"
        );
    }

    private void executeSqlResource(String location) {
        try {
            Resource resource = resourceLoader.getResource(location);
            try (InputStream in = resource.getInputStream()) {
                String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // The project SQL files contain simple statements only; keep this runner dependency-free.
                for (String statement : sql.split(";")) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty()) {
                        jdbc.execute(trimmed);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to execute SQL resource " + location, e);
        }
    }

    private void patchExistingSchema() {
        addColumnIfMissing("merchant", "status", "ALTER TABLE merchant ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'");
        addColumnIfMissing("sys_user", "phone", "ALTER TABLE sys_user ADD COLUMN phone VARCHAR(32) NULL");
        addColumnIfMissing("sys_user", "avatar_url", "ALTER TABLE sys_user ADD COLUMN avatar_url VARCHAR(500) NULL");
        modifyColumnIfExists("sys_user", "merchant_id", "ALTER TABLE sys_user MODIFY COLUMN merchant_id BIGINT NULL");
        modifyColumnIfExists("sys_user", "role", "ALTER TABLE sys_user MODIFY COLUMN role VARCHAR(32) NULL");
        addColumnIfMissing("dining_table", "store_id", "ALTER TABLE dining_table ADD COLUMN store_id BIGINT NOT NULL DEFAULT 11");
        addColumnIfMissing("dining_table", "reservation_name", "ALTER TABLE dining_table ADD COLUMN reservation_name VARCHAR(80) NULL");
        addColumnIfMissing("dining_table", "reservation_phone", "ALTER TABLE dining_table ADD COLUMN reservation_phone VARCHAR(40) NULL");
        addColumnIfMissing("dining_table", "reservation_arrival_time", "ALTER TABLE dining_table ADD COLUMN reservation_arrival_time VARCHAR(80) NULL");
        modifyColumnIfExists("dining_table", "reservation_arrival_time", "ALTER TABLE dining_table MODIFY COLUMN reservation_arrival_time VARCHAR(80) NULL");
        addColumnIfMissing("dish_category", "store_id", "ALTER TABLE dish_category ADD COLUMN store_id BIGINT NOT NULL DEFAULT 11");
        addColumnIfMissing("dish", "store_id", "ALTER TABLE dish ADD COLUMN store_id BIGINT NOT NULL DEFAULT 11");
        addColumnIfMissing("order_main", "store_id", "ALTER TABLE order_main ADD COLUMN store_id BIGINT NOT NULL DEFAULT 11");
        addColumnIfMissing("order_main", "people", "ALTER TABLE order_main ADD COLUMN people INT NOT NULL DEFAULT 0");
        addColumnIfMissing("order_item", "image_url", "ALTER TABLE order_item ADD COLUMN image_url VARCHAR(255) NULL");
        addColumnIfMissing("payment", "store_id", "ALTER TABLE payment ADD COLUMN store_id BIGINT NOT NULL DEFAULT 11");
        addColumnIfMissing("printer", "store_id", "ALTER TABLE printer ADD COLUMN store_id BIGINT NOT NULL DEFAULT 11");
        addColumnIfMissing("print_task", "store_id", "ALTER TABLE print_task ADD COLUMN store_id BIGINT NOT NULL DEFAULT 11");
        addColumnIfMissing("print_task", "next_retry_at", "ALTER TABLE print_task ADD COLUMN next_retry_at DATETIME NULL");
        addColumnIfMissing("operation_log", "store_id", "ALTER TABLE operation_log ADD COLUMN store_id BIGINT NULL");
        modifyColumnIfExists("operation_log", "merchant_id", "ALTER TABLE operation_log MODIFY COLUMN merchant_id BIGINT NULL");
        modifyColumnIfExists("operation_log", "store_id", "ALTER TABLE operation_log MODIFY COLUMN store_id BIGINT NULL");
        addIndexIfMissing("merchant_store", "idx_store_merchant", "ALTER TABLE merchant_store ADD KEY idx_store_merchant (merchant_id)");
        addIndexIfMissing("user_membership", "idx_membership_user_active", "ALTER TABLE user_membership ADD KEY idx_membership_user_active (user_id, status)");
        addIndexIfMissing("user_membership", "idx_membership_merchant_store", "ALTER TABLE user_membership ADD KEY idx_membership_merchant_store (merchant_id, store_id)");
        addIndexIfMissing("dining_table", "idx_table_merchant_store_status", "ALTER TABLE dining_table ADD KEY idx_table_merchant_store_status (merchant_id, store_id, status)");
        addIndexIfMissing("dish_category", "idx_category_merchant_store", "ALTER TABLE dish_category ADD KEY idx_category_merchant_store (merchant_id, store_id, sort_no)");
        addIndexIfMissing("dish", "idx_dish_merchant_store_category", "ALTER TABLE dish ADD KEY idx_dish_merchant_store_category (merchant_id, store_id, category_id, enabled)");
        addIndexIfMissing("dish", "idx_dish_merchant_store_enabled", "ALTER TABLE dish ADD KEY idx_dish_merchant_store_enabled (merchant_id, store_id, enabled)");
        addIndexIfMissing("order_main", "idx_order_merchant_store_status_created", "ALTER TABLE order_main ADD KEY idx_order_merchant_store_status_created (merchant_id, store_id, status, created_at)");
        addIndexIfMissing("order_main", "idx_order_table_status", "ALTER TABLE order_main ADD KEY idx_order_table_status (table_id, status)");
        addIndexIfMissing("order_main", "idx_order_store_created", "ALTER TABLE order_main ADD KEY idx_order_store_created (store_id, created_at)");
        addIndexIfMissing("order_item", "idx_order_item_order", "ALTER TABLE order_item ADD KEY idx_order_item_order (order_id)");
        addIndexIfMissing("order_item", "idx_order_item_dish", "ALTER TABLE order_item ADD KEY idx_order_item_dish (dish_id)");
        addIndexIfMissing("payment", "idx_payment_merchant_store_paid", "ALTER TABLE payment ADD KEY idx_payment_merchant_store_paid (merchant_id, store_id, paid_at)");
        addIndexIfMissing("payment", "idx_payment_order", "ALTER TABLE payment ADD KEY idx_payment_order (order_id)");
        addIndexIfMissing("printer", "idx_printer_merchant_store_type", "ALTER TABLE printer ADD KEY idx_printer_merchant_store_type (merchant_id, store_id, type, enabled)");
        addIndexIfMissing("print_task", "idx_print_task_merchant_store_status_created", "ALTER TABLE print_task ADD KEY idx_print_task_merchant_store_status_created (merchant_id, store_id, status, created_at)");
        addIndexIfMissing("print_task", "idx_print_task_status_next_retry", "ALTER TABLE print_task ADD KEY idx_print_task_status_next_retry (status, next_retry_at)");
        addIndexIfMissing("print_task", "idx_print_task_order", "ALTER TABLE print_task ADD KEY idx_print_task_order (order_id)");
        addIndexIfMissing("print_task", "idx_print_task_printer", "ALTER TABLE print_task ADD KEY idx_print_task_printer (printer_id)");
        addIndexIfMissing("operation_log", "idx_log_merchant_store_created", "ALTER TABLE operation_log ADD KEY idx_log_merchant_store_created (merchant_id, store_id, created_at)");
        addIndexIfMissing("operation_log", "idx_log_created", "ALTER TABLE operation_log ADD KEY idx_log_created (created_at)");
        createArchiveTableIfMissing("order_main", "order_main_archive");
        createArchiveTableIfMissing("order_item", "order_item_archive");
        addColumnIfMissing("order_item_archive", "image_url", "ALTER TABLE order_item_archive ADD COLUMN image_url VARCHAR(255) NULL");
        createArchiveTableIfMissing("payment", "payment_archive");
        createArchiveTableIfMissing("print_task", "print_task_archive");
        createArchiveTableIfMissing("operation_log", "operation_log_archive");
        jdbc.update("update sys_user set phone = concat('+60', lpad(id, 9, '0')) where phone is null or phone = ''");
        jdbc.update("""
                update sys_i18n
                set zh_cn = '订单', en_us = 'Orders', ms_my = 'Pesanan'
                where i18n_key = 'history_orders'
                """);
        jdbc.update("""
                update sys_i18n
                set zh_cn = '就餐人数', en_us = 'Guests', ms_my = 'Tetamu'
                where i18n_key = 'order_guest_count'
                """);
        jdbc.update("""
                update order_item oi
                join dish d on d.id = oi.dish_id
                set oi.image_url = d.image_url
                where (oi.image_url is null or oi.image_url = '') and d.image_url is not null and d.image_url <> ''
                """);
        jdbc.update("""
                update order_item_archive oi
                join dish d on d.id = oi.dish_id
                set oi.image_url = d.image_url
                where (oi.image_url is null or oi.image_url = '') and d.image_url is not null and d.image_url <> ''
                """);
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        Integer count = jdbc.queryForObject("""
                        select count(*) from information_schema.columns
                        where table_schema = database() and table_name = ? and column_name = ?
                        """,
                Integer.class, table, column);
        if (count == null || count == 0) {
            jdbc.execute(ddl);
        }
    }

    private void modifyColumnIfExists(String table, String column, String ddl) {
        Integer count = jdbc.queryForObject("""
                        select count(*) from information_schema.columns
                        where table_schema = database() and table_name = ? and column_name = ?
                        """,
                Integer.class, table, column);
        if (count != null && count > 0) {
            jdbc.execute(ddl);
        }
    }

    private void addIndexIfMissing(String table, String indexName, String ddl) {
        Integer count = jdbc.queryForObject("""
                        select count(*) from information_schema.statistics
                        where table_schema = database() and table_name = ? and index_name = ?
                        """,
                Integer.class, table, indexName);
        if (count == null || count == 0) {
            jdbc.execute(ddl);
        }
    }

    private void createArchiveTableIfMissing(String sourceTable, String archiveTable) {
        Integer count = jdbc.queryForObject("""
                        select count(*) from information_schema.tables
                        where table_schema = database() and table_name = ?
                        """,
                Integer.class, archiveTable);
        if (count == null || count == 0) {
            jdbc.execute("CREATE TABLE " + archiveTable + " LIKE " + sourceTable);
        }
    }

    private long maxTableId(String table, String column) {
        Long max = jdbc.queryForObject("select coalesce(max(" + column + "), 0) from " + table, Long.class);
        return max == null ? 0L : max;
    }

    private RowMapper<Domain.Merchant> merchantMapper() {
        return (rs, rowNum) -> new Domain.Merchant(rs.getLong("id"), rs.getString("name_zh"), rs.getString("name_en"),
                rs.getString("name_ms"), rs.getString("phone"), rs.getString("address"), rs.getString("status"));
    }

    private RowMapper<Domain.SysRole> roleMapper() {
        return (rs, rowNum) -> new Domain.SysRole(rs.getString("code"), rs.getString("name"),
                rs.getString("scope"), rs.getString("description"), rs.getString("data_scope"),
                rs.getString("status"));
    }

    private RowMapper<Domain.SysMenu> menuMapper() {
        return (rs, rowNum) -> new Domain.SysMenu(rs.getString("code"), rs.getString("name"),
                rs.getString("parent_code"), rs.getInt("sort_no"), rs.getString("icon"),
                rs.getInt("visible") == 1, rs.getString("path"), rs.getString("component"),
                rs.getString("component_name"), rs.getInt("keep_alive") == 1, rs.getString("permission"));
    }

    private RowMapper<Domain.Store> storeMapper() {
        return (rs, rowNum) -> new Domain.Store(rs.getLong("id"), rs.getLong("merchant_id"), rs.getString("code"),
                rs.getString("name"), rs.getString("phone"), rs.getString("address"), rs.getString("status"));
    }

    private RowMapper<Domain.User> userMapper() {
        return (rs, rowNum) -> new Domain.User(rs.getLong("id"), rs.getString("phone"), rs.getString("username"),
                rs.getString("password_hash"), rs.getString("display_name"), rs.getString("avatar_url"),
                rs.getInt("enabled") == 1, ldt(rs, "locked_until"), rs.getInt("fail_count"));
    }

    private RowMapper<Domain.UserMembership> membershipMapper() {
        return (rs, rowNum) -> new Domain.UserMembership(rs.getLong("id"), rs.getLong("user_id"),
                nullableLong(rs, "merchant_id"), nullableLong(rs, "store_id"), Role.valueOf(rs.getString("role")),
                rs.getString("status"));
    }

    private RowMapper<Domain.DiningTable> tableMapper() {
        return (rs, rowNum) -> new Domain.DiningTable(rs.getLong("id"), rs.getLong("merchant_id"),
                rs.getLong("store_id"), rs.getString("area"), rs.getString("table_no"), rs.getInt("max_people"),
                TableStatus.valueOf(rs.getString("status")), rs.getInt("current_people"), ldt(rs, "opened_at"),
                nullableLong(rs, "current_order_id"), rs.getString("reservation_name"), rs.getString("reservation_phone"),
                rs.getString("reservation_arrival_time"));
    }

    private RowMapper<Domain.DishCategory> categoryMapper() {
        return (rs, rowNum) -> new Domain.DishCategory(rs.getLong("id"), rs.getLong("merchant_id"),
                rs.getLong("store_id"), rs.getString("name_zh"), rs.getString("name_en"), rs.getString("name_ms"),
                rs.getInt("sort_no"));
    }

    private RowMapper<Domain.Dish> dishMapper() {
        return (rs, rowNum) -> new Domain.Dish(rs.getLong("id"), rs.getLong("merchant_id"), rs.getLong("store_id"),
                rs.getLong("category_id"), rs.getString("name_zh"), rs.getString("name_en"), rs.getString("name_ms"),
                rs.getString("description_zh"), rs.getString("description_en"), rs.getString("description_ms"),
                rs.getString("image_url"), rs.getBigDecimal("price"), rs.getString("spec"),
                rs.getInt("stock"), rs.getInt("enabled") == 1);
    }

    private RowMapper<Domain.OrderItem> orderItemMapper() {
        return (rs, rowNum) -> new Domain.OrderItem(rs.getLong("id"), rs.getLong("order_id"), rs.getLong("dish_id"),
                rs.getString("dish_name_zh"), rs.getString("dish_name_en"), rs.getString("dish_name_ms"),
                rs.getString("image_url"), rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getString("remark"),
                OrderItemStatus.valueOf(rs.getString("status")));
    }

    private RowMapper<Domain.Order> orderMapper(Map<Long, List<Domain.OrderItem>> itemsByOrder) {
        return (rs, rowNum) -> new Domain.Order(rs.getLong("id"), rs.getLong("merchant_id"), rs.getLong("store_id"),
                rs.getLong("table_id"), rs.getString("table_no"), rs.getInt("people"), rs.getLong("waiter_id"), rs.getString("waiter_name"),
                OrderStatus.valueOf(rs.getString("status")), rs.getBigDecimal("total_amount"), rs.getString("remark"),
                rs.getString("cancel_reason"), ldt(rs, "created_at"), ldt(rs, "updated_at"),
                itemsByOrder.getOrDefault(rs.getLong("id"), List.of()));
    }

    private RowMapper<Domain.Order> orderHeaderMapper() {
        return (rs, rowNum) -> new Domain.Order(rs.getLong("id"), rs.getLong("merchant_id"), rs.getLong("store_id"),
                rs.getLong("table_id"), rs.getString("table_no"), rs.getInt("people"), rs.getLong("waiter_id"), rs.getString("waiter_name"),
                OrderStatus.valueOf(rs.getString("status")), rs.getBigDecimal("total_amount"), rs.getString("remark"),
                rs.getString("cancel_reason"), ldt(rs, "created_at"), ldt(rs, "updated_at"), List.of());
    }

    private RowMapper<Domain.Payment> paymentMapper() {
        return (rs, rowNum) -> new Domain.Payment(rs.getLong("id"), rs.getLong("merchant_id"), rs.getLong("store_id"),
                rs.getLong("order_id"), PaymentMethod.valueOf(rs.getString("method")), rs.getBigDecimal("amount"),
                rs.getString("reference_no"), ldt(rs, "paid_at"), rs.getLong("cashier_id"));
    }

    private RowMapper<Domain.Printer> printerMapper() {
        return (rs, rowNum) -> new Domain.Printer(rs.getLong("id"), rs.getLong("merchant_id"), rs.getLong("store_id"),
                rs.getString("name"), PrinterType.valueOf(rs.getString("type")), rs.getString("ip"), rs.getInt("port"),
                rs.getInt("enabled") == 1);
    }

    private RowMapper<Domain.PrintTask> printTaskMapper() {
        return (rs, rowNum) -> new Domain.PrintTask(rs.getLong("id"), rs.getLong("merchant_id"), rs.getLong("store_id"),
                nullableLong(rs, "order_id"), rs.getLong("printer_id"), PrintScene.valueOf(rs.getString("scene")),
                rs.getString("content"), PrintStatus.valueOf(rs.getString("status")), rs.getInt("retry_count"),
                rs.getString("last_error"), ldt(rs, "next_retry_at"), ldt(rs, "created_at"), ldt(rs, "updated_at"));
    }

    private RowMapper<Domain.SysI18n> i18nMapper() {
        return (rs, rowNum) -> new Domain.SysI18n(rs.getLong("id"), rs.getString("i18n_key"), rs.getString("zh_cn"),
                rs.getString("en_us"), rs.getString("ms_my"), rs.getString("remark"));
    }

    private RowMapper<Domain.OperationLog> logMapper() {
        return (rs, rowNum) -> new Domain.OperationLog(rs.getLong("id"), nullableLong(rs, "merchant_id"),
                nullableLong(rs, "store_id"), rs.getLong("operator_id"), rs.getString("action"), rs.getString("detail"),
                ldt(rs, "created_at"));
    }

    private Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private LocalDateTime ldt(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private Long nullableLong(ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }
}
