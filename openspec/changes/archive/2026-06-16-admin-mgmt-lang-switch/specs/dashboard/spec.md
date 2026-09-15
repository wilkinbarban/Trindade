# Delta for Dashboard

## MODIFIED Requirements

### Requirement: Responsive Application Shell
The frontend MUST render a responsive layout containing a sidebar, a header with language selection buttons (PT/ES), and a footer with a change password trigger.
(Previously: The application shell rendered a responsive sidebar and header without language switchers or a footer password option.)

#### Scenario: Mobile viewport adjustment
- GIVEN a user accessing the application on a mobile device
- WHEN the application shell renders
- THEN the sidebar collapses or adapts into a mobile menu
- AND the layout is optimized for narrow screens

#### Scenario: Language switcher interaction
- GIVEN a user on the dashboard
- WHEN they click the PT or ES button in the header
- THEN the UI language translates dynamically using i18next

#### Scenario: Footer password change trigger
- GIVEN a logged-in user
- WHEN they click the password change option in the footer
- THEN a modal is presented to change their password
