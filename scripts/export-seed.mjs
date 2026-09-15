import Database from 'better-sqlite3';

const db = new Database('/app/packages/backend/data/trindade.db', { readonly: true });
const q = (value) => `'${String(value ?? '').replaceAll("'", "''")}'`;
const rows = (sql) => db.prepare(sql).all();
const lines = [
  '-- Generated from the current Trindade reference data.',
  '-- Runtime reports, schedules, photos and audit logs are intentionally excluded.',
  '',
];

for (const row of rows('SELECT id, name FROM roles ORDER BY id')) {
  lines.push(`INSERT OR IGNORE INTO roles (id, name) VALUES (${row.id}, ${q(row.name)});`);
}
lines.push('');

for (const row of rows('SELECT id, username, password_hash, display_name, role_id, is_active FROM users ORDER BY id')) {
  lines.push(`INSERT OR IGNORE INTO users (id, username, password_hash, display_name, role_id, is_active) VALUES (${row.id}, ${q(row.username)}, ${q(row.password_hash)}, ${q(row.display_name)}, ${row.role_id}, ${row.is_active});`);
}
lines.push('');

for (const row of rows('SELECT key, value FROM settings ORDER BY key')) {
  lines.push(`INSERT OR IGNORE INTO settings (key, value) VALUES (${q(row.key)}, ${q(row.value)});`);
}
lines.push('');

for (const row of rows('SELECT id, parent_category_id, name_pt, name_es, category_type, sort_order, is_active FROM report_categories ORDER BY id')) {
  const parent = row.parent_category_id === null ? 'NULL' : row.parent_category_id;
  lines.push(`INSERT OR IGNORE INTO report_categories (id, parent_category_id, name_pt, name_es, category_type, sort_order, is_active) VALUES (${row.id}, ${parent}, ${q(row.name_pt)}, ${q(row.name_es)}, ${q(row.category_type)}, ${row.sort_order}, ${row.is_active});`);
}
lines.push('');

for (const row of rows('SELECT id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id FROM report_tasks ORDER BY id')) {
  const creator = row.created_by_user_id === null ? 'NULL' : row.created_by_user_id;
  lines.push(`INSERT OR IGNORE INTO report_tasks (id, category_id, name_pt, name_es, temperature_readings, is_active, created_by_user_id) VALUES (${row.id}, ${row.category_id}, ${q(row.name_pt)}, ${q(row.name_es)}, ${row.temperature_readings}, ${row.is_active}, ${creator});`);
}
lines.push('');

for (const row of rows('SELECT id, name, license_plate, driver_type, is_active, created_by_user_id FROM drivers ORDER BY id')) {
  const plate = row.license_plate === null ? 'NULL' : q(row.license_plate);
  const creator = row.created_by_user_id === null ? 'NULL' : row.created_by_user_id;
  lines.push(`INSERT OR IGNORE INTO drivers (id, name, license_plate, driver_type, is_active, created_by_user_id) VALUES (${row.id}, ${q(row.name)}, ${plate}, ${q(row.driver_type)}, ${row.is_active}, ${creator});`);
}
lines.push('');

for (const row of rows('SELECT id, description, license_plate, is_active FROM vehicles ORDER BY id')) {
  lines.push(`INSERT OR IGNORE INTO vehicles (id, description, license_plate, is_active) VALUES (${row.id}, ${q(row.description)}, ${q(row.license_plate)}, ${row.is_active});`);
}

console.log(lines.join('\n'));
db.close();
