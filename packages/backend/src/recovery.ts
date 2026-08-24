import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import Database from 'better-sqlite3';

type Item = { path: string; size: number; sha256: string; inventoriedAt: string };
type Manifest = { setId: string; createdAt: string; sourceDatabase: Item; sourceSidecars: Item[]; snapshot: Item; assets: Item[] };
export type ExecutionResult = { code: number; stdout: string; stderr: string; timedOut: boolean };
type Runner = (file: string, args: readonly string[]) => Promise<ExecutionResult>;

const forbidden = /[;&|`$<>\n\r]/;
const digest = (path: string) => createHash('sha256').update(readFileSync(path)).digest('hex');
const item = (path: string, base?: string): Item => ({ path: base ? relative(base, path) || basename(path) : path, size: statSync(path).size, sha256: digest(path), inventoriedAt: new Date().toISOString() });

function canonicalProspective(path: string) {
  let parent = path;
  while (!existsSync(parent)) parent = dirname(parent);
  return resolve(realpathSync(parent), relative(parent, path));
}

function safePath(path: string, roots: string[], label: string) {
  if (!isAbsolute(path) || forbidden.test(path)) throw new Error(`${label} must be an absolute path without shell metacharacters`);
  const canonical = canonicalProspective(path);
  const allowed = roots.map(canonicalProspective).some((root) => canonical === root || canonical.startsWith(`${root}/`));
  if (!allowed) throw new Error(`${label} escapes the allowlisted roots`);
  return canonical;
}

function walkFiles(root: string): string[] {
  return readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const path = join(root, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Asset inventory rejects symlink: ${path}`);
    return entry.isDirectory() ? walkFiles(path) : [path];
  });
}

export async function executeChecked(runner: Runner, file: string, args: readonly string[], expected: string) {
  const result = await runner(file, args);
  if (result.timedOut) throw new Error(`${file} timed out`);
  if (result.code !== 0) throw new Error(`${file} exited with exit ${result.code}`);
  if (!result.stdout.includes(expected)) throw new Error(`${file} produced incomplete output`);
  return result.stdout;
}

export async function captureRecoverySet(input: { databasePath: string; assetsPath: string; outputDir: string; allowedRoots: string[] }) {
  const databasePath = safePath(input.databasePath, input.allowedRoots, 'databasePath');
  const assetsPath = safePath(input.assetsPath, input.allowedRoots, 'assetsPath');
  const outputDir = safePath(input.outputDir, input.allowedRoots, 'outputDir');
  if (outputDir === databasePath || outputDir.startsWith(`${dirname(databasePath)}/`)) throw new Error('Recovery output overlaps production data');
  if (!statSync(databasePath).isFile() || !statSync(assetsPath).isDirectory()) throw new Error('Recovery inventory is incomplete');
  const assetFiles = walkFiles(assetsPath);
  const assetInventory = assetFiles.map((path) => item(path, assetsPath));
  const sidecars = [`${databasePath}-wal`, `${databasePath}-shm`].filter(existsSync);
  const sidecarInventory = sidecars.map((path) => item(path));
  mkdirSync(outputDir, { recursive: false });
  const snapshotPath = join(outputDir, 'snapshot.db');
  const db = new Database(databasePath, { readonly: true, fileMustExist: true });
  try { await db.backup(snapshotPath); } catch (error) { rmSync(outputDir, { recursive: true, force: true }); throw error; } finally { db.close(); }
  cpSync(assetsPath, join(outputDir, 'assets'), { recursive: true, errorOnExist: true });
  for (const asset of assetInventory) {
    const copiedPath = join(outputDir, 'assets', asset.path);
    if (!existsSync(copiedPath) || statSync(copiedPath).size !== asset.size || digest(copiedPath) !== asset.sha256) {
      throw new Error(`Asset changed during capture: ${asset.path}`);
    }
  }
  for (const sidecar of sidecarInventory) {
    if (!existsSync(sidecar.path) || statSync(sidecar.path).size !== sidecar.size || digest(sidecar.path) !== sidecar.sha256) {
      throw new Error(`SQLite sidecar changed during capture: ${sidecar.path}`);
    }
  }
  const createdAt = new Date().toISOString();
  const manifest: Manifest = {
    setId: createHash('sha256').update(`${databasePath}:${createdAt}`).digest('hex'), createdAt,
    sourceDatabase: item(databasePath), sourceSidecars: sidecarInventory, snapshot: item(snapshotPath, outputDir), assets: assetInventory,
  };
  const manifestPath = join(outputDir, 'manifest.json');
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
  return manifestPath;
}

export function verifyIsolatedRestore(input: { manifestPath: string; targetDir: string; allowedRoots: string[] }) {
  const manifestPath = safePath(input.manifestPath, input.allowedRoots, 'manifestPath');
  const targetDir = safePath(input.targetDir, input.allowedRoots, 'targetDir');
  const setDir = dirname(manifestPath);
  if (targetDir === setDir || targetDir.startsWith(`${setDir}/`) || setDir.startsWith(`${targetDir}/`)) throw new Error('Restore target is not isolated');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')) as Manifest;
  if (digest(join(setDir, manifest.snapshot.path)) !== manifest.snapshot.sha256) throw new Error('Snapshot checksum mismatch');
  for (const asset of manifest.assets) if (digest(join(setDir, 'assets', asset.path)) !== asset.sha256) throw new Error(`Asset checksum mismatch: ${asset.path}`);
  mkdirSync(targetDir, { recursive: false });
  cpSync(join(setDir, manifest.snapshot.path), join(targetDir, 'restored.db'));
  cpSync(join(setDir, 'assets'), join(targetDir, 'assets'), { recursive: true });
  const db = new Database(join(targetDir, 'restored.db'), { readonly: true });
  const integrity = db.pragma('integrity_check', { simple: true }) as string;
  const tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").pluck().all() as string[];
  const rowCounts = Object.fromEntries(tables.map((table) => [table, db.prepare(`SELECT COUNT(*) FROM "${table.replaceAll('"', '""')}"`).pluck().get() as number]));
  db.close();
  if (integrity !== 'ok') throw new Error(`Restore integrity failed: ${integrity}`);
  return { setId: manifest.setId, verifiedAt: new Date().toISOString(), integrity, schemaIdentity: createHash('sha256').update(tables.join('\n')).digest('hex'), rowCounts, assetsVerified: manifest.assets.length };
}

async function runtimeScenario() {
  const root = mkdtempSync(join(tmpdir(), 'trindade-recovery-runtime-'));
  const source = join(root, 'source'); mkdirSync(join(source, 'photos'), { recursive: true }); writeFileSync(join(source, 'photos', 'probe.txt'), 'probe');
  const db = new Database(join(source, 'probe.db')); db.exec('CREATE TABLE probe (id INTEGER); INSERT INTO probe VALUES (1)'); db.close();
  const manifestPath = await captureRecoverySet({ databasePath: join(source, 'probe.db'), assetsPath: join(source, 'photos'), outputDir: join(root, 'set'), allowedRoots: [root] });
  const proof = verifyIsolatedRestore({ manifestPath, targetDir: join(root, 'restore'), allowedRoots: [root] });
  console.log(JSON.stringify({ scenario: 'verify-isolated', result: 'pass', ...proof })); rmSync(root, { recursive: true, force: true });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.length === 3 && process.argv[2] === '--verify-isolated') await runtimeScenario();
  else throw new Error('Usage: recovery.js --verify-isolated');
}
