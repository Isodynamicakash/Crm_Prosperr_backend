# Prosperr Sales CRM — Backend

Spring Boot 3.4 · Java 21 · Supabase Postgres · Flyway · Spring Security (Google sign-in) · Spring Modulith

Built from the **Dashboard BRD** and the **Consolidated PRD v1 (14 Nov 2026)**. Where they disagree, the PRD wins (it says so itself).
`docs/TRACEABILITY.md` maps every BRD and PRD point to the code and the test that proves it.

---

## 1. Run it

### Prerequisites
- Java 21, Maven 3.9+
- A Postgres 15+ database (Supabase works). Flyway creates everything in schema `crm` on first start.

### Environment variables

| Variable | Example | Notes |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://db.<ref>.supabase.co:5432/postgres?currentSchema=crm` | Supabase **direct connection (5432)** or **session pooler**. Not the transaction pooler (6543): Flyway and prepared statements need a session. |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / … | |
| `JWT_SECRET` | 64 random characters | Signs the CRM's own access token. **Must be changed in production.** |
| `GOOGLE_CLIENT_ID` | `…apps.googleusercontent.com` | OAuth client of the React app's "Sign in with Google". |
| `FRONTEND_URL` | `http://localhost:5173` | CORS origin and links in emails. |
| `APP_BASE_URL` | `https://crm-api.prosperr.io` | Used in unsubscribe links. |
| `DEV_LOGIN_ENABLED` | `true` (local only) | Enables `POST /api/auth/dev-login {email}` without Google. Never in production. |
| `JOBS_ENABLED` | `true` | Scheduled jobs (60-day flag, revisits, sequences, reminders, digest). Run one instance with jobs on. |
| `MAIL_PROVIDER` | `log` | `log` = emails are logged, not sent. See section 6 for Gmail. |

```bash
mvn spring-boot:run
# Swagger UI: http://localhost:8080/swagger-ui.html
```

### First admin
Users are created by an admin; there is no self sign-up. Insert the first admin once:
```sql
INSERT INTO crm.app_user (email, full_name, role) VALUES ('founder@prosperr.io', 'Founder', 'ADMIN');
```
After that the admin creates everyone else in the app (`POST /api/admin/users`) and decides each role.

### Login flow
1. React shows "Sign in with Google" and gets a Google ID token.
2. `POST /api/auth/google {idToken}`: the backend verifies it against Google's keys, issuer and your client id. The email must be on the
   `allowed_login_domain` setting (`prosperr.io`) **and** exist as an active user created by the admin.
3. The response holds the CRM access token (JWT, 12 h) and the user with their **role from the CRM**, not from Google.
4. Send `Authorization: Bearer <token>` on every call. `GET /api/auth/me` tells the frontend which home screen to show.

---

## 2. Test it

```bash
mvn test
```
- **Unit tests** (64): the business rules (ICP criteria, call-outcome rules, stage rules, allocation, permissions, template rendering,
  send window, inactivity). No database needed.
- **Integration tests** (60): real Spring context, real Postgres, HTTP calls with real JWTs, one test per PRD/BRD point.
  - With Docker running: Testcontainers starts `postgres:16-alpine` automatically.
  - Without Docker: point them at an **empty** database:
    `CRM_TEST_DB_URL=jdbc:postgresql://localhost:5432/crm_test?currentSchema=crm CRM_TEST_DB_USER=postgres CRM_TEST_DB_PASSWORD=… mvn test`
- **ModularityTest** (2): fails the build if a module reaches into another module's internals or modules form a cycle; also writes
  PlantUML module diagrams to `target/spring-modulith-docs`.

### What was verified before delivery, and what was not

This code was written in a sandbox that **could not download Maven dependencies**, so `mvn test` has **not** been run yet.
To compensate, the following checks were run:

| Check | Result |
|---|---|
| Business-rule unit tests compiled and executed with `javac` + a JUnit stand-in | **64 / 64 pass** |
| All main code type-checked against stand-ins of every library API it calls (everything except `SecurityConfig`'s DSL) | **0 errors** |
| All test code type-checked the same way | **0 errors** (this caught 8 real errors, now fixed) |
| Migrations V1 + V2 applied to Postgres 16 | **OK** |
| Every static SQL statement (258) `PREPARE`d against the real schema (columns, types, syntax) | **258 / 258 OK** |
| Every runtime-assembled SQL (dashboards, reports, lists with all filters: 98 distinct) recorded from the real code and `PREPARE`d | **98 / 98 OK** (this caught 2 real bugs, now fixed) |
| Triggers: size bucket, stage history with acting user, combined L1+L2 stepping, activity recency, capacity view | **OK** |
| Integration tests executed | **Not yet — run `mvn test`** |

Expect a short round of fixes on the first real `mvn test`, most likely in Spring wiring or configuration rather than in business
logic or SQL, which were checked above.

---

## 3. Architecture

**Modular monolith.** One deployable, strict modules, verified by Spring Modulith. Each module exposes only its `api` package
(interfaces, records, events). Everything else is internal.

```
com.prosperr.crm
├── shared/        enums, errors, security (Actor, AccessPolicy, CurrentUser), JDBC helpers, export
├── identity/      users, roles, Google sign-in, app JWT, deactivation
├── config/        picklists, settings, cities, size buckets, designation map, ICP rules, thresholds, checklists, permission overrides
├── audit/         field-level audit trail
├── account/       accounts, stakeholders, duplicates, guard, lists, search, account page, multithreading, timeline
├── prospecting/   call outcomes + rules, tasks, activities, My Day
├── pipeline/      meetings, opportunity, stages, exit-step checklists, temperature, attachments
├── qualification/ SQL checklist, submit (books L1), validate / reject, AE corrections
├── assignment/    queue, capacity + recommendation, assign / reassign, bulk on deactivation
├── lifecycle/     60-day flag, to-be-moved, inactive, revisit, revival, reactivation
├── outreach/      templates, snippets, sequence engine, mailbox, unsubscribe, monitor
├── notification/  every PRD trigger, reminders, daily digest
├── reporting/     dashboards, reports, targets, drill-down
└── importer/      CSV / XLSX import with column mapping and duplicate report
```

Inside a module: `api/` (public), `domain/` (framework-free rules, unit tested), `application/` (use cases), `infrastructure/`
(JPA entities, adapters), `web/` (controllers).

**Rules that keep modules standalone**
- Cross-module calls go through `api` interfaces (`AccountApi`, `StakeholderApi`, `PipelineApi`, `TaskApi`, `ActivityApi`, `ConfigApi`, `AuditApi`, `UserDirectory`).
- Side effects travel as domain events (`SqlSubmitted`, `MeetingBooked`, `StakeholderEngagementChanged`, `AccountAssigned`, …). Notifications and the timeline listen; publishers don't know about them.
- No JPA relationships across modules: other modules' records are referenced by UUID.
- Read models (dashboards, reports, lists) are plain SQL over the schema (CQRS-style), checked against Postgres.

**Design patterns where they pay off**

| Pattern | Where |
|---|---|
| Strategy | `IcpCriterion` (4 ICP rules), `AllocationStrategy` (capacity, round-robin), `RowReader` (CSV, XLSX), `MailGateway` |
| Specification | `QualificationEngine` combines criteria; each pass / fail / needs-approval is stored |
| State | `StagePolicy`: derived stage from completed meetings + exit steps; allowed manual moves |
| Rule table | `call_outcome_rule` + `OutcomePlanner`: admins change outcome behaviour without a deploy |
| Observer / domain events | notifications, timeline entries, SQL status follow meetings, sequences stop on L1 |
| Adapter / port | `MailGateway` (log or Gmail), `GoogleTokenVerifier` |
| Facade | each module's `api` interface |
| Policy | `AccessPolicy`: the PRD permission table in one tested class, with admin overrides |
| Template method | importer: parse, map, dry-run, commit |

**Conflict handling**
- Same company: matched on normalized name or domain. A BDR adding it joins the existing account and sees who already works which stakeholder (name, designation, BDR only). `INSERT … ON CONFLICT` handles two BDRs adding the same company at the same moment.
- Same person (email, LinkedIn or phone): blocked with the owner's name; never silently duplicated.
- Two edits at once: `version` columns; the second save gets HTTP 409 `EDIT_CONFLICT`.
- BDR vs AE numbers: the SQL submission is an immutable snapshot; AE corrections are separate rows, so reports compare both.
- Nothing in the pipeline is deleted. Inactive is a status; every owner, stage, status and temperature change is recorded.

---

## 4. API overview (122 endpoints; full list in Swagger)

| Area | Endpoints |
|---|---|
| Auth | `POST /api/auth/google`, `GET /api/auth/me`, `POST /api/auth/dev-login` (local) |
| Admin | `/api/admin/users` (create, role, deactivate → owned accounts), `/api/admin/picklists`, `/settings/{key}`, `/cities`, `/size-buckets`, `/designations` (+ `/review`), `/icp-rules`, `/call-outcome-rules`, `/stage-thresholds`, `/checklist-items`, `/permissions`, `/email-templates`, `/email-snippets` |
| Accounts | `POST /api/accounts` (create or join), `GET /api/accounts` (filters, `status=ACTIVE/INACTIVE/ALL`, `format=csv/xlsx`), `GET/PATCH /api/accounts/{id}`, `/multithreading`, `/timeline`, `/stakeholders`, `/api/search`, `/api/saved-views` |
| Stakeholders | `PATCH /api/stakeholders/{id}`, `/engagement`, `/emails`, `/phones`, `/do-not-contact`, `POST /api/phones/{id}/invalid`, DPDP erase |
| Calling | `GET /api/my-day`, `GET /api/stakeholders/{id}/calls/preview`, `POST /api/stakeholders/{id}/calls`, `POST /api/activities`, `/api/tasks` |
| Sequences | `PUT /api/mailbox`, `POST /api/sequences/start`, `/enrollments/{id}/stop|pause`, `GET /api/sequences/monitor`, `GET /public/unsubscribe/{id}` |
| SQL | `POST /api/accounts/{id}/sql/checklist`, `POST /api/accounts/{id}/sql`, `GET /api/sql`, `POST /api/sql/{id}/validate`, `POST /api/sql/{id}/corrections` |
| Assignment | `GET /api/assignment/queue`, `GET /api/assignment/capacity`, `POST /api/accounts/{id}/assign`, `POST /api/stakeholders/{id}/reassign-bdr`, `POST /api/assignment/bulk` |
| Pipeline | `/api/accounts/{id}/meetings`, `POST /api/meetings/{id}/outcome|reschedule`, `GET /api/meetings/overdue-outcomes`, `/api/opportunities/{id}` (+ `/stage`, `/checklist/{itemId}`), `/api/accounts/{id}/attachments` |
| Lifecycle | `GET /api/lifecycle/to-be-moved`, `POST /api/accounts/{id}/inactive|keep-active|reactivate`, `GET /api/lifecycle/revival-queue` |
| Reporting | `/api/dashboards/bdr|ae|leadership|hygiene|drilldown`, `/api/reports/l1-not-progressed|stakeholder-coverage|inactive-reasons|workload|ageing|sql-quality|sequence-performance`, `/api/targets` |
| Other | `/api/notifications`, `/api/audit/{entity}/{id}`, `/api/imports` (upload, rows, commit) |

Errors use RFC 7807 `ProblemDetail` with a `code` field (`SQL_CRITERIA_FAILED`, `STAKEHOLDER_EXISTS`, `EDIT_CONFLICT`, …).

---

## 5. Database
- `V1__init.sql`: 42 tables, 4 views, triggers (size bucket, stage sync + history, activity recency, updated_at), PRD seed data.
- `V2__login_views_seed.sql`: Google login columns, saved views, acting user on stage history, Appendix A templates and snippets, the default 4-step sequence, the remaining picklists and the full 56-title Appendix B designation map.
- Everything lives in schema `crm`, which Supabase's auto REST API does not expose. Only this backend reaches the tables.

---

## 6. Not done yet / next steps
1. **Gmail adapter** (PRD M3-1). The sequence engine, scheduling, stop rules, logging and monitor are built and tested against a fake.
   Still needed: Google OAuth consent per BDR (scope `gmail.send` + `gmail.readonly`), storing the refresh token encrypted in
   `user_mailbox.refresh_token_encrypted`, and a `GmailMailGateway implements MailGateway` (users.messages.send, threads.get).
   Set `MAIL_PROVIDER=gmail` to switch.
2. **Sheet migration profiles.** The importer handles mapping, normalization ("NA" → empty, LinkedIn params, phones, names) and duplicates.
   The workbook-specific clean-up in the PRD (splitting "BDR Name" into BDR + lead source, rebuilding inactive data from "Deleted",
   1899 dates, the 27 accounts both active and inactive, PRD decision #14 for the 127 stuck sequences) needs the actual sheets.
3. **Google Calendar** (M6-8, P2) and **Apollo API** (phase 3) are not in v1, as the PRD plans.
4. Daily digest is stored as an in-app notification; emailing it needs the Gmail adapter.
5. Stagnation reason (M6-7) is surfaced as a flag on the opportunity and in the hygiene dashboard, not as a hard block on saving.
