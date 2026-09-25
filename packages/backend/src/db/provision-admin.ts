import Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { setupSchema } from '../modules/auth/auth.schema.js';
import { readSchemaReport } from './schema-version.js';

const MAX_PASSWORD_BYTES = 4096;

/** Read one bounded password line from stdin without echoing or retaining trailing input. */
export async function readPassword(input: NodeJS.ReadableStream): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    let foundNewline = false;
    const fail = (message: string) => {
      cleanup();
      reject(new Error(message));
    };
    const cleanup = () => {
      input.removeListener('data', onData);
      input.removeListener('end', onEnd);
      input.removeListener('error', onError);
    };
    const onError = () => fail('Cannot read password from stdin');
    const onEnd = () => {
      if (!foundNewline) return fail('Password must end with a newline');
      const password = Buffer.concat(chunks).toString('utf8').replace(/\r$/, '');
      cleanup();
      resolve(password);
    };
    const onData = (chunk: Buffer | string) => {
      const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      if (foundNewline) return fail('Password input must contain one line');
      const newline = bytes.indexOf(10);
      const part = newline < 0 ? bytes : bytes.subarray(0, newline);
      size += part.length;
      if (size > MAX_PASSWORD_BYTES) return fail('Password input exceeds limit');
      chunks.push(part);
      if (newline >= 0) {
        if (newline !== bytes.length - 1) return fail('Password input must contain one line');
        foundNewline = true;
      }
    };
    input.on('data', onData);
    input.once('end', onEnd);
    input.once('error', onError);
  });
}

/** Refuse an existing or obsolete database before accepting a password. */
export function checkProvisioningDatabase(path: string): void {
  if (!existsSync(path)) throw new Error('Database does not exist');
  const db = new Database(path, { readonly: true, fileMustExist: true });
  try {
    if (readSchemaReport(db).verdict !== 'current') throw new Error('Database schema is not current');
    if ((db.prepare('SELECT COUNT(*) FROM users').pluck().get() as number) !== 0)
      throw new Error('Administrator provisioning requires an empty users table');
  } finally {
    db.close();
  }
}

export async function provisionAdministrator(path: string, username: string, displayName: string, password: string): Promise<number> {
  checkProvisioningDatabase(path);
  const parsed = setupSchema.safeParse({ username, displayName, password });
  if (!parsed.success) throw new Error('Invalid administrator fields or password');
  const hash = await bcrypt.hash(parsed.data.password, 10);
  const db = new Database(path, { fileMustExist: true });
  try {
    const create = db.transaction(() => {
      if (readSchemaReport(db).verdict !== 'current') throw new Error('Database schema is not current');
      if ((db.prepare('SELECT COUNT(*) FROM users').pluck().get() as number) !== 0)
        throw new Error('Administrator provisioning requires an empty users table');
      const role = db.prepare("SELECT id FROM roles WHERE name = 'Administrador'").get() as { id: number } | undefined;
      if (!role) throw new Error('Administrator role is missing');
      const result = db.prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, ?, 1)')
        .run(parsed.data.username, hash, parsed.data.displayName, role.id);
      return Number(result.lastInsertRowid);
    });
    return create.immediate();
  } finally {
    db.close();
  }
}

async function main(): Promise<void> {
  // Only non-secret identifiers and an explicit existing database path are accepted.
  if (process.argv.length !== 5) throw new Error('Usage: provision-admin <database-path> <username> <display-name> (password on stdin)');
  const [, , path, username, displayName] = process.argv;
  checkProvisioningDatabase(path);
  if (process.stdin.isTTY) throw new Error('Pipe one password line into stdin; terminal echo is not disabled');
  const password = await readPassword(process.stdin);
  await provisionAdministrator(path, username, displayName, password);
  console.log('Administrator provisioned');
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((error: Error) => {
    // Never include a database/SQLite error or supplied input in diagnostics.
    console.error(`Provisioning refused: ${error.message.startsWith('Usage:') ? error.message : 'check input, schema and empty users table'}`);
    process.exitCode = 1;
  });
}
