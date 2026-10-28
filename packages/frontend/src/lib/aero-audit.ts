import { api } from '../api/client';

/**
 * One line of the company integration's audit trail.
 *
 * The vocabulary is the server's: it validates this body against a strict schema and stamps the user
 * from the verified token, so nothing here can claim an identity. Everything is optional except the
 * event and the destination because a failed attempt is still worth recording, and a failed attempt
 * often knows nothing but where it was going.
 */
export interface AeroAuditEvent {
  event: 'test_connection' | 'launch_sync' | 'read_history';
  phase?: 'requested' | 'result';
  destinationUrl: string;
  httpStatus?: number | null;
  payload?: unknown;
  response?: string | null;
  error?: string | null;
  launchId?: number | null;
  note?: string | null;
}

/**
 * Sends the event and reports whether the server kept it.
 *
 * Never throws: the audit trail describes the operation, it is not part of it. A launch that reached
 * the company ERP and then failed to be logged must still read as a successful launch to the operator.
 */
export async function postAeroAuditEvent(event: AeroAuditEvent): Promise<boolean> {
  try {
    await api.post('/integration/aero/log', event);
    return true;
  } catch {
    return false;
  }
}
