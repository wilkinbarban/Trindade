# Design: Product Polish

## Technical Approach

Create a shared `LoadingSpinner` component to eliminate inline spinner duplication. Add `role="alert"` and `aria-live="assertive"` attributes to feedback banners across the app to fix accessibility issues. Refactor `AdminDashboard` and other pages to extract hardcoded Portuguese strings and emojis into the `pt-BR.json` translation file. Set up an `es.json` skeleton file to provide a safe fallback for Spanish locale support.

## Architecture Decisions

### Decision: Centralized Loading Spinner

**Choice**: Create a `LoadingSpinner` component in `src/components/ui/loading-spinner.tsx`.
**Alternatives considered**: Continue using inline Tailwind classes or use an external library.
**Rationale**: Reduces duplication and creates a single source of truth for the loading state visual representation across the application.

### Decision: ARIA Attributes for Alerts

**Choice**: Add `role="alert"` and `aria-live="assertive"` directly to existing error/success banner divs.
**Alternatives considered**: Create a centralized Toast system.
**Rationale**: Adding standard ARIA attributes is the lowest-effort, highest-impact way to make current feedback mechanisms accessible to screen readers, keeping within the scope of a single polish PR.

### Decision: i18n Extraction and Fallback

**Choice**: Add Portuguese strings to `pt-BR.json`, use `useTranslation().t()` in components, and create an `es.json` skeleton file.
**Alternatives considered**: Full translation of `es.json` or keeping strings hardcoded.
**Rationale**: Solves the hardcoded string technical debt while keeping the Spanish locale out of scope for translation, allowing progressive enhancement later.

## Data Flow

    Component ──(uses t())──→ i18next ──(loads)──→ pt-BR.json
                                   │
                                   └─(fallback)─→ es.json

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `packages/frontend/src/components/ui/loading-spinner.tsx` | Create | Shared `LoadingSpinner` component |
| `packages/frontend/src/App.tsx` | Modify | Use `LoadingSpinner` |
| `packages/frontend/src/pages/*.tsx` | Modify | Replace inline spinners with `LoadingSpinner`, add `aria-live` to banners, move emojis out of translation calls |
| `packages/frontend/src/pages/admin/AdminDashboard.tsx` | Modify | Extract hardcoded PT strings to `t()`, add `aria-live`, fix `reloadData()` |
| `packages/frontend/src/i18n/locales/pt-BR.json` | Modify | Add new keys for admin strings, remove emojis from `turno` strings |
| `packages/frontend/src/i18n/config.ts` | Modify | Add Spanish locale import and resource entry |
| `packages/frontend/src/i18n/locales/es.json` | Create | Spanish locale skeleton falling back to Portuguese keys |
| `packages/frontend/src/components/loading/ScheduleExportView.tsx` | Modify | Use `LoadingSpinner` |
| `packages/frontend/src/components/reports/ExportPreview.tsx` | Modify | Use `LoadingSpinner` |

## Interfaces / Contracts

```tsx
// src/components/ui/loading-spinner.tsx
export function LoadingSpinner({ className }: { className?: string }) {
  return (
    <div className={`animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600 ${className || ''}`} />
  );
}
```

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| E2E | Existing workflows and component rendering | Run Playwright tests locally to ensure no regressions from UI and i18n string changes. Update textual assertions if needed. |
| Manual | Admin Dashboard strings and spinner | Visual inspection of all Admin Dashboard tabs and generic loading states across the app. |
| Manual | Error Banners Accessibility | Validate the presence of `role="alert"` and `aria-live="assertive"` on error/success DOM elements using browser dev tools. |

## Migration / Rollout

No migration required. This is a pure UI/UX change.

## Open Questions

- None
