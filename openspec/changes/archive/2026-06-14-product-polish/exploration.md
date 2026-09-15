## Exploration: Product Polish — Bounded First Slice

### Current State

Trindade Massas Operações has 6 operational features through 8 prior SDD changes (foundation, reports, loading, dashboard, admin, exports, photos, audit). The codebase is functional but has accumulated minor UX debt across all pages:

- **i18n usage is inconsistent**: AdminDashboard and AuditPage hardcode ~40 labels/strings in Portuguese instead of using `t()` from `react-i18next`. The Spanish locale (`es.json`) does not exist — only `pt-BR.json` is loaded. The system always stays in PT-BR regardless of browser language.
- **Loading state pattern duplication**: Every page inlines the same 5-line spinner (`animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600`). No shared `LoadingSpinner` component exists despite a private one in `App.tsx`.
- **Error banners lack aria-live**: 10+ error/success banners across all pages use plain divs. Screen readers will not announce state changes.
- **Dashboard has stale copy**: The `deferredNotice` says modules "will be available in future versions" — all modules are already shipped.
- **Emojis baked into translation strings**: `reports.turnoTarde: "Tarde 🌅"` and `reports.turnoNoite: "Noite 🌙"` — emojis mixed with i18n data.
- **AdminDashboard reloadData() is fragile**: Uses a tab-toggling hack (`setTimeout(() => setActiveTab(current), 0)`) that causes unnecessary re-renders.
- **Navigation active state uses exact path match**: `/reports/history` is not highlighted when viewing a report at `/reports/:id` or a new report at `/reports`.
- **No success feedback on saves**: After saving/updating/deleting, the user only knows it worked because the page navigates or the item disappears. No toast or confirmation banner.
- **6 E2E specs + 4 backend test files exist** — no existing tests would need modification for the changes outlined below.

### Affected Areas

- `packages/frontend/src/components/ui/loading-spinner.tsx` — **NEW**: shared LoadingSpinner component.
- `packages/frontend/src/App.tsx` — Replace inline LoadingSpinner with shared import.
- `packages/frontend/src/pages/DashboardPage.tsx` — Replace inline spinner, fix stale deferredNotice, fix turno emoji usage.
- `packages/frontend/src/pages/ReportsPage.tsx` — Replace inline spinner, add `aria-live` to error banner.
- `packages/frontend/src/pages/ReportEditPage.tsx` — Replace inline spinner, add `aria-live` to error banners.
- `packages/frontend/src/pages/ReportViewPage.tsx` — Replace inline spinner, add `aria-live` to error banners.
- `packages/frontend/src/pages/ReportHistoryPage.tsx` — Replace inline spinner, add `aria-live`.
- `packages/frontend/src/pages/LoadingSchedulePage.tsx` — Replace inline spinner, add `aria-live`.
- `packages/frontend/src/pages/LoadingEditPage.tsx` — Replace inline spinner, add `aria-live`.
- `packages/frontend/src/pages/admin/AdminDashboard.tsx` — Replace inline spinner, replace ~30 hardcoded PT strings with `t()` calls (tabs, action labels, empty state messages, Badge, buttons), add `aria-live` to error banner.
- `packages/frontend/src/pages/admin/AuditPage.tsx` — Replace inline spinner, add `aria-live`.
- `packages/frontend/src/pages/LoginPage.tsx` — Add `aria-live` to error banner.
- `packages/frontend/src/i18n/locales/pt-BR.json` — Add ~35 new translation keys (admin panel labels, button labels, empty state messages), remove emoji sequences from turno labels.
- `packages/frontend/src/i18n/config.ts` — Add Spanish locale import/resource entry.
- `packages/frontend/src/i18n/locales/es.json` — **NEW**: Spanish locale (skeleton with all keys, most values falling back to Portuguese for now with a comment header noting this).

### Approaches

#### Overall Strategy: Progressive Polish in a Single PR

Rather than splitting polish across multiple chained PRs (which adds review overhead), ship these cohesive changes in a single PR under 400 lines. The changes are:
- **Mechanical**: Replace duplicates with shared component, add aria attributes.
- **Low risk**: Changing i18n key locations and adding a new locale file does not break existing functionality.
- **Verifiable**: Existing E2E tests should still pass since DOM structure changes are minimal (aria attributes only) and selector targets (`data-testid`) are untouched. AdminDashboard labels move to `t()` calls, so visual text changes — update E2E assertions if they check hardcoded text.

