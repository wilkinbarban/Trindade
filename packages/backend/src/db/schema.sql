-- ============================================================
-- Trindade Massas Operações — Database Schema
-- 15 user tables: the approved schema after the admin-refactor-loading-rules change
-- dropped report_products and report_quantities, whose data report_items carries in
-- selected_products as JSON, and schema revision 2 added auth_sessions. The PRD listed
-- 17 tables; the refactor superseded two, revision 2 added one, and revision 6 added massa_launches.
-- Changing this file needs a matching SCHEMA_VERSION bump in schema-version.ts and an
-- approved migration path: a fresh installation stamps PRAGMA user_version, and an
-- existing database is never migrated, reset, seeded, or stamped by startup.
-- ============================================================

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- ============================================================
-- Roles
-- ============================================================
CREATE TABLE IF NOT EXISTS roles (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT    NOT NULL UNIQUE,  -- 'Administrador' | 'Trabalhador'
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Users
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  username       TEXT    NOT NULL UNIQUE,
  password_hash  TEXT    NOT NULL,
  display_name   TEXT    NOT NULL,
  role_id        INTEGER NOT NULL REFERENCES roles(id),
  is_active      INTEGER NOT NULL DEFAULT 1,
  security_version INTEGER NOT NULL DEFAULT 1 CHECK (security_version >= 1),
  created_at     TEXT    NOT NULL DEFAULT (datetime('now')),
  updated_at     TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Auth Sessions (refresh-token persistence)
-- ============================================================
CREATE TABLE IF NOT EXISTS auth_sessions (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash   TEXT    NOT NULL UNIQUE,   -- sha256 hex of the refresh token; never the raw value
  family_id    TEXT    NOT NULL,          -- rotation family, for reuse detection
  expires_at   TEXT    NOT NULL,
  revoked_at   TEXT,
  replaced_by  TEXT,                      -- token_hash of the successor
  created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
  last_used_at TEXT,
  ip_address   TEXT
);

-- ============================================================
-- Report Categories (dynamic, user-created)
-- ============================================================
CREATE TABLE IF NOT EXISTS report_categories (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  parent_category_id INTEGER REFERENCES report_categories(id),
  name_pt     TEXT    NOT NULL,            -- name in Portuguese
  name_es     TEXT    NOT NULL,            -- name in Spanish
  category_type TEXT    NOT NULL DEFAULT 'check' CHECK(category_type IN ('check','temperature','check_assai','check_normal')),
  sort_order  INTEGER NOT NULL DEFAULT 0,
  is_active   INTEGER NOT NULL DEFAULT 1,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Report Tasks (items inside a category, e.g. "Pátio organizado")
-- ============================================================
CREATE TABLE IF NOT EXISTS report_tasks (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  category_id   INTEGER NOT NULL REFERENCES report_categories(id),
  name_pt       TEXT    NOT NULL,
  name_es       TEXT    NOT NULL,
  temperature_readings INTEGER NOT NULL DEFAULT 1 CHECK(temperature_readings BETWEEN 1 AND 3),
  is_active     INTEGER NOT NULL DEFAULT 1,
  created_by_user_id INTEGER REFERENCES users(id),
  created_at    TEXT    NOT NULL DEFAULT (datetime('now'))
);


-- ============================================================
-- Report Templates (pre-defined report structures)
-- ============================================================
CREATE TABLE IF NOT EXISTS report_templates (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT    NOT NULL,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Reports
-- ============================================================
CREATE TABLE IF NOT EXISTS reports (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id         INTEGER NOT NULL REFERENCES users(id),
  turno           TEXT    NOT NULL CHECK(turno IN ('tarde','noite')),
  report_date     TEXT    NOT NULL,          -- YYYY-MM-DD
  notes           TEXT,
  is_editable     INTEGER NOT NULL DEFAULT 1,
  is_active      INTEGER NOT NULL DEFAULT 1,
  created_at      TEXT    NOT NULL DEFAULT (datetime('now')),
  updated_at      TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Report Items (checklist / list selections)
-- ============================================================
CREATE TABLE IF NOT EXISTS report_items (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id INTEGER NOT NULL REFERENCES reports(id),
  task_id   INTEGER NOT NULL REFERENCES report_tasks(id),
  checked   INTEGER NOT NULL DEFAULT 0,
  selected_products TEXT,                      -- JSON array of selected product names for check_assai/check_normal
  UNIQUE(report_id, task_id)
);

-- ============================================================
-- Report Temperatures
-- ============================================================
CREATE TABLE IF NOT EXISTS report_temperatures (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id   INTEGER NOT NULL REFERENCES reports(id),
  location    TEXT    NOT NULL,              -- e.g. "Câmara Principal"
  reading_index INTEGER NOT NULL DEFAULT 1 CHECK(reading_index BETWEEN 1 AND 3),
  value       REAL    NOT NULL,              -- e.g. -18.0
  created_at  TEXT    NOT NULL DEFAULT (datetime('now')),
  UNIQUE(report_id, location, reading_index)
);

-- ============================================================

-- ============================================================
-- Report Photos
-- ============================================================
CREATE TABLE IF NOT EXISTS report_photos (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id   INTEGER NOT NULL REFERENCES reports(id),
  file_path    TEXT    NOT NULL,
  file_size    INTEGER NOT NULL,             -- bytes, max 5MB
  mime_type    TEXT    NOT NULL CHECK(mime_type IN ('image/jpeg','image/png','image/webp')),
  public_token TEXT    UNIQUE,
  created_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Drivers (Fleteros — external freelancers)
-- ============================================================
CREATE TABLE IF NOT EXISTS drivers (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT    NOT NULL,
  license_plate TEXT,                        -- optional for fleteros
  phone       TEXT,                        -- optional fletero number for the 1-on-1 WhatsApp dispatch
  driver_type TEXT    NOT NULL DEFAULT 'fletero' CHECK(driver_type IN ('casa', 'fletero')),
  is_active   INTEGER NOT NULL DEFAULT 1,
  created_by_user_id INTEGER REFERENCES users(id),
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Vehicles (Casa — company-owned)
-- ============================================================
CREATE TABLE IF NOT EXISTS vehicles (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  description     TEXT    NOT NULL,
  license_plate   TEXT    NOT NULL,          -- mandatory for company vehicles
  is_active       INTEGER NOT NULL DEFAULT 1,
  created_at      TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Loading Schedules
-- ============================================================
CREATE TABLE IF NOT EXISTS loading_schedules (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  schedule_date TEXT   NOT NULL,             -- YYYY-MM-DD
  time_slot   TEXT    NOT NULL,              -- '04:00', '04:30', etc.
  driver_id   INTEGER REFERENCES drivers(id),
  vehicle_id  INTEGER REFERENCES vehicles(id),
  driver_type TEXT    NOT NULL CHECK(driver_type IN ('fletero','casa')),
  user_id     INTEGER REFERENCES users(id),
  is_active   INTEGER NOT NULL DEFAULT 1,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now')),
  updated_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Audit Log
-- ============================================================
CREATE TABLE IF NOT EXISTS audit_logs (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER REFERENCES users(id),
  action      TEXT    NOT NULL,              -- 'login','create','edit','delete'
  entity_type TEXT    NOT NULL,              -- 'report','schedule','user',etc.
  entity_id   INTEGER,
  ip_address  TEXT,
  details     TEXT,                          -- JSON payload for context
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- App Settings
-- ============================================================
CREATE TABLE IF NOT EXISTS settings (
  key         TEXT    PRIMARY KEY,
  value       TEXT    NOT NULL,
  updated_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Indexes
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_reports_date     ON reports(report_date);
CREATE INDEX IF NOT EXISTS idx_reports_user     ON reports(user_id);
CREATE INDEX IF NOT EXISTS idx_reports_turno    ON reports(turno);
CREATE INDEX IF NOT EXISTS idx_audit_user       ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_created    ON audit_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_schedules_date   ON loading_schedules(schedule_date);
CREATE UNIQUE INDEX IF NOT EXISTS idx_schedule_slot ON loading_schedules(schedule_date, time_slot, driver_id);
CREATE INDEX IF NOT EXISTS idx_report_items_report_id ON report_items(report_id);
CREATE INDEX IF NOT EXISTS idx_report_temperatures_report_id ON report_temperatures(report_id);
CREATE INDEX IF NOT EXISTS idx_report_photos_report_id ON report_photos(report_id);
CREATE INDEX IF NOT EXISTS idx_report_categories_parent ON report_categories(parent_category_id);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_id   ON auth_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_family_id ON auth_sessions(family_id);

-- Revision 5: the daily count of boxes per product. Additive by design: creating this table rewrites
-- nothing, and a row keeps the name and the boxes-per-box of the moment it was counted, so a change to
-- the catalogue in code cannot rewrite what an old count said.
CREATE TABLE IF NOT EXISTS stock_counts (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  count_date      TEXT NOT NULL,
  category        TEXT NOT NULL,
  product_code    TEXT NOT NULL,
  product_name    TEXT NOT NULL,
  units_per_box   INTEGER NOT NULL,
  boxes_total     INTEGER NOT NULL DEFAULT 0,
  units_total     INTEGER NOT NULL DEFAULT 0,
  entries_log     TEXT NOT NULL DEFAULT '[]',
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL,
  user_id         INTEGER REFERENCES users(id),
  UNIQUE(count_date, product_code)
);

-- Revision 6: the daily launches of massa boxes per product and user.
CREATE TABLE IF NOT EXISTS massa_launches (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  launch_date     TEXT NOT NULL,
  product_code    TEXT NOT NULL,
  boxes           INTEGER NOT NULL,
  user_id         INTEGER NOT NULL REFERENCES users(id),
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL,
  -- Revision 9: the ERP seat this launch was imported from. NULL for a launch typed by hand.
  ent_lan         INTEGER DEFAULT NULL
);
CREATE INDEX IF NOT EXISTS idx_massa_launches_date ON massa_launches(launch_date);
-- One launch per seat, many launches without one: the same seat imported twice is a duplicate.
CREATE UNIQUE INDEX IF NOT EXISTS uq_massa_launches_ent_lan
  ON massa_launches(ent_lan) WHERE ent_lan IS NOT NULL;

-- Revision 7: the company ERP's movement seats, mirrored so the plant reads one snapshot.
-- The ERP is the authority for ENT_LAN; this table never edits it, only copies what the bridge brought.
-- Revision 8 rekeys it: a purchase document is one ENT_LAN with one line per product, so the seat alone
-- is not unique and the product completes the key.
CREATE TABLE IF NOT EXISTS aero_movements (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  movement_date   TEXT NOT NULL,
  ent_lan         INTEGER NOT NULL,
  product_code    TEXT NOT NULL,
  product_desc    TEXT NOT NULL,
  quantity        REAL NOT NULL,
  entry_date      TEXT NOT NULL,
  operator_name   TEXT NOT NULL,
  movement_type   TEXT NOT NULL CHECK (movement_type IN ('E', 'S')),
  synced_at       TEXT NOT NULL,
  UNIQUE(movement_date, ent_lan, product_code)
);
CREATE INDEX IF NOT EXISTS idx_aero_movements_date ON aero_movements(movement_date);

