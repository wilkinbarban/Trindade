import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';
import type { z } from 'zod';
import {
  DriverResponseSchema,
  DriversResponseSchema,
  ScheduleHistoryResponseSchema,
  ScheduleResponseSchema,
  SchedulesResponseSchema,
  TimeSlotsResponseSchema,
  VehiclesResponseSchema,
} from '../modules/loading/loading.schema.js';
import { buildTestApp, type TestFixtures } from '../test-helper.js';
import { SuccessResponseSchema, TextResponseSchema } from './common.schema.js';
import { ErrorEnvelopeSchema } from './error.schema.js';

const CREATE_DATE = '2026-03-15';
const BATCH_DATE = '2026-03-16';

function expectMatch<T extends z.ZodTypeAny>(schema: T, body: string): z.infer<T> {
  const parsed = schema.safeParse(JSON.parse(body));
  assert.ok(parsed.success, `response does not match its schema: ${JSON.stringify(parsed.error?.issues)}`);
  return parsed.data;
}

describe('loading responses match their declared schemas', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let fixtures: TestFixtures;
  let auth: { authorization: string };

  /** Schedule a driver on a date, returning the created id. */
  async function createSchedule(date: string, timeSlot: string, driverId: number, driverType = 'fletero') {
    const res = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: auth,
      payload: { schedule_date: date, time_slot: timeSlot, driver_type: driverType, driver_id: driverId },
    });
    assert.strictEqual(res.statusCode, 201, res.body);
    return JSON.parse(res.body).schedule as { id: number };
  }

  // The same active driver cannot appear twice on one date, so every test below draws from a
  // disjoint slice of the driver list. Reusing an id across tests produced a 409 that looked
  // like a contract failure and was really a fixture collision.
  function fleteroIds(count: number, offset = 0): number[] {
    return (
      db
        .prepare(
          "SELECT id FROM drivers WHERE driver_type = 'fletero' AND is_active = 1 ORDER BY id LIMIT ? OFFSET ?",
        )
        .pluck()
        .all(count, offset) as number[]
    );
  }

  before(async () => {
    const result = await buildTestApp();
    app = result.app;
    db = result.db;
    fixtures = result.fixtures;

    const login = await app.inject({ method: 'POST', url: '/api/auth/login', payload: fixtures.admin });
    assert.strictEqual(login.statusCode, 200, login.body);
    auth = { authorization: `Bearer ${JSON.parse(login.body).token}` };
  });

  after(async () => {
    await app.close();
    db.close();
  });

  it('returns the declared time slots, drivers and vehicles', async () => {
    const slots = await app.inject({ method: 'GET', url: '/api/loading/time-slots', headers: auth });
    assert.strictEqual(slots.statusCode, 200, slots.body);
    const slotBody = expectMatch(TimeSlotsResponseSchema, slots.body) as { timeSlots: string[] };
    assert.ok(slotBody.timeSlots.length > 0, 'the configured slots came back empty');

    const drivers = await app.inject({ method: 'GET', url: '/api/loading/drivers', headers: auth });
    assert.strictEqual(drivers.statusCode, 200, drivers.body);
    expectMatch(DriversResponseSchema, drivers.body);

    const vehicles = await app.inject({ method: 'GET', url: '/api/loading/vehicles', headers: auth });
    assert.strictEqual(vehicles.statusCode, 200, vehicles.body);
    expectMatch(VehiclesResponseSchema, vehicles.body);
  });

  it('quick-adds an external driver with the declared shape', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/api/loading/drivers',
      headers: auth,
      payload: { name: `Contrato ${Date.now()}`, license_plate: 'QAA1B23' },
    });
    assert.strictEqual(res.statusCode, 201, res.body);

    const body = expectMatch(DriverResponseSchema, res.body) as { driver: { driver_type: string } };
    assert.strictEqual(body.driver.driver_type, 'fletero', 'quick-add must always create a fletero');
  });

  // This is the drift B2.2 fixed. `create` and `update` used to return the raw database row
  // while `listByDate` returned a projection, so the same declared type described three
  // different runtime objects. Comparing the two responses field by field is what stops that
  // from coming back, because a `.strict()` schema alone would accept either shape.
  it('creates a schedule that is field-for-field identical to the listed one', async () => {
    const [driverId] = fleteroIds(1);
    const created = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: auth,
      payload: { schedule_date: CREATE_DATE, time_slot: '04:00', driver_type: 'fletero', driver_id: driverId },
    });
    assert.strictEqual(created.statusCode, 201, created.body);
    const createdSchedule = expectMatch(ScheduleResponseSchema, created.body) as { schedule: { id: number } };

    const listed = await app.inject({
      method: 'GET',
      url: `/api/loading/schedules?date=${CREATE_DATE}`,
      headers: auth,
    });
    assert.strictEqual(listed.statusCode, 200, listed.body);
    const schedules = expectMatch(SchedulesResponseSchema, listed.body) as { schedules: { id: number }[] };

    const listedRow = schedules.schedules.find((row) => row.id === createdSchedule.schedule.id);
    assert.ok(listedRow, 'the created schedule is missing from the date listing');
    assert.deepStrictEqual(
      listedRow,
      createdSchedule.schedule,
      'a created schedule and a listed schedule are not the same shape',
    );
  });

  // The four-fletero rule is informative by an explicit business decision. The server must
  // therefore accept the fourth assignment, and the contract documents that it does.
  it('accepts a fourth fletero in the same slot', async () => {
    const driverIds = fleteroIds(4, 1);
    for (const driverId of driverIds) {
      await createSchedule(CREATE_DATE, '05:00', driverId);
    }
    const listed = await app.inject({
      method: 'GET',
      url: `/api/loading/schedules?date=${CREATE_DATE}`,
      headers: auth,
    });
    const schedules = expectMatch(SchedulesResponseSchema, listed.body) as {
      schedules: { time_slot: string; driver_type: string }[];
    };
    const atFive = schedules.schedules.filter((row) => row.time_slot === '05:00' && row.driver_type === 'fletero');
    assert.strictEqual(atFive.length, 4, 'the fourth fletero was rejected or lost');
  });

  it('updates a schedule and returns the projected shape', async () => {
    const [driverId] = fleteroIds(1, 5);
    const created = await createSchedule(BATCH_DATE, '04:00', driverId);

    const updated = await app.inject({
      method: 'PATCH',
      url: `/api/loading/schedules/${created.id}`,
      headers: auth,
      payload: { time_slot: '06:00' },
    });
    assert.strictEqual(updated.statusCode, 200, updated.body);
    const updatedSchedule = expectMatch(ScheduleResponseSchema, updated.body) as {
      schedule: { id: number; time_slot: string };
    };
    assert.strictEqual(updatedSchedule.schedule.time_slot, '06:00');

    const listed = await app.inject({
      method: 'GET',
      url: `/api/loading/schedules?date=${BATCH_DATE}`,
      headers: auth,
    });
    const schedules = expectMatch(SchedulesResponseSchema, listed.body) as { schedules: { id: number }[] };
    const listedRow = schedules.schedules.find((row) => row.id === created.id);
    assert.deepStrictEqual(
      listedRow,
      updatedSchedule.schedule,
      'an updated schedule and a listed schedule are not the same shape',
    );
  });

  it('lists loading history with the declared shape', async () => {
    const res = await app.inject({ method: 'GET', url: '/api/loading/schedules/history', headers: auth });
    assert.strictEqual(res.statusCode, 200, res.body);
    const body = expectMatch(ScheduleHistoryResponseSchema, res.body) as {
      items: { batch_date: string }[];
      pagination: { total: number };
    };
    assert.ok(body.items.length > 0, 'history returned no batch for the dates created above');
    assert.ok(body.pagination.total >= body.items.length);
  });

  it('renders the WhatsApp text on the server', async () => {
    const res = await app.inject({ method: 'GET', url: `/api/loading/export?date=${BATCH_DATE}`, headers: auth });
    assert.strictEqual(res.statusCode, 200, res.body);

    const body = expectMatch(TextResponseSchema, res.body) as { text: string };
    assert.ok(body.text.length > 0, 'the export produced no text');
    assert.ok(!/undefined/.test(body.text), 'the export leaked an undefined value into client-facing text');
  });

  it('returns the declared error envelope for a rejected assignment', async () => {
    const [driverId] = fleteroIds(1, 6);
    const created = await createSchedule(BATCH_DATE, '07:00', driverId);

    // The same driver cannot appear twice on one date.
    const duplicate = await app.inject({
      method: 'POST',
      url: '/api/loading/schedules',
      headers: auth,
      payload: { schedule_date: BATCH_DATE, time_slot: '06:30', driver_type: 'fletero', driver_id: driverId },
    });
    assert.ok(duplicate.statusCode >= 400, `expected a rejection, got ${duplicate.statusCode}: ${duplicate.body}`);
    const envelope = expectMatch(ErrorEnvelopeSchema, duplicate.body) as { error: string; message?: string };
    assert.ok(envelope.error.length > 0);
    assert.ok(envelope.message, 'the rejected assignment did not echo a human-readable message');

    const deleted = await app.inject({
      method: 'DELETE',
      url: `/api/loading/schedules/${created.id}`,
      headers: auth,
    });
    assert.strictEqual(deleted.statusCode, 204, deleted.body);
    assert.strictEqual(deleted.body, '', 'a 204 answer must carry no body');
  });

  it('deactivates a whole batch with the declared success envelope', async () => {
    const res = await app.inject({
      method: 'PATCH',
      url: `/api/loading/schedules/batch/${BATCH_DATE}/deactivate`,
      headers: auth,
    });
    assert.strictEqual(res.statusCode, 200, res.body);
    expectMatch(SuccessResponseSchema, res.body);
  });
});
