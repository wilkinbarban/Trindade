import type Database from 'better-sqlite3';
import type {
  CreateCategoryBody,
  UpdateCategoryBody,
  CreateTaskBody,
  UpdateTaskBody,
  CreateDriverBody,
  UpdateDriverBody,
  CreateVehicleBody,
  UpdateVehicleBody,
  UpdateTimeSlotsBody,
  CategoryRow,
  TaskRow,
  DriverRow,
  VehicleRow,
  CreateUserBody,
  UpdateUserBody,
  UserRow,
} from './admin.schema.js';
import bcrypt from 'bcryptjs';
import { translateText } from '../../utils/translator.js';

async function translateOrCopy(text: string, fromLang: 'es' | 'pt'): Promise<string> {
  const cleaned = text.trim();
  if (!cleaned) return '';
  try {
    const translated = await translateText(cleaned, fromLang);
    return translated.trim() || cleaned;
  } catch {
    return cleaned;
  }
}

const DEFAULT_TIME_SLOTS = [
  '04:00',
  '04:30',
  '05:00',
  '05:30',
  '06:00',
  '06:30',
  '07:00',
];

// ============================================================
// Categories
// ============================================================

export function listCategories(db: Database.Database): CategoryRow[] {
  return db
    .prepare('SELECT * FROM report_categories ORDER BY COALESCE(parent_category_id, id), sort_order, id')
    .all() as CategoryRow[];
}

export async function createCategory(
  db: Database.Database,
  data: CreateCategoryBody
): Promise<CategoryRow> {
  let { name_pt, name_es } = data;
  if (name_pt && !name_es) {
    name_es = await translateOrCopy(name_pt, 'pt');
  } else if (name_es && !name_pt) {
    name_pt = await translateOrCopy(name_es, 'es');
  }

  const result = db
    .prepare(
      `INSERT INTO report_categories (parent_category_id, name_pt, name_es, category_type, sort_order)
       VALUES (?, ?, ?, ?, ?)`
    )
    .run(data.parent_category_id ?? null, name_pt || '', name_es || '', data.category_type ?? 'check', data.sort_order ?? 0);

  return db
    .prepare('SELECT * FROM report_categories WHERE id = ?')
    .get(result.lastInsertRowid as number) as CategoryRow;
}

export async function updateCategory(
  db: Database.Database,
  id: number,
  data: UpdateCategoryBody
): Promise<CategoryRow | null> {
  const existing = db
    .prepare('SELECT * FROM report_categories WHERE id = ?')
    .get(id) as CategoryRow | undefined;
  if (!existing) return null;

  if (data.parent_category_id !== undefined && data.parent_category_id !== null) {
    if (data.parent_category_id === id || !db.prepare('SELECT id FROM report_categories WHERE id = ?').get(data.parent_category_id)) {
      throw Object.assign(new Error('Categoría padre no encontrada o inválida.'), { statusCode: 400 });
    }
  }

  let name_pt = data.name_pt;
  let name_es = data.name_es;

  if (name_pt !== undefined && name_es === undefined && !existing.name_es) {
    name_es = await translateOrCopy(name_pt, 'pt');
  } else if (name_es !== undefined && name_pt === undefined && !existing.name_pt) {
    name_pt = await translateOrCopy(name_es, 'es');
  }

  const updates: string[] = [];
  const params: unknown[] = [];

  if (name_pt !== undefined) {
    updates.push('name_pt = ?');
    params.push(name_pt);
  }
  if (name_es !== undefined) {
    updates.push('name_es = ?');
    params.push(name_es);
  }
  if (data.parent_category_id !== undefined) {
    updates.push('parent_category_id = ?');
    params.push(data.parent_category_id);
  }
  if (data.category_type !== undefined) {
    updates.push('category_type = ?');
    params.push(data.category_type);
  }
  if (data.sort_order !== undefined) {
    updates.push('sort_order = ?');
    params.push(data.sort_order);
  }
  if (data.is_active !== undefined) {
    updates.push('is_active = ?');
    params.push(data.is_active);
  }

  if (updates.length === 0) return existing as CategoryRow;

  params.push(id);
  db.prepare(`UPDATE report_categories SET ${updates.join(', ')} WHERE id = ?`).run(...params);

  return db
    .prepare('SELECT * FROM report_categories WHERE id = ?')
    .get(id) as CategoryRow;
}

