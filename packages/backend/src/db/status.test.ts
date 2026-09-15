import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, it } from 'node:test';
import Database from 'better-sqlite3';
import { openDatabase } from './index.js';

const statusScript = join(import.meta.dirname, 'status.ts');
const schema = readFileSync(join(import.meta.dirname, 'schema.sql'), 'utf8');
const seed = readFileSync(join(import.meta.dirname, 'seed.sql'), 'utf8');
const digest = (path: string) => createHash('sha256').update(readFileSync(path)).digest('hex');

function runStatus(databasePath: string): Promise<{ code: number | null | 'timeout'; output: string }> {
  const { NODE_TEST_CONTEXT: _nodeTestContext, ...env } = process.env;
  const child = spawn(process.execPath, ['--import', 'tsx', statusScript, databasePath], {
    cwd: join(import.meta.dirname, '..'),
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', (chunk) => { output += chunk; });
  child.stderr.on('data', (chunk) => { output += chunk; });
  return new Promise((resolve) => {
    const timer = setTimeout(() => { child.kill(); resolve({ code: 'timeout', output }); }, 30_000);
    child.once('exit', (code) => { clearTimeout(timer); resolve({ code, output }); });
  });
}

describe('schema status command', () => {
  const roots: string[] = [];
  const fixtureRoot = () => {
    const root = mkdtempSync(join(tmpdir(), 'trindade-schema-status-'));
    roots.push(root);
    return root;
  };

  afterEach(() => roots.splice(0).forEach((root) => rmSync(root, { recursive: true, force: true })));

  it('reports a fresh installation as current and exits zero', async () => {
    const databasePath = join(fixtureRoot(), 'fresh.db');
    openDatabase(databasePath).close();

    const { code, output } = await runStatus(databasePath);
    assert.equal(code, 0, output);
    assert.match(output, /integrity: ok/);
    assert.match(output, /schema revision: 1 \(this build supports 1\)/);
    assert.match(output, /verdict: current/);
  });

  it('reports an unversioned database without writing to it', async () => {
    const databasePath = join(fixtureRoot(), 'legacy.db');
    const legacy = new Database(databasePath);
    legacy.exec(schema);
    legacy.exec(seed);
    legacy.close();
    const before = digest(databasePath);

    const { code, output } = await runStatus(databasePath);
    assert.equal(code, 1, output);
    assert.match(output, /schema revision: 0 \(this build supports 1\)/);
    assert.match(output, /tables: 14 observed, 0 missing, 0 unexpected/);
    assert.match(output, /verdict: unversioned/);
    assert.equal(digest(databasePath), before, 'the status command modified the database');

    const after = new Database(databasePath, { readonly: true });
    assert.equal(after.pragma('user_version', { simple: true }), 0);
    after.close();
  });

  it('refuses a missing database instead of creating one', async () => {
    const databasePath = join(fixtureRoot(), 'absent.db');

    const { code, output } = await runStatus(databasePath);
    assert.equal(code, 1, output);
    assert.match(output, /no database at/);
    assert.equal(existsSync(databasePath), false, 'the status command created a database');
  });
});
