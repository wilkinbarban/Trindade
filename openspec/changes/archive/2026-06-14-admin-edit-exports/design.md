# Design: Admin Edit & Exports

## Technical Approach

We will implement this change in three distinct reviewable PRs to respect the 400-line budget: Admin CRUD, Advanced Editing, and Extra Exports. We will leverage the existing Fastify backend and React/Tailwind frontend, adding `jspdf` + `html2canvas` for frontend-based PDF generation to avoid backend infrastructure bloat. We will introduce an `Admin` role guard for the new backend routes.

## Architecture Decisions

### Decision: PDF Generation Strategy

**Choice**: Client-side generation using `jspdf` and `html2canvas`.
**Alternatives considered**: Server-side using Puppeteer, `pdfkit`, or `pdf-lib`.
**Rationale**: Server-side options either require heavy binaries (Puppeteer) or manual coordinate-based drawing (`pdfkit`/`pdf-lib`) which makes replicating the React UI extremely complex. Client-side captures the existing DOM precisely as styled with Tailwind, minimizing effort and backend complexity.

### Decision: Advanced Editing Approach

**Choice**: Reuse existing creation components (`CategorySection`, `ScheduleGrid`) initialized with existing data.
**Alternatives considered**: Separate "Edit" specific components.
**Rationale**: Reusing creation components ensures consistency between what was entered and what is edited, reducing duplicated logic and component maintenance.

### Decision: Admin Role Guard

**Choice**: Add `requireRole` middleware in backend and role checks in frontend.
**Alternatives considered**: Create a separate admin app.
**Rationale**: The app is currently simple enough that adding role-based checks to existing auth middleware is sufficient and keeps the architecture cohesive.

## Data Flow

**Admin CRUD:**
Frontend Admin UI ──(JSON)──→ Backend `/api/admin/*` ──→ DB (Categories, Products, Users)

**Advanced Editing:**
Frontend Edit Page ──(GET)──→ Backend `/api/reports/:id` or `/api/loading/:id`
Frontend Edit Page ──(PATCH)──→ Backend `/api/reports/:id` or `/api/loading/:id` (Transaction updates)

**PDF Export:**
Frontend Report/Loading View ──→ `html2canvas` renders DOM to Image ──→ `jspdf` embeds image into PDF ──→ Browser downloads PDF

## File Changes

### PR 1: Admin CRUD
| File | Action | Description |
|------|--------|-------------|
| `packages/backend/src/modules/auth/auth.middleware.ts` | Modify | Add `requireRole` middleware. |
| `packages/backend/src/modules/admin/admin.routes.ts` | Create | CRUD endpoints for reference data and users. |
| `packages/backend/src/modules/admin/admin.service.ts` | Create | Service logic for admin operations. |
| `packages/frontend/src/pages/admin/AdminDashboard.tsx` | Create | Admin landing and navigation. |
| `packages/frontend/src/api/client.ts` | Modify | Add `patch` and `put` methods. |

### PR 2: Advanced Editing
| File | Action | Description |
|------|--------|-------------|
| `packages/backend/src/modules/reports/reports.routes.ts` | Modify | Enhance `PATCH /:id` to ensure deep updates are transactional. |
| `packages/backend/src/modules/loading/loading.routes.ts` | Modify | Add `PATCH /:id` endpoint. |
| `packages/frontend/src/pages/ReportEditPage.tsx` | Create | Edit wrapper reusing `CategorySection`. |
| `packages/frontend/src/pages/LoadingEditPage.tsx` | Create | Edit wrapper reusing `ScheduleGrid`. |

### PR 3: Extra Exports
| File | Action | Description |
|------|--------|-------------|
| `packages/frontend/package.json` | Modify | Add `jspdf` and `html2canvas`. |
| `packages/backend/src/modules/reports/export.service.ts` | Modify | Add TXT formatting logic (if generated server-side). |
| `packages/backend/src/modules/reports/reports.routes.ts` | Modify | Add `/api/reports/:id/export/txt` endpoint. |
| `packages/frontend/src/components/reports/ExportPreview.tsx` | Modify | Add "Export as PDF" and "Export as TXT" buttons and client-side PDF logic. |

## Interfaces / Contracts

**Admin Middleware:**
```typescript
export function requireRole(role: string) {
  return async (request: FastifyRequest, reply: FastifyReply) => {
    // assumes request.user is populated by authenticate
    if (request.user?.role !== role) {
      return reply.status(403).send({ error: 'Forbidden' });
    }
  };
}
```

**Loading Schedule Edit Payload:**
```typescript
export const UpdateLoadingBodySchema = z.object({
  date: z.string().optional(),
  turno: z.enum(['manha', 'tarde', 'noite']).optional(),
  assignments: z.array(z.object({
    id: z.number().optional(), // if existing
    vehicle: z.string(),
    driver: z.string()
  })).optional()
});
```

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Unit | Admin service CRUD, Export TXT formatting | Fastify injecting requests, asserting DB/output changes. |
| Integration | PATCH routes transactional safety | Seed DB, run PATCH with partial failures to ensure rollback. |
| E2E | Edit flow, PDF download | Playwright: Open edit page, change value, save, verify. Click PDF, wait for download event. |

## Migration / Rollout

No database schema migration is strictly required if reference tables (categories/products) already exist. If adding a `role` column to `Users` is needed, a simple `ALTER TABLE` in `schema.sql` and `seed.sql` update is required.

## Open Questions

- [ ] Does the `Users` table currently have a `role` column, or do we need to add it for the `requireRole` middleware?