export function deleteCategory(db: Database.Database, id: number): boolean {
  const existing = db
    .prepare('SELECT id FROM report_categories WHERE id = ?')
    .get(id);
  if (!existing) return false;

  const deleteTasks = db.prepare('DELETE FROM report_tasks WHERE category_id = ?');
  const deleteCat = db.prepare('DELETE FROM report_categories WHERE id = ?');

  const execute = db.transaction(() => {
    deleteTasks.run(id);
    deleteCat.run(id);
  });

  try {
    execute();
  } catch (err: any) {
    if (err && err.code && err.code.startsWith('SQLITE_CONSTRAINT')) {
      throw Object.assign(new Error('Não é possível excluir a categoria pois ela ou suas tarefas possuem registros relacionados.'), { statusCode: 400 });
    }
    throw err;
  }
  return true;
}

// ============================================================
// Tasks
// ============================================================

export function listTasks(db: Database.Database): TaskRow[] {
  return db
    .prepare(
      `SELECT rt.*, rc.category_type AS task_type, rc.name_pt AS category_name
       FROM report_tasks rt
       JOIN report_categories rc ON rt.category_id = rc.id
       ORDER BY rc.sort_order ASC, rt.id ASC`
    )
    .all() as (TaskRow & { category_name: string })[];
}

export async function createTask(
  db: Database.Database,
  data: CreateTaskBody,
  createdByUserId: number | null = null
): Promise<TaskRow> {
  const category = db.prepare('SELECT category_type FROM report_categories WHERE id = ?').get(data.category_id) as { category_type: string } | undefined;
  if (!category) {
    throw Object.assign(new Error('Categoria não encontrada.'), { statusCode: 400 });
  }
  if (category.category_type !== 'temperature' && data.temperature_readings !== 1) {
    throw Object.assign(new Error('Apenas tarefas de temperatura podem ter mais de uma leitura.'), { statusCode: 400 });
  }
  let { name_pt, name_es } = data;
  if (name_pt && !name_es) {
    name_es = await translateOrCopy(name_pt, 'pt');
  } else if (name_es && !name_pt) {
    name_pt = await translateOrCopy(name_es, 'es');
  }

  let result: Database.RunResult;
  try {
    result = db
      .prepare(
        `INSERT INTO report_tasks (category_id, name_pt, name_es, temperature_readings, created_by_user_id)
         VALUES (?, ?, ?, ?, ?)`
      )
      .run(data.category_id, name_pt || '', name_es || '', data.temperature_readings, createdByUserId);
  } catch (err: any) {
    if (err && err.code && err.code.startsWith('SQLITE_CONSTRAINT')) {
      throw Object.assign(new Error('Violação de restrição de banco de dados (ID de categoria inválido ou duplicado).'), { statusCode: 400 });
    }
    throw err;
  }

  return db
    .prepare(
      `SELECT rt.*, rc.category_type AS task_type
       FROM report_tasks rt
       JOIN report_categories rc ON rt.category_id = rc.id
       WHERE rt.id = ?`
    )
    .get(result.lastInsertRowid as number) as TaskRow;
}