| Approach | Pros | Cons | Effort |
|----------|------|------|--------|
| A. **One comprehensive polish PR** (recommended) | Single review, no stacking overhead, all polish consistent | Changes ~15 files (but each change is small) | Low |
| B. **Chained: (1) i18n cleanup, (2) UX polish** | Each PR is smaller, easier to review individually | Two reviews, overhead of stacking, i18n-only PR feels incomplete alone | Medium |
| C. **Only the top-3 items (spinner + aria + admin i18n)** | Slightly smaller diff | Leaves stale copy, emojis, and nav active state for later — scope feels arbitrary | Low |

#### Detailed A1: AdminDashboard i18n extraction

1. **Add all keys to pt-BR.json** — All admin tab labels, button labels, empty state messages, Badge labels, form labels. ~35 new keys.
2. **Replace string literals in AdminDashboard.tsx** — Each `<th>`, button text, `Badge`, and empty `<td>` text's hardcoded PT string → `t('admin.xxx')`. The form labels inside `InlineForm` field arrays also need extraction.
3. **Scope risk**: ~80 lines changed in AdminDashboard alone. Mitigated by the fact that changes are purely string → `t()` call with no logic changes.

### Recommendation

**Approach A — One comprehensive polish PR (~320-370 lines)**:

1. **Shared `LoadingSpinner` component** (~25 lines new + ~45 lines changed across 9 pages): Create at `components/ui/loading-spinner.tsx`. Import in 9 page files + App.tsx, replacing inline spinners. Consistent visual, reduces boilerplate, one place to update.

2. **Aria-live error banners** (~15 lines changed across 10 pages): Add `role="alert"` and `aria-live="assertive"` to every error/success banner div. This is a one-attribute addition per banner — zero behavioral risk, high accessibility value.

3. **AdminDashboard hardcoded strings → i18n keys** (~80 lines changed in AdminDashboard.tsx + ~50 new keys in pt-BR.json): Replace ~30 hardcoded PT strings with `t('admin.xxx')` calls. Affects tab labels, column headers, button labels, empty state messages, Badge "Ativo/Inativo" text, form field labels. This is purely mechanical and testable by visual inspection.

4. **Fix AdminDashboard reloadData()** (~10 lines changed): Replace the tab-toggling hack with a `useCallback`-based re-fetch key increment. Simple, eliminates the re-render flash.

5. **Fix stale dashboard deferredNotice** (~3 lines changed): Update or remove the message in pt-BR.json. All modules are shipped.

6. **Move emojis out of translation strings** (~4 lines changed in pt-BR.json + ~6 lines in ReportsPage.tsx, ReportViewPage.tsx): Remove `🌅` and `🌙` from locale values, add inline emoji spans in the two pages that render turno labels. Keeps translation data clean.

7. **Spanish locale skeleton** (~140 lines new in `es.json` + ~3 lines in `config.ts`): Create `es.json` with all keys from pt-BR.json, values defaulting to Portuguese marked with `// TODO(i18n): translate` header comment. Register in i18n config. Enables language detection without crashing and creates a foundation for future translation.

**Total**: ~320-370 changed/new lines (within 400-line budget).

### Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| E2E tests check hardcoded PT strings in AdminDashboard | Medium | Tests fail on string changes | Run Playwright locally to catch. Fix by using stable selector attributes or updating expected text matches. |
| Spanish locale skeleton users see Portuguese | Medium | Low — expected for untranslated locale | The PRD mandates PT-BR as the app language. Fallback to pt-BR is correct behavior. |
| AdminDashboard code size (796 lines) makes i18n extraction tedious | High | Nothing breaks, just careful work | Use mechanical search-and-replace per section. Each change is a literal string → `t()` mapping. |
| Accidental styling regression from aria-live addition | Very Low | Low | `role="alert"` and `aria-live` do not affect visual rendering. |

### Ready for Proposal

**Yes**. The exploration is complete. The orchestrator should proceed to `sdd-propose` with the bounded first polish slice described above.

Key message to the user: *"I've explored the full codebase and identified 7 concrete polish improvements that fit in a single PR (~350 lines). The focus is high-operator-value, low-risk changes: sharing the loading spinner (all 9 pages), adding screen-reader support to error banners, fixing admin panel labels to use i18n, cleaning up stale dashboard copy, separating emojis from translations, and creating the Spanish locale skeleton. No breaking changes, no feature additions — pure polish. All existing tests should pass with minimal updates. Ready to propose."*
