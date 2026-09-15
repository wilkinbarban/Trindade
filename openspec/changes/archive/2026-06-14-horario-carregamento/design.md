# Design: Horário de Carregamento

## Technical Approach

Implement the scheduling module focusing on the core business logic of assigning drivers (Fleteros) to time slots while enforcing a strict 3-fletero limit per slot. This will be built as a new `loading` module in the Fastify backend and a new `LoadingSchedulePage` on the React frontend. We use existing SQLite tables (`loading_schedules`, `drivers`) and enforce the 3-fletero limit through a count validation in the service layer prior to database insertion.

## Architecture Decisions

### Decision: Enforcing max 3 Fleteros per time slot

**Choice**: Validate via a count query in the service layer before `INSERT` (business logic).
**Alternatives considered**: Add a complex SQLite trigger or CHECK constraint with subquery.
**Rationale**: SQLite has limited support for conditional uniqueness across counts. Service layer validation is simpler, easier to test, and maps directly to the specific `driver_type = 'fletero'` requirement without requiring schema changes.

### Decision: Driver creation scope

**Choice**: Implement a minimal "quick-add" for Fleteros (name and optional plate) without full CRUD UI.
**Alternatives considered**: Build a full fleet management admin panel.
**Rationale**: Keeps scope bounded within the 400-line PR budget limit and meets the immediate requirement to schedule external drivers. Fleet vehicle (Casa) CRUD is deferred.

### Decision: WhatsApp Export Generation

**Choice**: Generate the formatted text in the backend (`export.service.ts`).
**Alternatives considered**: Format the text on the frontend based on API JSON responses.
**Rationale**: Matches the existing pattern from the reports module, centralizing localization (always pt-BR for exports) and format logic in one place.

## Data Flow

    [Frontend: LoadingSchedulePage]
         │ (GET/POST/DELETE)
         ▼
    [Fastify: loading.routes.ts] ──(Validate input)──→ [Zod schemas]
         │
         ▼
    [Service: loading.service.ts] ──(Check quota)──→ [SQLite: count(fleteros)]
         │
         ▼ (Insert/Delete)
    [SQLite: loading_schedules]

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `packages/backend/src/modules/loading/loading.schema.ts` | Create | Zod validations for endpoints (schedule, drivers). |
| `packages/backend/src/modules/loading/loading.service.ts` | Create | CRUD logic and 3-fletero quota check. |
| `packages/backend/src/modules/loading/loading.export.service.ts` | Create | WhatsApp format generator (pt-BR). |
| `packages/backend/src/modules/loading/loading.routes.ts` | Create | Fastify endpoints (`/api/loading/...`). |
| `packages/backend/src/modules/loading/loading.routes.test.ts` | Create | Integration tests for the new module. |
| `packages/backend/src/server.ts` | Modify | Register `/api/loading` routes. |
| `packages/frontend/src/pages/LoadingSchedulePage.tsx` | Create | Date picker and time slot UI container. |
| `packages/frontend/src/components/loading/ScheduleGrid.tsx` | Create | Displays time slots, assigned drivers, and quota UI. |
| `packages/frontend/src/components/loading/DriverSelect.tsx` | Create | Dropdown to assign drivers to slots. |
| `packages/frontend/src/components/loading/DriverForm.tsx` | Create | Quick-add form for Fleteros. |
| `packages/frontend/src/components/loading/ScheduleExportView.tsx` | Create | Dialog showing WhatsApp-formatted text. |
| `packages/frontend/src/App.tsx` | Modify | Add `/loading` route. |
| `packages/frontend/src/components/layout/AppShell.tsx` | Modify | Enable sidebar link for Cronograma. |
| `packages/frontend/src/i18n/locales/pt-BR.json` | Modify | Add new translations for loading module. |

## Interfaces / Contracts

```typescript
// GET /api/loading/schedules?date=YYYY-MM-DD
type ScheduleResponse = {
  schedules: Array<{
    id: number;
    schedule_date: string;
    time_slot: string; // HH:mm
    driver_type: 'fletero' | 'casa';
    driver_name: string;
    license_plate: string | null;
  }>
};

// POST /api/loading/schedules
type CreateScheduleRequest = {
  schedule_date: string;
  time_slot: string;
  driver_type: 'fletero' | 'casa';
  driver_id?: number; // Needed if fletero
  vehicle_id?: number; // Needed if casa
};

// GET /api/loading/drivers
type DriversResponse = {
  drivers: Array<{ id: number; name: string; license_plate: string | null }>;
};

// POST /api/loading/drivers
type CreateDriverRequest = {
  name: string;
  license_plate?: string;
};
```

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Unit | Quota enforcement | Backend test to ensure `POST` rejects 4th fletero. |
| Unit | Export formatting | Validate WhatsApp text output ascending order and pt-BR headers. |
| Integration | Route handlers | Fastify `inject()` tests verifying auth and valid DB states. |
| E2E | Schedule flow | Playwright tests navigating to `/loading`, assigning 3 drivers, verifying 4th is blocked, and opening export modal. |

## Migration / Rollout

No migration required. The `loading_schedules` table and settings are already seeded in `schema.sql`.

## Open Questions

- None