export async function updateTask(
  db: Database.Database,
  id: number,
  data: UpdateTaskBody
): Promise<TaskRow | null> {
  const existing = db
    .prepare('SELECT * FROM report_tasks WHERE id = ?')
    .get(id) as TaskRow | undefined;
  if (!existing) return null;

  const categoryId = data.category_id ?? existing.category_id;
  const category = db.prepare('SELECT category_type FROM report_categories WHERE id = ?').get(categoryId) as { category_type: string } | undefined;
  if (!category) {
    throw Object.assign(new Error('Categoria não encontrada.'), { statusCode: 400 });
  }
  const temperatureReadings = data.temperature_readings ?? existing.temperature_readings;
  if (category.category_type !== 'temperature' && temperatureReadings !== 1) {
    throw Object.assign(new Error('Apenas tarefas de temperatura podem ter mais de uma leitura.'), { statusCode: 400 });
  }

  let name_pt = data.name_pt;
  let name_es = data.name_es;

  if (name_pt !== undefined && name_es === undefined && !existing.name_es) {
    name_es = await translateOrCopy(name_pt, 'pt');
  } else if (name_es !== undefined && name_pt === undefined && !existing.name_pt) {
    name_pt = await translateOrCopy(name_es, 'es');
  }

  const updates: string[] = [];
  const params: unknown[] = [];

  if (data.category_id !== undefined) {
    updates.push('category_id = ?');
    params.push(data.category_id);
  }
  if (name_pt !== undefined) {
    updates.push('name_pt = ?');
    params.push(name_pt);
  }
  if (name_es !== undefined) {
    updates.push('name_es = ?');
    params.push(name_es);
  }
  if (data.is_active !== undefined) {
    updates.push('is_active = ?');
    params.push(data.is_active);
  }
  if (data.temperature_readings !== undefined) {
    updates.push('temperature_readings = ?');
    params.push(data.temperature_readings);
  }

  if (updates.length === 0) return existing as TaskRow;

  params.push(id);
  try {
    db.prepare(`UPDATE report_tasks SET ${updates.join(', ')} WHERE id = ?`).run(...params);
  } catch (err: any) {
    if (err && err.code && err.code.startsWith('SQLITE_CONSTRAINT')) {
      throw Object.assign(new Error('Violação de restrição de banco de dados (ID de categoria inválido ou duplicado).'), { statusCode: 400 });
    }
    throw err;
  }

  return db
    .prepare(
      `SELECT rt.*, rc.category_type AS task_type
       FROM report_tasks rt
       JOIN report_categories rc ON rt.category_id = rc.id
       WHERE rt.id = ?`
    )
    .get(id) as TaskRow;
}

export function deleteTask(db: Database.Database, id: number): boolean {
  const existing = db
    .prepare('SELECT id FROM report_tasks WHERE id = ?')
    .get(id);
  if (!existing) return false;

  try {
    db.prepare('DELETE FROM report_tasks WHERE id = ?').run(id);
  } catch (err: any) {
    if (err && err.code && err.code.startsWith('SQLITE_CONSTRAINT')) {
      throw Object.assign(new Error('Não é possível excluir a tarefa pois ela possui registros relacionados.'), { statusCode: 400 });
    }
    throw err;
  }
  return true;
}

// ============================================================
// Drivers
// ============================================================

export function listDrivers(db: Database.Database): DriverRow[] {
  return db
    .prepare('SELECT * FROM drivers ORDER BY name ASC')
    .all() as DriverRow[];
}

export function createDriver(
  db: Database.Database,
  data: CreateDriverBody,
  createdByUserId: number | null = null
): DriverRow {
  const result = db
    .prepare(
      `INSERT INTO drivers (name, license_plate, driver_type, created_by_user_id)
       VALUES (?, ?, ?, ?)`
    )
    .run(data.name, data.license_plate ?? null, data.driver_type ?? 'fletero', createdByUserId);

  return db
    .prepare('SELECT * FROM drivers WHERE id = ?')
    .get(result.lastInsertRowid as number) as DriverRow;
}

export function updateDriver(
  db: Database.Database,
  id: number,
  data: UpdateDriverBody
): DriverRow | null {
  const existing = db
    .prepare('SELECT * FROM drivers WHERE id = ?')
    .get(id) as DriverRow | undefined;
  if (!existing) return null;

  const updates: string[] = [];
  const params: unknown[] = [];

  if (data.name !== undefined) {
    updates.push('name = ?');
    params.push(data.name);
  }
  if (data.license_plate !== undefined) {
    updates.push('license_plate = ?');
    params.push(data.license_plate);
  }
  if (data.driver_type !== undefined) {
    updates.push('driver_type = ?');
    params.push(data.driver_type);
  }
  if (data.is_active !== undefined) {
    updates.push('is_active = ?');
    params.push(data.is_active);
  }

  if (updates.length === 0) return existing as DriverRow;

  params.push(id);
  db.prepare(`UPDATE drivers SET ${updates.join(', ')} WHERE id = ?`).run(...params);

  return db
    .prepare('SELECT * FROM drivers WHERE id = ?')
    .get(id) as DriverRow;
}

