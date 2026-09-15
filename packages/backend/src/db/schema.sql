-- ============================================================
-- Trindade Massas Operações — Database Schema
-- 14 user tables: the approved schema after the admin-refactor-loading-rules change
-- dropped report_products and report_quantities, whose data report_items carries in
-- selected_products as JSON. The PRD listed 16 tables; the refactor superseded two.
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
  created_at     TEXT    NOT NULL DEFAULT (datetime('now')),
  updated_at     TEXT    NOT NULL DEFAULT (datetime('now'))
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
