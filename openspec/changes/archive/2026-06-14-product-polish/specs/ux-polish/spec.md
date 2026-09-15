# UX and Accessibility Polish Specification

## Purpose

Defines non-functional requirements for application-wide UI consistency, accessibility, and internationalization standards applied during the product polish phase.

## Requirements

### Requirement: Centralized Loading State

The system SHOULD use a single, centralized visual loading component across all views rather than duplicating inline spinner elements.

#### Scenario: Page requires loading state

- GIVEN a user navigates to a view that fetches data
- WHEN the data is currently loading
- THEN the application displays the shared `LoadingSpinner` component
- AND the visual representation remains consistent across all pages

### Requirement: Screen Reader Alerts for Feedback

The system MUST announce error, success, and validation messages to assistive technologies immediately as they appear on screen.

#### Scenario: Displaying a temporary banner

- GIVEN a user performs an action (e.g., login failure, data fetch error)
- WHEN a feedback banner is rendered dynamically
- THEN the banner element includes `role="alert"` and `aria-live="assertive"`
- AND screen readers announce the banner content immediately

### Requirement: Internationalization Completeness

All user-facing strings SHOULD be extracted to the active translation registry (`i18next`) to avoid hardcoded text, ensuring the application can support alternative locales cleanly.

#### Scenario: Viewing the Admin Dashboard

- GIVEN a user views the Admin Dashboard
- WHEN they inspect tabs, buttons, or empty states
- THEN all text values are loaded from the translation dictionary
- AND no hardcoded Portuguese string literals exist in the component

### Requirement: Structural Separation of Localization and Graphics

Translation dictionary entries MUST NOT contain graphical characters (e.g., emojis) to ensure locale strings remain purely semantic.

#### Scenario: Displaying shift labels with icons

- GIVEN the application needs to display a shift (e.g., "Turno Tarde 🌅")
- WHEN the label is rendered
- THEN the textual part is fetched from the translation dictionary
- AND the emoji is rendered separately in the component markup

### Requirement: Safe Fallback for Incomplete Locales

The system MUST allow registration of new locales (e.g., Spanish) even if translations are incomplete, falling back to the primary language safely.

#### Scenario: Adding a skeleton locale

- GIVEN a new locale like `es.json` is registered
- WHEN a user requests a translation key
- THEN the system resolves the value correctly or falls back gracefully without crashing
