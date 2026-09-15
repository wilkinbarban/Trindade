# Design: Photos in Reports + Visual Audit

## Technical Approach

Implement local photo storage via Fastify auth-gated endpoints and centralized inline audit logging. Split into two independent chained PRs (Photos, then Audit) to keep work units under 400 lines and maintain reviewer focus.

## Architecture Decisions

### Decision: Photo Storage and Serving

**Choice**: `@fastify/multipart` for upload, store in `data/photos/`, serve via Fastify GET endpoint with JWT auth.
**Alternatives considered**: Nginx `X-Accel-Redirect` (too complex for current scale).
**Rationale**: Simple to implement, works with existing infrastructure, and ensures photos remain auth-gated without touching Docker Nginx configs.

### Decision: Audit Logging Hook Strategy

**Choice**: Explicit inline `audit.log()` calls inside service modules.
**Alternatives considered**: Fastify global `onResponse` hook.
**Rationale**: Inline hooks are explicit, easier to test, and avoid magic. They capture the semantic "why" better than reverse-engineering from URLs.

### Decision: Audit Visual UI Placement

**Choice**: Separate admin page at `/admin/audit`.
**Alternatives considered**: Add "Auditoria" as a 7th tab in `AdminDashboard.tsx`.
**Rationale**: `AdminDashboard.tsx` is already complex (796 lines). A separate route with pagination and filters prevents bloating and adheres to single-responsibility principles.

## Data Flow

    [Frontend Upload] ──(multipart/form-data)──→ [Fastify /api/reports/:id/photos]
                                                             │
                                                             ├─→ [SQLite: report_photos]
                                                             │
                                                             └─→ [Disk: data/photos/...]

    [Service Action] ──(audit.log)──→ [Audit Module] ──→ [SQLite: audit_logs]
                                                             │
    [Admin Frontend] ◀──(GET /api/admin/audit)───────────────┘

## File Changes

### PR 1: Photos in Reports (Work Unit 1)
| File | Action | Description |
|------|--------|-------------|
| `packages/backend/src/server.ts` | Modify | Register `@fastify/multipart`. |
| `packages/backend/src/modules/reports/reports.routes.ts` | Modify | Add photo upload, serve, list, delete routes. |
| `packages/backend/src/modules/reports/reports.service.ts` | Modify | Add database operations for `report_photos`. |
| `packages/frontend/src/api/client.ts` | Modify | Add `upload` method using `FormData`. |
| `packages/frontend/src/pages/ReportViewPage.tsx` | Modify | Add Photo Gallery UI. |
| `packages/frontend/src/pages/ReportEditPage.tsx` | Modify | Add Photo Upload/Delete widget. |
| `docker-compose.yml` | Modify | Ensure data volume includes photos directory mapping if needed. |

### PR 2: Audit Service + Visual UI (Work Unit 2)
| File | Action | Description |
|------|--------|-------------|
| `packages/backend/src/modules/audit/audit.service.ts` | Create | Centralized `audit.log()` utility. |
| `packages/backend/src/modules/audit/audit.routes.ts` | Create | Admin paginated GET endpoint for logs. |
| `packages/frontend/src/pages/admin/AuditPage.tsx` | Create | Standalone paginated admin UI. |
| `packages/frontend/src/App.tsx` | Modify | Register `/admin/audit` route. |
| `packages/backend/src/modules/*/` | Modify | Insert inline `audit.log()` calls into existing services. |

## Interfaces / Contracts

```typescript
// Photo Upload UI
interface PhotoUploadProps {
  reportId: string;
  readOnly?: boolean;
}

// Audit Service
export interface AuditLogParams {
  userId: string;
  action: string;
  entity: string;
  entityId?: string;
  details?: Record<string, any>;
}

export const audit = {
  log: (db: Database, params: AuditLogParams) => void;
};
```

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Unit (Backend) | `audit.log()` behavior | Mock DB write and assert `audit_logs` record structure. |
| Integration | Photo Upload/Serve | `app.inject` multipart payload, assert DB entry and disk write. |
| E2E | Report creation with photo | Playwright test adding a photo to a new report. |

## Migration / Rollout

No data migration required (`report_photos` and `audit_logs` tables already exist). Docker image build must ensure `data/photos` directory is created on startup or mounted.

## Open Questions

- None. Implementation is ready for task generation.
