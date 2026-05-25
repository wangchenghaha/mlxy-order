CREATE TABLE IF NOT EXISTS sys_i18n (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  i18n_key VARCHAR(100) NOT NULL UNIQUE,
  zh_cn VARCHAR(500),
  en_us VARCHAR(500),
  ms_my VARCHAR(500),
  remark VARCHAR(255),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局国际化配置表';

CREATE TABLE IF NOT EXISTS merchant (
  id BIGINT PRIMARY KEY,
  name_zh VARCHAR(120) NOT NULL,
  name_en VARCHAR(120) NOT NULL,
  name_ms VARCHAR(120) NOT NULL,
  phone VARCHAR(40),
  address VARCHAR(255),
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_store (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(120) NOT NULL,
  phone VARCHAR(40),
  address VARCHAR(255),
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  KEY idx_store_merchant (merchant_id),
  UNIQUE KEY uk_store_merchant_code (merchant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT PRIMARY KEY,
  phone VARCHAR(32) NOT NULL,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(64) NOT NULL,
  avatar_url VARCHAR(500),
  enabled TINYINT NOT NULL DEFAULT 1,
  locked_until DATETIME NULL,
  fail_count INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_phone (phone),
  UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_membership (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  merchant_id BIGINT NULL,
  store_id BIGINT NULL,
  role VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  KEY idx_membership_user_active (user_id, status),
  KEY idx_membership_merchant_store (merchant_id, store_id),
  UNIQUE KEY uk_user_scope_role (user_id, merchant_id, store_id, role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_role (
  code VARCHAR(64) PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  scope VARCHAR(80),
  description VARCHAR(500),
  data_scope VARCHAR(32) NOT NULL DEFAULT 'STORE',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_menu (
  code VARCHAR(80) PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  parent_code VARCHAR(80),
  sort_no INT NOT NULL DEFAULT 0,
  icon VARCHAR(80),
  visible TINYINT NOT NULL DEFAULT 1,
  path VARCHAR(120) NOT NULL,
  component VARCHAR(160),
  component_name VARCHAR(120),
  keep_alive TINYINT NOT NULL DEFAULT 1,
  permission VARCHAR(120)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_role_menu (
  role_code VARCHAR(64) NOT NULL,
  menu_code VARCHAR(80) NOT NULL,
  PRIMARY KEY (role_code, menu_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_role_permission (
  role_code VARCHAR(64) NOT NULL,
  permission VARCHAR(120) NOT NULL,
  PRIMARY KEY (role_code, permission)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dining_table (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  area VARCHAR(64) NOT NULL,
  table_no VARCHAR(32) NOT NULL,
  max_people INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  current_people INT DEFAULT 0,
  opened_at DATETIME NULL,
  current_order_id BIGINT NULL,
  reservation_name VARCHAR(80),
  reservation_phone VARCHAR(40),
  reservation_arrival_time VARCHAR(80),
  KEY idx_table_merchant_store_status (merchant_id, store_id, status),
  UNIQUE KEY uk_table_store_no (store_id, table_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dish_category (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  name_zh VARCHAR(80), name_en VARCHAR(80), name_ms VARCHAR(80),
  sort_no INT NOT NULL DEFAULT 0,
  KEY idx_category_merchant_store (merchant_id, store_id, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dish (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  category_id BIGINT NOT NULL,
  name_zh VARCHAR(120), name_en VARCHAR(120), name_ms VARCHAR(120),
  description_zh VARCHAR(500), description_en VARCHAR(500), description_ms VARCHAR(500),
  image_url VARCHAR(255),
  price DECIMAL(10,2) NOT NULL,
  spec VARCHAR(80),
  stock INT DEFAULT 9999,
  enabled TINYINT NOT NULL DEFAULT 1,
  KEY idx_dish_merchant_store_category (merchant_id, store_id, category_id, enabled),
  KEY idx_dish_merchant_store_enabled (merchant_id, store_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_main (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  table_id BIGINT NOT NULL,
  table_no VARCHAR(32) NOT NULL,
  people INT NOT NULL DEFAULT 0,
  waiter_id BIGINT NOT NULL,
  waiter_name VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  total_amount DECIMAL(10,2) NOT NULL,
  remark VARCHAR(500),
  cancel_reason VARCHAR(500),
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  KEY idx_order_merchant_store_status_created (merchant_id, store_id, status, created_at),
  KEY idx_order_table_status (table_id, status),
  KEY idx_order_store_created (store_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_item (
  id BIGINT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  dish_id BIGINT NOT NULL,
  dish_name_zh VARCHAR(120), dish_name_en VARCHAR(120), dish_name_ms VARCHAR(120),
  image_url VARCHAR(255),
  quantity INT NOT NULL,
  unit_price DECIMAL(10,2) NOT NULL,
  remark VARCHAR(255),
  status VARCHAR(32) NOT NULL,
  KEY idx_order_item_order (order_id),
  KEY idx_order_item_dish (dish_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_main_archive LIKE order_main;
CREATE TABLE IF NOT EXISTS order_item_archive LIKE order_item;

CREATE TABLE IF NOT EXISTS payment (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  method VARCHAR(40) NOT NULL,
  amount DECIMAL(10,2) NOT NULL,
  reference_no VARCHAR(120),
  paid_at DATETIME NOT NULL,
  cashier_id BIGINT NOT NULL,
  KEY idx_payment_merchant_store_paid (merchant_id, store_id, paid_at),
  KEY idx_payment_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_archive LIKE payment;

CREATE TABLE IF NOT EXISTS printer (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  name VARCHAR(80) NOT NULL,
  type VARCHAR(32) NOT NULL,
  ip VARCHAR(64) NOT NULL,
  port INT NOT NULL DEFAULT 9100,
  enabled TINYINT NOT NULL DEFAULT 1,
  KEY idx_printer_merchant_store_type (merchant_id, store_id, type, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS print_task (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  order_id BIGINT,
  printer_id BIGINT NOT NULL,
  scene VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(500),
  next_retry_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  KEY idx_print_task_merchant_store_status_created (merchant_id, store_id, status, created_at),
  KEY idx_print_task_status_next_retry (status, next_retry_at),
  KEY idx_print_task_order (order_id),
  KEY idx_print_task_printer (printer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS print_task_archive LIKE print_task;

CREATE TABLE IF NOT EXISTS operation_log (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NULL,
  store_id BIGINT NULL,
  operator_id BIGINT NOT NULL,
  action VARCHAR(80) NOT NULL,
  detail VARCHAR(1000),
  created_at DATETIME NOT NULL,
  KEY idx_log_merchant_store_created (merchant_id, store_id, created_at),
  KEY idx_log_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS operation_log_archive LIKE operation_log;
