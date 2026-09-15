# Design: Relatório Inteligente

## Technical Approach

Implement the core report builder using existing SQLite tables. Create a Fastify `reports` module with a dedicated `export.service.ts` for WhatsApp text generation. Build a dynamic React frontend using `shadcn/ui` components for the 4 data types, and a history page with date filters. Edit access will be blocked on the backend for reports older than the previous day.

## Architecture Decisions

### Decision: WhatsApp Export Location
**Choice**: Backend `export.service.ts`.
**Alternatives considered**: Frontend text generation.
**Rationale**: Keeps the "Generador Inteligente" text formatting central, secure, and easier to test independently of the UI. It guarantees the output is strictly in Portuguese (using `name_pt`) regardless of the frontend's current locale.

### Decision: Shift Turn (Turno) Auto-detection
**Choice**: Backend service layer sets default based on server time, but allows frontend override.
**Alternatives considered**: Frontend-only detection.
**Rationale**: Server time is authoritative. If a user submits a report at 2 AM, the server correctly attributes it to the 'noite' shift, avoiding timezone or client-clock inconsistencies.

### Decision: Edit Window Validation
**Choice**: Backend `PATCH /api/reports/:id` blocks if `report_date` is older than `date('now', 'localtime', '-1 day')`.
**Alternatives considered**: Frontend-only hiding of edit buttons.
**Rationale**: Security requires backend enforcement. The UI will hide the edit button, but the API must reject invalid requests.

## Data Flow

    [React Builder Form] ──(POST /api/reports)──→ [Fastify Reports Route]
           │                                                │
           │                                                ▼
           │                                      [SQLite Transaction]
           │
           └──(GET /api/reports/:id/export)──→ [Export Service] ──→ [WhatsApp Plain Text]

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `packages/backend/src/modules/reports/reports.routes.ts` | Create | Fastify routes for CRUD and export. |
| `packages/backend/src/modules/reports/reports.service.ts` | Create | Business logic and DB transactions. |
| `packages/backend/src/modules/reports/export.service.ts` | Create | Generador Inteligente text generation (pt-BR only). |
| `packages/backend/src/modules/reports/reports.schema.ts` | Create | TypeBox validation schemas. |
| `packages/backend/src/server.ts` | Modify | Register `reportsRoutes`. |
| `packages/frontend/src/pages/ReportsPage.tsx` | Create | Dynamic builder UI. |
| `packages/frontend/src/pages/ReportHistoryPage.tsx` | Create | History view with date filters. |
| `packages/frontend/src/pages/ReportViewPage.tsx` | Create | Single report view and export preview. |
| `packages/frontend/src/components/reports/CategorySection.tsx` | Create | Renders elements for a category. |
| `packages/frontend/src/components/reports/ExportPreview.tsx` | Create | Dialog showing generated WhatsApp text with copy button. |
| `packages/frontend/src/App.tsx` | Modify | Add routes. |
| `packages/frontend/src/components/layout/AppShell.tsx` | Modify | Enable "Relatórios" navigation link. |
| `packages/frontend/src/i18n/locales/pt-BR.json` | Modify | Add UI strings for reports. |

## Interfaces / Contracts

```typescript
// backend/src/modules/reports/reports.schema.ts
export const CreateReportSchema = Type.Object({
  turno: Type.Optional(Type.Union([Type.Literal('tarde'), Type.Literal('noite')])),
  notes: Type.Optional(Type.String()),
  items: Type.Array(Type.Object({ taskId: Type.Integer(), checked: Type.Boolean() })),
  temperatures: Type.Array(Type.Object({ location: Type.String(), value: Type.Number() })),
  quantities: Type.Array(Type.Object({ productId: Type.Integer(), quantity: Type.Number() }))
});
```

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Unit | `export.service.ts` | Verify formatting, sections, and strict Portuguese output. |
| Integration | `reports.routes.ts` | Fastify `inject` for creating a report, DB transaction rollback on failure, edit window block. |
| E2E | Builder & Export | Cypress/Playwright flow: form fill -> submit -> export -> copy to clipboard. |

## Migration / Rollout

No migration required. All tables are predefined in `schema.sql`.

## Open Questions

- None