// ============================================================
// Vehicles
// ============================================================

export function listVehicles(db: Database.Database): VehicleRow[] {
  return db
    .prepare('SELECT * FROM vehicles ORDER BY description ASC')
    .all() as VehicleRow[];
}

export function createVehicle(
  db: Database.Database,
  data: CreateVehicleBody
): VehicleRow {
  const result = db
    .prepare(
      `INSERT INTO vehicles (description, license_plate)
       VALUES (?, ?)`
    )
    .run(data.description, data.license_plate);

  return db
    .prepare('SELECT * FROM vehicles WHERE id = ?')
    .get(result.lastInsertRowid as number) as VehicleRow;
}

export function updateVehicle(
  db: Database.Database,
  id: number,
  data: UpdateVehicleBody
): VehicleRow | null {
  const existing = db
    .prepare('SELECT * FROM vehicles WHERE id = ?')
    .get(id) as VehicleRow | undefined;
  if (!existing) return null;

  const updates: string[] = [];
  const params: unknown[] = [];

  if (data.description !== undefined) {
    updates.push('description = ?');
    params.push(data.description);
  }
  if (data.license_plate !== undefined) {
    updates.push('license_plate = ?');
    params.push(data.license_plate);
  }
  if (data.is_active !== undefined) {
    updates.push('is_active = ?');
    params.push(data.is_active);
  }

  if (updates.length === 0) return existing as VehicleRow;

  params.push(id);
  db.prepare(`UPDATE vehicles SET ${updates.join(', ')} WHERE id = ?`).run(...params);

  return db
    .prepare('SELECT * FROM vehicles WHERE id = ?')
    .get(id) as VehicleRow;
}

export function deleteVehicle(db: Database.Database, id: number): boolean {
  const existing = db
    .prepare('SELECT id FROM vehicles WHERE id = ?')
    .get(id);
  if (!existing) return false;

  try {
    db.prepare('DELETE FROM vehicles WHERE id = ?').run(id);
  } catch (err: any) {
    if (err && err.code && err.code.startsWith('SQLITE_CONSTRAINT')) {
      throw Object.assign(new Error('Não é possível excluir o veículo pois ele possui registros relacionados.'), { statusCode: 400 });
    }
    throw err;
  }
  return true;
}

// ============================================================
// Time Slots
// ============================================================

export function getTimeSlots(db: Database.Database): string[] {
  const row = db
    .prepare(`SELECT value FROM settings WHERE key = 'loading_time_slots'`)
    .get() as { value: string } | undefined;

  if (!row) return DEFAULT_TIME_SLOTS;

  try {
    const parsed = JSON.parse(row.value);
    if (Array.isArray(parsed)) return parsed as string[];
  } catch {
    // fall through
  }

  return DEFAULT_TIME_SLOTS;
}

export function updateTimeSlots(
  db: Database.Database,
  data: UpdateTimeSlotsBody
): string[] {
  const json = JSON.stringify(data.time_slots);

  db.prepare(
    `INSERT INTO settings (key, value, updated_at)
     VALUES ('loading_time_slots', ?, datetime('now'))
     ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`
  ).run(json);

  return data.time_slots;
}

// ============================================================
// Users
// ============================================================

export function listUsers(db: Database.Database): UserRow[] {
  return db
    .prepare(
      `SELECT u.id, u.username, u.display_name, u.role_id, r.name AS role_name, u.is_active, u.created_at
       FROM users u
       JOIN roles r ON u.role_id = r.id
       ORDER BY u.display_name ASC`
    )
    .all() as UserRow[];
}

