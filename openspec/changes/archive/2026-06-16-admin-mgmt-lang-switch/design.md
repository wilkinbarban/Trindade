# Design: Admin Management & Language Switch

## Technical Approach
Implement user management, password changes, language switching, and physical deletes with database constraint handling. Add user CRUD to `/api/admin/users`, physical delete handlers under `/api/admin/*` that catch foreign key violations (returning HTTP 400), and self-password change at `/api/auth/change-password`. Integrate client-side language toggle in `AppShell.tsx` and custom modals with weak password warnings.

## Architecture Decisions

### Decision: User Management Endpoint Location
| Option | Tradeoff | Decision |
|---|---|---|
| `/api/users/*` | Requires additional middleware role check, separates admin scope. | Rejected |
| `/api/admin/users` | Uses existing `adminGuard`, centralizes all administrative operations. | **Chosen** |

### Decision: Password Change Endpoint Location
| Option | Tradeoff | Decision |
|---|---|---|
| `/api/admin/change-password` | Workers cannot access due to admin guard. | Rejected |
| `/api/auth/change-password` | Accessible to all logged-in users under `authenticate` middleware. | **Chosen** |

### Decision: Client-side Password Strength Check
| Option | Tradeoff | Decision |
|---|---|---|
| Block weak passwords on submit | Frustrates users, deviates from requirement of allowing 4+ char passwords. | Rejected |
| Show non-blocking recommendation warning | Warns user of weak password (8+ chars, uppercase, digit) but allows submission. | **Chosen** |

## Data Flow
```
[Frontend AppShell / Dashboard] ──(Request)──> [Fastify API Server] ──> [SQLite DB]
  - Language toggle updates client i18n context & stores in LocalStorage
  - Password modal validates client-side, POSTs to /api/auth/change-password
  - Admin Dashboard makes admin requests to /api/admin/*
  - Deletes catch SQLite constraint (SQLITE_CONSTRAINT) on backend -> HTTP 400
```

## File Changes
| File | Action | Description |
|---|---|---|
| `packages/backend/src/db/seed.sql` | Modify | Rename historical administrator [REDACTED LEGACY CREDENTIAL]. |
| `packages/backend/src/modules/admin/admin.schema.ts` | Modify | Define `CreateUserSchema` and `UpdateUserSchema`. |
| `packages/backend/src/modules/admin/admin.service.ts` | Modify | Implement User CRUD and Entity delete functions catching `SQLITE_CONSTRAINT`. |
| `packages/backend/src/modules/admin/admin.routes.ts` | Modify | Add `/users` CRUD routes and `DELETE` endpoints for other entities. |
| `packages/backend/src/modules/auth/auth.routes.ts` | Modify | Add `POST /change-password` route with current password verification. |
| `packages/backend/src/modules/*/*.test.ts` | Modify | Update administrator logins [REDACTED LEGACY CREDENTIAL] and add new CRUD/delete tests. |
| `packages/frontend/src/components/layout/AppShell.tsx` | Modify | Add PT/ES header buttons and password change footer modal. |
| `packages/frontend/src/pages/admin/AdminDashboard.tsx` | Modify | Add Users tab for CRUD and physical delete buttons with error display. |
| `packages/frontend/src/i18n/locales/*.json` | Modify | Add translation strings for UI actions, deletes, and warnings. |

## Interfaces / Contracts
```typescript
// CreateUserSchema
export const CreateUserSchema = z.object({
  username: z.string().min(1),
  password: z.string().min(4),
  display_name: z.string().min(1),
  role_id: z.union([z.literal(1), z.literal(2)]),
});

// UpdateUserSchema
export const UpdateUserSchema = z.object({
  username: z.string().min(1).optional(),
  password: z.string().min(4).optional(),
  display_name: z.string().min(1).optional(),
  role_id: z.union([z.literal(1), z.literal(2)]).optional(),
  is_active: z.union([z.literal(0), z.literal(1)]).optional(),
});

// POST /api/auth/change-password
export const ChangePasswordSchema = z.object({
  currentPassword: z.string().min(1),
  newPassword: z.string().min(4),
});
```

## Testing Strategy
| Layer | What to Test | Approach |
|---|---|---|
| Integration | `/api/admin/users` CRUD endpoints | Fastify `app.inject` tests validating role access, creation, updating, is_active toggle, and deletion. |
| Integration | `/api/auth/change-password` endpoint | Test successful password change, invalid current password, and minimum length validation. |
| Integration | physical deletes & constraint failure | Attempt to delete a seeded category/task with existing relations; assert HTTP 400. |

## Migration / Rollout
No database schema migration is required. SQLite seed is run once on DB reset (`npm run db:reset`). The rollout is fully backward compatible, updating current seed configuration.

## Open Questions
None.
