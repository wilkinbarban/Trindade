# Dashboard Specification

## Purpose

Provide a responsive application shell and base dashboard with summary metric cards.

## Requirements

### Requirement: Responsive Application Shell

The frontend MUST render a responsive layout containing a sidebar and header, optimized for mobile-first viewing.

#### Scenario: Mobile viewport adjustment

- GIVEN a user accessing the application on a mobile device
- WHEN the application shell renders
- THEN the sidebar collapses or adapts into a mobile menu
- AND the layout is optimized for narrow screens

### Requirement: Dashboard Summary Data

The system MUST query and display summary counts for today's reports, tomorrow's schedules, and active users.

#### Scenario: Viewing summary cards

- GIVEN an authenticated user on the dashboard
- WHEN the page loads
- THEN the summary endpoint is called
- AND the summary cards render the corresponding counts

### Requirement: Deferred Modules Constraint

The application shell MUST keep the reporting, loading schedule, and WhatsApp export modules out of scope for this foundation slice.

#### Scenario: Navigating deferred features

- GIVEN an authenticated user on the dashboard
- WHEN inspecting the navigation and UI
- THEN the deferred business modules are either hidden or strictly presented as disabled
