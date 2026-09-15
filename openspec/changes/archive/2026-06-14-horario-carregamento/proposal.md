# Proposal: Horário de Carregamento

## Intent

Provide a scheduling module to organize the next day's loading operations, ensuring predictable capacity by capping external drivers (Fleteros) per time slot.

## Scope

### In Scope
- Schedule CRUD (list by date, add entry, delete entry)
- Fletero management (list active, quick-create)
- Max 3 fleteros per time slot quota validation
- Date navigation and time slot grid UI
- WhatsApp export in Portuguese (ascending time order)

### Out of Scope
- Fleet vehicle (Casa) management UI
- Schedule edit functionality (delete/recreate instead)
- TXT/PDF export formats
- Admin-only driver permissions

## Capabilities

### New Capabilities
- `loading-schedule`: Core scheduling CRUD, driver assignment, and quota enforcement (max 3 fleteros per slot).
- `loading-export`: WhatsApp export text generation in pt-BR.

### Modified Capabilities
- None

## Approach

Create a new `loading` module in the backend with routes, schemas, and a service that enforces the 3-fletero quota using a count check before insertion. Build `LoadingSchedulePage` on the frontend with a date picker, a time slot grid showing assignments, and a WhatsApp export modal. Use existing DB tables without adding new constraints, relying on business logic.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `packages/backend/src/modules/loading/` | New | Routes, schemas, and services |
| `packages/backend/src/server.ts` | Modified | Register `/api/loading` routes |
| `packages/frontend/src/pages/LoadingSchedulePage.tsx` | New | Main schedule UI grid |
| `packages/frontend/src/App.tsx` | Modified | Add route, enable sidebar item |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Scope creep into fleet management | Low | Explicitly defer vehicle CRUD |
| Overlap confusion | Low | Show driver count per slot in UI |

## Rollback Plan

Remove the `LoadingSchedulePage` from frontend routes, delete the `loading` backend module, and remove route registration from `server.ts`.

## Success Criteria

- [ ] Users can assign drivers to time slots on a given date.
- [ ] System rejects assigning a 4th fletero to a time slot.
- [ ] Users can generate a WhatsApp export of the daily schedule in Portuguese.