# Malaysia Restaurant Ordering Backend

Spring Boot 3.2 + Java 17 backend for the waiter APP, cashier PC client, and admin portal.

## Implemented Scope

- Multi-role login with token, password failure lock, enabled/disabled staff accounts.
- Merchant scoped table, dish, order, cashier, printer, i18n, log APIs.
- Waiter workflow: open table, submit order, add/return dishes, cancel pending kitchen order, mark served, transfer table, request checkout.
- Cashier workflow: unified checkout, five Malaysia payment methods, receipt reprint, front desk printer test.
- Admin workflow: employees, tables, dish categories, dishes, orders, statistics, printers, global i18n, operation logs.
- Backend-dispatched WiFi print tasks: kitchen ticket, add/return ticket, cashier receipt, retry job.

## Demo Accounts

- `admin / Admin@123`
- `waiter / Waiter@123`
- `cashier / Cashier@123`

## Run

```bash
docker compose up -d mysql redis
mvn spring-boot:run
```

The application now initializes and persists data in MySQL on startup:

- `src/main/resources/db/schema.sql` creates the tables.
- `src/main/resources/db/seed.sql` seeds merchant, tables, dishes, printers, and i18n text.
- Demo users are inserted by the backend because their passwords are hashed in Java.

Default database settings:

```bash
MYSQL_URL=jdbc:mysql://localhost:3306/restaurant_order?useUnicode=true\&characterEncoding=utf8\&serverTimezone=Asia/Kuala_Lumpur
MYSQL_USERNAME=restaurant
MYSQL_PASSWORD=restaurant
```

Local MySQL setup without Docker:

```bash
mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS restaurant_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS 'restaurant'@'localhost' IDENTIFIED BY 'restaurant'; GRANT ALL PRIVILEGES ON restaurant_order.* TO 'restaurant'@'localhost'; FLUSH PRIVILEGES;"
JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home mvn spring-boot:run
```

This source targets Java 17 as required by the technical document.
