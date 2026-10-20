# Frontend Tailwind migration

## Goal

Move the Next.js frontend's layout and responsive styling to Tailwind CSS while retaining the existing eCabin Ledger appearance, controls, workflows, and accessibility behavior. The existing CSS is the visual reference during migration; colors, type, spacing, radii, shadows, and states should be translated exactly before legacy rules are removed.

## Existing setup

- Tailwind CSS 4.3 is installed with the @tailwindcss/postcss plugin.
- src/app/tailwind.css imports Tailwind theme and utility layers without Preflight, and scans src/app and src/common.
- Existing CSS variables and brand colors remain the Tailwind theme source.
- layout.tsx still imports the existing global, details, login, configuration, Tailwind, and responsive stylesheets. Existing feature styles remain active until their individual migrations are verified.
- The application uses the persistent authenticated shell in src/app/loginhome/page.tsx, shared DataTable, DialogPortal, dynamic operational forms, and a monolithic Administration route. These shared surfaces have cross-screen impact and are migrated before one-off page details.

## Migration sequence

1. **Foundation and authentication** — verify the Tailwind v4/PostCSS setup, preserve font/color tokens and reduced-motion behavior, and convert Login plus access/sign-out states.
2. **Authenticated shell** — migrate shell sizing, header, navigation, responsive menu layout, and shared page containers.
3. **Shared interaction primitives** — migrate DataTable toolbar/pagination, dialogs, inputs, and upload presentation without changing their API or behavior.
4. **Operational screens** — fleet summary, aircraft defect list, Report Defect, defect detail, Reviewer queue/decision, Maintenance queue/action, verification, and closure review.
5. **Administration screens** — configuration masters, Form Master, routing, SLA, rate limits, imports, and login monitoring.
6. **Retirement and regression review** — remove a legacy CSS rule only after every consumer has Tailwind equivalents; run lint/build and compare representative desktop/mobile screenshots at 320, 375, 480, 768, 1024, and 1440px.

## Current progress

- **Foundation** — Tailwind v4 remains utility-only with no Preflight. Existing fonts and CSS design tokens are retained.
- **Login page** — converted to Tailwind utilities, including the existing desktop, narrow-screen, reduced-motion, and short-desktop-viewport treatments. The old login component rules were removed from login.css.
- **Access-unavailable and sign-out states** — converted to Tailwind utilities.
- **Authenticated shell base layout** — the shell, top bar, main navigation layout, navigation item row, workspace, and shared page-container sizing now use Tailwind utilities. Brand colors, typography, navigation item appearance, and narrow-screen CSS exceptions remain in the existing stylesheet.
- **Shared Administration DataTable layout** — toolbar, page-size control, search container, action group, table overflow container, footer, and pagination base layout now use utilities. Existing control visuals, table rules, and mobile stacking rules remain in configuration.css.
- **Shared dialog backdrop** — fixed positioning, viewport fill, centering, scroll handling, and base padding now use utilities on DialogPortal. Overlay color, blur, stacking order, safe-area padding, and existing mobile bottom-sheet placement remain in CSS.
- **Operational reporting and evidence** — the shared operational renderer uses utilities for field grid, labels, form heading, and error layout. The evidence uploader uses utilities for dropzone, copy, file list, preview rows, and progress placement. Report Defect area selection and form-choice layouts are utility-driven. Existing visual rules and upload validation behavior remain in CSS/logic.
- **Defect detail** — the case detail dialog's responsive metadata grid and audit timeline geometry use utilities. Case data, workflow actions, evidence access, and status rules are unchanged.
- **Fleet overview and aircraft list** — fleet summary grid, aircraft filters, and aircraft-page actions use utilities while retaining the existing cards, tables, and row behavior.
- **Shared dialog content** — dialog headings and action rows now use utilities across current consumers; feature-specific sizing, borders, spacing exceptions, and mobile behavior remain in CSS.
- **Administration layouts** — the shared configuration toolbar and edit-dialog grids, Reviewer routing editor, role-permission grid, area/team routing cards/forms, Maintenance SLA cards, API Rate Limits summary/filter/monitoring grids, login-monitoring summaries/filters, and shared configuration table headings/actions use utilities. Colors, typography, inputs, table appearance, and workflow states remain in existing stylesheets.
- **Form Master and configuration dialogs** — organization master-data modal layout, Form Master editor field grids, field-list layout, scroll regions, toolbar, field-card header, preview and Manage Form sections use utilities. CSV import instructions, dropzone, preview, and summary layout also use utilities. Existing field/control styling, dialog dimensions, and form behavior remain in CSS or React logic.
- **Reviewer decision panel** — the Galley Reviewer decision panel, source-report sections, version header, decision grid, and action row now use utilities. Existing field appearance, evidence behavior, and workflow validation remain unchanged.
- **Reviewer and Closure Review panels** — the Reviewer decision panel, submitted-answer grid, evidence row, action controls, shared operational renderer's closure evidence panel/list, and closure decision control/button group use utilities for responsive layout. Verification submission continues through the established SweetAlert prompt and is intentionally not replaced with another dialog system.
- **SLA, routing and login-monitoring details** — Maintenance SLA cards/reason sections and Cabin Area–Team Mapping summaries, forms, exceptions, current route and history use utilities; User Login Monitoring filters, alert, and note layout also use utilities. Form definition and workflow behavior are unchanged.
- **Layout migration status** — planned shared and page-level layout slices are migrated. CSS remains for design tokens, typography, controls, colors, borders, shadows, focus treatment, and narrowly scoped visual/responsive states. This is an additive layout migration, not removal of the existing design system.

