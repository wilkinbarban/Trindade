import { appendFileSync, existsSync, mkdirSync, renameSync, rmSync, statSync } from 'node:fs';
import { join } from 'node:path';

export const AERO_LOG_FILE = 'aero-integration.log';
export const AERO_LOG_MAX_BYTES = 2 * 1024 * 1024;
const TRUNCATION_MARKER = '…[truncated]';
const PAYLOAD_MAX_CHARS = 4096;
const RESPONSE_MAX_CHARS = 2048;

/**
 * Structured log entry for Aero32 WebService integration events.
 */
export interface AeroLogEntry {
  ts: string;
  event: string;
  phase: string | null;
  user: string;
  role: string;
  destinationUrl: string;
  httpStatus: number | null;
  payload: unknown;
  response: string | null;
  error: string | null;
  launchId: number | null;
  note: string | null;
}

/**
 * Resolves the directory where the Aero integration audit log is stored.
 * Honors AERO_LOG_DIR from the environment when set and non-empty, falling back
 * to the `logs` folder under the process current working directory.
 */
export function resolveAeroLogDir(env?: NodeJS.ProcessEnv): string {
  const currentEnv = env ?? process.env;
  const custom = currentEnv.AERO_LOG_DIR?.trim();
  if (custom && custom.length > 0) {
    return custom;
  }
  return join(process.cwd(), 'logs');
}

/**
 * Recursively removes authorization and credential fields from payloads
 * so passwords, basic auth tokens, and secret keys never leak to disk.
 */
function sanitizePayloadData(data: unknown): unknown {
  if (data === null || data === undefined) {
    return data;
  }
  if (typeof data !== 'object') {
    return data;
  }
  if (Array.isArray(data)) {
    return data.map(sanitizePayloadData);
  }
  const sanitized: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(data as Record<string, unknown>)) {
    const lower = key.toLowerCase();
    if (
      lower === 'authorization' ||
      lower.includes('password') ||
      lower === 'senha' ||
      lower.includes('credential')
    ) {
      continue;
    }
    sanitized[key] = sanitizePayloadData(value);
  }
  return sanitized;
}

/**
 * Appends one JSON line to the Aero integration log file.
 * Handles directory creation, single-generation size rotation at 2MB,
 * and length truncation for payload and response fields.
 */
export function appendAeroLog(entry: AeroLogEntry, dir?: string): string {
  const logDir = dir ?? resolveAeroLogDir();
  mkdirSync(logDir, { recursive: true });

  const filePath = join(logDir, AERO_LOG_FILE);
  const rotatedPath = `${filePath}.1`;

  if (existsSync(filePath)) {
    try {
      const stats = statSync(filePath);
      if (stats.size >= AERO_LOG_MAX_BYTES) {
        if (existsSync(rotatedPath)) {
          rmSync(rotatedPath, { force: true });
        }
        renameSync(filePath, rotatedPath);
      }
    } catch {
      // Rotation failure should not prevent logging the current event
    }
  }

  // Sanitize and cap payload
  let finalPayload = sanitizePayloadData(entry.payload);
  if (finalPayload !== undefined && finalPayload !== null) {
    if (typeof finalPayload === 'string') {
      if (finalPayload.length > PAYLOAD_MAX_CHARS) {
        finalPayload = finalPayload.slice(0, PAYLOAD_MAX_CHARS) + TRUNCATION_MARKER;
      }
    } else {
      const serialized = JSON.stringify(finalPayload);
      if (serialized && serialized.length > PAYLOAD_MAX_CHARS) {
        finalPayload = serialized.slice(0, PAYLOAD_MAX_CHARS) + TRUNCATION_MARKER;
      }
    }
  } else {
    finalPayload = null;
  }

  // Cap response
  let finalResponse = entry.response ?? null;
  if (finalResponse && finalResponse.length > RESPONSE_MAX_CHARS) {
    finalResponse = finalResponse.slice(0, RESPONSE_MAX_CHARS) + TRUNCATION_MARKER;
  }

  const record: AeroLogEntry = {
    ts: entry.ts,
    event: entry.event,
    phase: entry.phase ?? null,
    user: entry.user,
    role: entry.role,
    destinationUrl: entry.destinationUrl,
    httpStatus: entry.httpStatus ?? null,
    payload: finalPayload,
    response: finalResponse,
    error: entry.error ?? null,
    launchId: entry.launchId ?? null,
    note: entry.note ?? null,
  };

  const line = JSON.stringify(record) + '\n';
  appendFileSync(filePath, line, 'utf-8');

  return filePath;
}