export function createUser(
  db: Database.Database,
  data: CreateUserBody
): UserRow {
  const passwordHash = bcrypt.hashSync(data.password, 10);
  let result: Database.RunResult;
  try {
    result = db
      .prepare(
        `INSERT INTO users (username, password_hash, display_name, role_id, is_active)
         VALUES (?, ?, ?, ?, 1)`
      )
      .run(data.username, passwordHash, data.display_name, data.role_id);
  } catch (err: any) {
    if (err && err.code && err.code.startsWith('SQLITE_CONSTRAINT')) {
      throw Object.assign(new Error('Violação de restrição de banco de dados (Nome de usuário já existe ou role_id inválido).'), { statusCode: 400 });
    }
    throw err;
  }

  return db
    .prepare(
      `SELECT u.id, u.username, u.display_name, u.role_id, r.name AS role_name, u.is_active, u.created_at
       FROM users u
       JOIN roles r ON u.role_id = r.id
       WHERE u.id = ?`
    )
    .get(result.lastInsertRowid as number) as UserRow;
}

export function updateUser(
  db: Database.Database,
  id: number,
  data: UpdateUserBody
): UserRow | null {
  const existing = db
    .prepare('SELECT * FROM users WHERE id = ?')
    .get(id) as UserRow | undefined;
  if (!existing) return null;

  const updates: string[] = [];
  const params: unknown[] = [];

  if (data.username !== undefined) {
    updates.push('username = ?');
    params.push(data.username);
  }
  if (data.password !== undefined) {
    const passwordHash = bcrypt.hashSync(data.password, 10);
    updates.push('password_hash = ?');
    params.push(passwordHash);
  }
  if (data.display_name !== undefined) {
    updates.push('display_name = ?');
    params.push(data.display_name);
  }
  if (data.role_id !== undefined) {
    updates.push('role_id = ?');
    params.push(data.role_id);
  }
  if (data.is_active !== undefined) {
    updates.push('is_active = ?');
    params.push(data.is_active);
  }

  if (updates.length > 0) {
    updates.push("updated_at = datetime('now')");
    params.push(id);
    try {
      db.prepare(`UPDATE users SET ${updates.join(', ')} WHERE id = ?`).run(...params);
    } catch (err: any) {
      if (err && err.code && err.code.startsWith('SQLITE_CONSTRAINT')) {
        throw Object.assign(new Error('Violação de restrição de banco de dados (Nome de usuário já existe ou role_id inválido).'), { statusCode: 400 });
      }
      throw err;
    }
  }

  return db
    .prepare(
      `SELECT u.id, u.username, u.display_name, u.role_id, r.name AS role_name, u.is_active, u.created_at
       FROM users u
       JOIN roles r ON u.role_id = r.id
       WHERE u.id = ?`
    )
    .get(id) as UserRow;
}

export function deleteUser(db: Database.Database, id: number): boolean {
  const existing = db
    .prepare('SELECT id FROM users WHERE id = ?')
    .get(id);
  if (!existing) return false;

  try {
    const deleteTransaction = db.transaction((userId: number) => {
      db.prepare('UPDATE audit_logs SET user_id = NULL WHERE user_id = ?').run(userId);
      db.prepare('UPDATE loading_schedules SET user_id = NULL WHERE user_id = ?').run(userId);
      db.prepare('UPDATE report_tasks SET created_by_user_id = NULL WHERE created_by_user_id = ?').run(userId);
      db.prepare('UPDATE drivers SET created_by_user_id = NULL WHERE created_by_user_id = ?').run(userId);
      db.prepare('DELETE FROM users WHERE id = ?').run(userId);
    });

    deleteTransaction(id);
  } catch (err: any) {
    if (err && err.code && err.code.startsWith('SQLITE_CONSTRAINT')) {
      throw Object.assign(new Error('Não é possível excluir o usuário pois ele possui registros relacionados.'), { statusCode: 400 });
    }
    throw err;
  }
  return true;
}