## Implementation completion

The planned Tailwind layout migration is implemented across the application surfaces listed above. Tailwind utilities own the migrated page and component layout/responsive geometry; the existing stylesheets are intentionally retained because they still define the eCabin Ledger appearance, control treatment, and feature-specific states. This is a complete migration for the agreed layout-and-responsive scope, not a zero-CSS rewrite. No global reset, replacement component library, or visual redesign was introduced.

## Verification

For each slice run `cd frontend && pnpm lint` and `cd frontend && NEXT_TELEMETRY_DISABLED=1 pnpm exec next build --webpack`. When browser/screenshot tooling is available, compare the same representative desktop and mobile viewports before and after. Do not alter API payloads, authentication, permissions, workflow behavior, or responsive content to simplify styling.

### Verification for the shell and shared-table slice

- `cd frontend && pnpm lint` — passed.
- `cd frontend && NEXT_TELEMETRY_DISABLED=1 pnpm exec next build --webpack` — passed, including TypeScript and production build.
- `git diff --check` — passed.
- Browser screenshots and viewport interaction checks were not run in this slice.

### Verification for operational and Administration layout slices

- `cd frontend && pnpm lint` — passed after the current layout changes.
- `cd frontend && NEXT_TELEMETRY_DISABLED=1 pnpm exec next build --webpack` — passed, including TypeScript and production compilation.
- `cd frontend && pnpm build` — started the configured Turbopack build but produced no further output for about one minute; it was interrupted. The equivalent production build with `--webpack` completed successfully.
- `git diff --check` — passed.
- Browser screenshots and viewport interaction checks were not run; no before-and-after images were captured.

### Verification for closure form controls

- `cd frontend && pnpm lint` — passed.
- `cd frontend && NEXT_TELEMETRY_DISABLED=1 pnpm exec next build --webpack` — passed, including TypeScript and production compilation.
- `git diff --check` — passed.
- Browser screenshots and viewport interaction checks were not run.

### Verification for the current completion pass

- `cd frontend && pnpm lint` — passed after the Form Master, Reviewer panel, routing, and monitoring changes.
- `cd frontend && pnpm build` — started the configured Turbopack build but produced no progress after the compile-start message for roughly 30 seconds; it was interrupted (exit code 130).
- `cd frontend && NEXT_TELEMETRY_DISABLED=1 pnpm exec next build --webpack` — passed after the final changes, including TypeScript checking and production compilation.
- `git diff --check` — passed after the code and documentation changes.
- Browser viewport check — the Login screen had no horizontal overflow at 320, 375, 480, 768, 1024, or 1440 CSS pixels; a 375px screenshot was inspected in the earlier pass.
- Authenticated browser spot check — inspected the Aircraft Defect List, Form Master, Manage Form dialog, and Galley Report Defect form with evidence upload at the connected browser's 1200×630 viewport. The dialog stayed within the viewport and the report form's evidence area remained after the configured fields. No page-level horizontal overflow was visible; the wide Form Master table retains its internal horizontal scrolling behavior.
- Exact 320/375/480/768/1024/1440 checks for authenticated screens and before/after screenshot comparisons remain unavailable. The connected browser-control surface can capture screenshots but does not expose a viewport-size override, and no baseline screenshots were present. These checks are therefore not claimed as completed.

The Administration timestamp formatter no longer combines incompatible `dateStyle`/`timeStyle` and `timeZoneName` options. Users and roles / User Login Monitoring display `NA` for `NEVER_LOGGED_IN` and `Active` for `ACTIVE_RECENTLY`; API classification values and filters are unchanged.
