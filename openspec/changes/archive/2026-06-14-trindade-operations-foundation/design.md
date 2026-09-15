# Design: Trindade Operations Foundation

## Technical Approach

Establish an npm workspaces monorepo containing a React/Vite frontend and a Fastify/Node.js backend. Use `better-sqlite3` to interact with a local SQLite database using raw SQL queries and Zod for runtime validation, avoiding heavy ORM overhead. Secure the API with JWT-based stateless authentication. Containerize the full stack using Docker Compose with an Nginx reverse proxy.

## Architecture Decisions

### Decision: NPM Workspaces Monorepo

**Choice**: Single repository with `packages/frontend` and `packages/backend`.
**Alternatives considered**: Two separate repositories.
**Rationale**: Simplifies type sharing across the stack, keeps full stack context in one place, and reduces configuration overhead for a small team.

### Decision: Database Access Pattern

**Choice**: `better-sqlite3` with raw SQL and Zod validation.
**Alternatives considered**: Prisma, TypeORM, Drizzle.
**Rationale**: `better-sqlite3` is highly performant and synchronous. For a simple operations app with predetermined tables, raw SQL avoids ORM bloat and complex build steps, while Zod ensures type safety at the boundary.

### Decision: Authentication

**Choice**: Stateless JWT via Authorization header.
**Alternatives considered**: Stateful session cookies.
**Rationale**: JWT aligns well with a Fastify REST API and a mobile-first React SPA. It simplifies cross-origin resource sharing if the API and frontend ever split domains, without complex cookie policies.

## Data Flow

    React SPA (Frontend) ──(REST/JWT)──→ Fastify API (Backend)
             │                                 │
      (Zod Validation)                 (better-sqlite3)
             │                                 │
             └───────────── SQLite Database ───┘

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `package.json` | Create | Root workspace configuration |
| `docker-compose.yml` | Create | Orchestrates Nginx, Node API |
| `Makefile` | Create | Developer shortcuts (`dev`, `db-reset`) |
| `packages/backend/src/db/schema.sql` | Create | Defines all PRD tables |
| `packages/backend/src/db/seed.sql` | Create | Inserts admin/trabajador roles and base admin user |
| `packages/backend/src/db/index.ts` | Create | Configures `better-sqlite3` connection |
| `packages/backend/src/modules/auth/auth.routes.ts` | Create | Login endpoint & JWT generation |
| `packages/frontend/src/contexts/AuthContext.tsx` | Create | React context managing JWT state |
| `packages/frontend/src/api/client.ts` | Create | Fetch wrapper with JWT interceptor |
| `packages/frontend/src/components/layout/AppShell.tsx` | Create | Responsive sidebar and header |

## Interfaces / Contracts

```typescript
// Shared Authentication Response
export interface LoginResponse {
  token: string;
  user: {
    id: number;
    username: string;
    role: 'Administrador' | 'Trabalhador';
  };
}

// Dashboard Summary Response
export interface DashboardSummary {
  reportsToday: number;
  schedulesTomorrow: number;
  activeUsers: number;
}
```

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Unit | API utility functions, schemas | Node native test runner (`node:test`) |
| Integration | Login endpoint, JWT middleware | Fastify `.inject()` against in-memory SQLite |
| E2E | App shell rendering, login flow | Playwright (setup later, manual for now) |

## Migration / Rollout

No migration required. This is a greenfield deployment. The backend will execute `schema.sql` and `seed.sql` automatically on startup if the database file is empty.

## Open Questions

- [ ] Will Nginx also serve the built React static files in the production Docker Compose setup? (Assuming yes for now).
