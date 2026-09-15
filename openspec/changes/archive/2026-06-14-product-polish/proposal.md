# Proposal: Product Polish

## Intent

Address accumulated UX debt and inconsistencies across the application by centralizing UI components, improving accessibility, and standardizing internationalization (i18n).

## Scope

### In Scope
- Create a shared `LoadingSpinner` component and apply it across all pages.
- Add `aria-live="assertive"` and `role="alert"` to error/success banners for screen readers.
- Extract ~30 hardcoded Portuguese strings in `AdminDashboard` to `pt-BR.json`.
- Fix the `reloadData()` re-render flash issue in `AdminDashboard`.
- Fix stale `deferredNotice` copy on the dashboard.
- Separate emojis from translation strings in `ReportsPage` and `ReportViewPage`.
- Set up a Spanish locale skeleton (`es.json`) that falls back to Portuguese.

### Out of Scope
- Full translation of the Spanish locale.
- Visual redesigns or layout structure changes.
- Componentizing or splitting `AdminDashboard` into smaller modules.

## Capabilities

> This section is the CONTRACT between proposal and specs phases.
> The sdd-spec agent reads this to know exactly which spec files to create or update.
> Research `openspec/specs/` before filling this in.

### New Capabilities
None.

### Modified Capabilities
None. (This is a pure UI/UX polish and refactoring change; no business capabilities or requirements are changing.)

## Approach

Deliver a single comprehensive polish PR (~350 lines). The changes are mostly mechanical replacements (inline spinners to shared component, string literals to `t()` calls, adding aria attributes) without altering application logic. This avoids stacking overhead while keeping the diff reviewable within the 400-line budget.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `packages/frontend/src/components/ui/loading-spinner.tsx` | New | Shared spinner component |
| `packages/frontend/src/pages/*` | Modified | Replace inline spinners, add `aria-live` to banners |
| `packages/frontend/src/pages/admin/AdminDashboard.tsx` | Modified | Extract i18n strings, fix `reloadData()` |
| `packages/frontend/src/i18n/locales/pt-BR.json` | Modified | Add new keys, remove emojis, update text |
| `packages/frontend/src/i18n/locales/es.json` | New | Create Spanish skeleton |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| E2E tests fail due to hardcoded text changes | Medium | Run Playwright locally; update text assertions if needed. |
| AdminDashboard regressions during extraction | Low | Purely mechanical string-to-function extraction. |

## Rollback Plan

Revert the single PR. Since there are no database or backend logic changes, a git revert is perfectly safe and immediate.

## Dependencies

- None.

## Success Criteria

- [ ] Shared `LoadingSpinner` is used on all 9 application pages.
- [ ] Error/success banners across the app have `role="alert"` and `aria-live="assertive"`.
- [ ] `AdminDashboard` uses `t()` for all user-facing text without hardcoded PT-BR strings.
- [ ] Emojis are no longer baked into i18n JSON values.
- [ ] Spanish locale (`es.json`) is properly registered and falls back to Portuguese without crashing.
- [ ] All E2E tests continue to pass.