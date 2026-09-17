import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { buildOpenApiDocument } from './openapi.js';

/**
 * Write the generated contract to disk.
 *
 * Usage: npm run contracts:generate --workspace=packages/backend
 *        tsx src/contracts/generate.ts <output-path>
 *
 * The artifact is committed, and CI regenerates it and fails on any difference, so a
 * schema change that was not regenerated cannot be merged.
 */
const target = resolve(process.argv[2] ?? '../contracts/openapi.json');

mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, `${JSON.stringify(buildOpenApiDocument(), null, 2)}\n`, 'utf8');

console.log(`contracts: wrote ${target}`);
