# Construction contract management design

Status: proposed for approval

## Scope and decisions to confirm

The initial MVP lets an internal user sign in, work within an assigned project, manage counterparties where authorized, and create and edit a draft contract. The subsequent slices take that draft through approval, documentation, change orders, payment recording, and final settlement.

| Decision | Proposed baseline | When to confirm |
| --- | --- | --- |
| Organization and identity | Internal staff only. Organizations are data boundaries. A user may belong to several and explicitly selects an active organization. Local accounts initially, with administrator-led provisioning and one-time activation. | Before identity slice. Confirm SSO, invitations, and recovery requirements. |
| Access | Organization-level administration and partner management. Project membership is required for operational access. Organization administrators can manage project access and see the project directory. | Before project slice. Confirm any company-wide oversight and external access. |
| Contract model | One project and one legal counterparty per contract. User-entered project-scoped reference. One currency and a lump-sum base amount. | Before contract slice. Confirm contract directions, numbering, and whether schedules of values are needed. |
| Approval | Serial internal approval, no self-approval, assignee and permission checked when deciding. Final internal approval establishes the financial baseline. | Before workflow slice. Confirm thresholds, delegation, escalation, and whether signing or a separate activation step controls effectiveness. |
| Financial policy | Track contractual value and payment activity. Model taxes, retention, advances, certification and reversals as explicit decisions before payment delivery. | Before financial slices. Confirm tax basis, rounding, payment caps, partial payments, and corrections. |
| Documents and settlement | Private versioned files; settlement based on a reconciled snapshot. | Before respective slices. Confirm storage, signing evidence, retention, reporting, and closure or reopening policy. |

## Architecture and decision record

- **Runtime.** Java 21 LTS and Spring Boot 4.1.2 with Spring MVC, Spring Security, Spring Data JPA and Hibernate. Use the Maven wrapper and Boot dependency management. Publish OpenAPI with springdoc 3.2.0 and verify the dependency combination in slice 1. Use MySQL 8.4 LTS with InnoDB and Flyway. [Spring Boot project](https://spring.io/projects/spring-boot/) · [Java compatibility](https://docs.spring.io/spring-boot/system-requirements.html) · [springdoc compatibility matrix](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot) · [MySQL 8.4 manual](https://dev.mysql.com/doc/refman/8.4/en/)
- **Deployment.** One backend deployable and one database. Vue 3, TypeScript, Vite and Element Plus provide the browser UI. Deploy the built UI and API on the same origin. Docker Compose provides a reproducible local environment. Inject database and bootstrap credentials at runtime. Production uses TLS, a migration identity with DDL permissions, and a restricted application identity. Hibernate validates the Flyway-managed schema.
- **Module boundary.** Organize backend packages by feature, each with `api`, `application`, `domain`, and `infrastructure`. API controllers validate transport DTOs and delegate. Application services authorize and own transaction boundaries. Domain objects enforce invariants and state transitions. Infrastructure handles JPA and external adapters. Cross-feature calls use published application interfaces and IDs. Each module owns its tables and repositories. Enforce dependency direction and cycle freedom in an automated architecture check.
- **Identity and access.** Spring Security with server-side sessions, secure HttpOnly session cookies, CSRF protection for mutations, session rotation at sign-in, and adaptive password hashing. Use organization membership, role permissions and project or workflow scope on every protected use case. Resolve caller identity and organization server-side. Apply scope in list queries and resource lookups, including files, audit and exports. Recheck active memberships at request time. Audit role and scope changes.
- **Consistency.** Use a transaction for each command and its audit entry. Use optimistic version checks for draft edits, and serialize financial postings and settlement using the contract row as a lock acquired by all relevant writers. Recheck availability and workflow state inside that transaction. For retryable postings, persist an idempotency key and request fingerprint with the outcome in the same transaction.
- **Financial history.** Freeze submitted revisions. Approval records reference the exact frozen revision, including its amount, currency and counterparty snapshot. A rejection or withdrawal permits a new draft revision. After baseline approval, amount adjustments post through approved change orders. Correct posted items through linked reversal or adjustment records and audit the actor, reason and target. Restrict UPDATE and DELETE on append-only posting, decision and audit tables. Reports derive effective committed value from the approved base and posted change entries, and sum disbursement entries separately by currency.

## Modules and dependencies

Dependencies below refer to application interfaces. A command that needs both a workflow decision and an aggregate transition runs in the owning aggregate's application service. The generic workflow does not call back into business modules. The audit writer accepts an event envelope and has no dependency on business modules. Reporting reads scoped query interfaces.

| Module | Owns | Uses |
| --- | --- | --- |
| Organization and users | Credentials, activation, organization membership and organization roles | Audit writer |
| Projects | Project directory, project lifecycle and project memberships | Organization and users, audit writer |
| Business partners | Organization-wide counterparty registry and lifecycle | Organization and users, audit writer |
| Contracts | Contract identity, draft revisions, baseline and lifecycle | Projects, partners, approvals, audit writer |
| Approvals | Policy versions, approval instances, assigned steps and decisions | Organization and users, audit writer |
| Documents | Private file metadata, versions and contract attachments | Contracts for authorization, audit writer |
| Change orders | Proposed changes and approved adjustments | Contracts, approvals, audit writer |
| Payments | Payment applications, certification and disbursement records | Contracts, change orders, approvals, audit writer |
| Final settlement | Reconciliation snapshot, settlement approval and closure | Contracts, change orders, payments, approvals, audit writer |
| Audit and reporting | Append-only audit writer and scoped read models | Reporting reads published module queries and audit events |

## Initial relational schema

Use InnoDB, UTF-8, internal BIGINT keys exposed as strings in DTOs, UTC timestamps with microsecond precision, and DATE for business dates. Mutable aggregate roots carry a BIGINT `version` column. Status fields use constrained strings, and foreign keys default to restricted deletion. Use composite organization-scoped foreign keys for tenant-owned relations, backed by unique keys on `(organization_id, id)` when needed. Store amounts as `DECIMAL(19,4)` and currency as a three-character code. Parse with BigDecimal, reject values outside storage bounds or the currency's accepted scale, and send amounts as decimal strings in the API.

| Slice | Table | Key fields and constraints |
| --- | --- | --- |
| 1 | `organizations` | ID, unique code, name, status, version |
| 1 | `users` | ID, unique normalized email, display name, nullable password hash until activation, status, version |
| 1 | `organization_memberships` | Organization ID, user ID, status, version. Primary key on organization and user. |
| 1 | `organization_membership_roles` | Organization ID, user ID, role code. Unique per membership and role. |
| 1 | `account_activation_tokens` | Token digest, user ID, expiry and consumed timestamp. Digest unique. |
| 1 | `audit_events` | ID, organization ID when known, actor ID if known, action, resource type and ID, timestamp, request ID and redacted change summary. Index by organization and timestamp, and by organization and resource. |
| 2 | `projects` | ID, organization ID, code, name, status, version. Unique on organization and normalized code. |
| 2 | `project_memberships` | Organization ID, project ID, user ID, project role. Unique per project, user and role. Composite references to project and organization membership. |
| 3 | `business_partners` | ID, organization ID, reference code, legal name, optional registration identifier and contact, status, version. Unique organization-scoped normalized reference. |
| 4 | `contracts` | ID, organization ID, project ID, reference, status, version. Unique on organization, project and normalized reference. |
| 4 | `contract_revisions` | Organization ID, contract ID, revision number, partner ID, title, scope summary, currency, base amount, optional start and end dates, creator and timestamps. Unique on organization, contract and revision number. FK to same-organization partner. Check amount is nonnegative and end date follows or equals start date when both exist. |

Draft terms are edited through the contract aggregate and share its version. Slice 5 adds revision freezing, the legal counterparty snapshot, and an approved revision reference constrained to the same contract. Create list indexes from actual query shapes, starting with organization and project/status on contracts and organization and user on project memberships. Deactivation preserves references. Contract creation requires an active project and partner. Existing drafts may be read after deactivation, and submission rechecks eligibility.

Planned migrations extend the schema when the relevant use cases arrive:

| Slice | Added tables and invariants |
| --- | --- |
| 5 | `approval_policies`, `approval_policy_versions`, `approval_instances`, `approval_steps`, `approval_decisions`. Instances refer to an immutable policy version and frozen subject revision through owner-side FK links. Steps are unique per instance and sequence. Decisions are append-only and unique per step. The owner ensures one active submission per subject under lock. |
| 6 | `documents`, `document_versions`, `contract_documents`. Version sequence unique per document. Store private object key, digest, size, content type and scan status. Only clean versions can be downloaded. Finalize an upload after storage verification and reconcile incomplete uploads. |
| 7 | `change_orders`, `change_order_revisions`, `change_postings`. Frozen approved revision produces one signed posting in contract currency. Reversal links identify the original posting. |
| 8 | `payment_applications`, `payment_application_revisions`, `payment_certifications`, `payment_disbursements`, `idempotency_records`. Certified and disbursed values remain distinct. Disbursements and reversals reference their source and are append-only. |
| 9 | `settlements`, `settlement_revisions`, with frozen totals and references to the reconciled baseline, postings and certifications. Closure verifies the snapshot against current records under the shared contract lock. |

## MVP REST API

All paths are relative to `/api/v1`. IDs in paths are strings. JSON errors use Problem Details with a stable application code and optional field errors. Creates return 201 and Location. List responses include items, page, size and total, with bounded page size and a stable ID tie-breaker. Detail reads return ETags for mutable resources. Mutations of existing versioned resources require `If-Match` and return 428 when absent or 412 when stale. Return 409 for uniqueness and state conflicts, 401 for unauthenticated requests, 403 for denied actions within visible scope and 404 for inaccessible resource IDs.

| Slice | Method and path | Request or outcome |
| --- | --- | --- |
| 1 | GET `/auth/csrf` | CSRF token for browser mutations |
| 1 | POST `/auth/login`, POST `/auth/logout` | Email and password create a session. Logout invalidates it. |
| 1 | GET `/auth/me`, PUT `/auth/organization` | Caller identity, active memberships and roles. Switch active organization with `organizationId`. |
| 1 | POST `/auth/activation` | Consume one-time token and set initial password. |
| 1 | GET and POST `/organizations/{orgId}/members`, PATCH `/organizations/{orgId}/members/{userId}` | Organization admin lists members, provisions a member by email with roles or changes membership status and roles. Provisioning an existing account never grants a password reset. |
| 2 | GET and POST `/projects`, GET and PATCH `/projects/{projectId}` | Manage code, name and status. List only visible projects. |
| 2 | GET and PUT `/projects/{projectId}/members` | Read or replace the project role assignments under the project version. |
| 3 | GET and POST `/partners`, GET and PATCH `/partners/{partnerId}` | Manage reference code, legal name, registration and contact fields, and status. Contract authors can search active partners with a summary DTO. |
| 4 | GET and POST `/projects/{projectId}/contracts` | List or create project contracts. Create accepts reference, partner ID, title, scope summary, currency, decimal-string base amount and optional dates. Status starts at DRAFT. |
| 4 | GET and PATCH `/projects/{projectId}/contracts/{contractId}` | View or edit draft terms. Response includes project, partner, revision, status and terms. |

Organization administration manages account and project access. Project managers manage project assignments. Project contract editors create and edit drafts in assigned projects. Project viewers have read access in assigned projects. Partner managers maintain the organization registry. Approval, finance and auditor permissions enter with their slices and are evaluated against resource scope and action.

## Phased delivery

| Slice | User-facing increment | Acceptance evidence |
| --- | --- | --- |
| 1. Authentication and organization | Sign-in, activation, organization switch, member administration. Add Compose, secret injection and the initial migrations. | Sign-in and logout, expired and reused activation, CSRF, disabled membership, cross-organization denial, fresh MySQL migration. |
| 2. Projects | Project list, detail, create/edit and membership screen. | Active membership and role matrix, cross-organization rejection, stale edits, unique project code and assignment revocation. |
| 3. Partners | Search, create, edit and deactivate counterparties. | Scoped lists, reduced partner lookup DTO, permissions, duplicates and deactivation. |
| 4. Draft contracts | Project contract list, create/edit form and detail view. | Sign-in-to-draft browser flow, correct decimal round trip, cross-scope reference rejection, inactive project or partner rejection, duplicate reference and stale updates. |
| 5. Approval | Submit, approve, reject and view decision history. | Revision freeze, concurrent approval, assigned approver and self-approval checks, durable audit. |
| 6. Documents | Upload, version and download authorized attachments. | Version integrity, clean-file gating, unauthorized download denial, interrupted upload recovery. |
| 7. Change orders | Propose, approve and post adjustments. | Exact-once posting, signed amounts, protected baseline, traceable corrections. |
| 8. Payments | Submit applications, certify and record disbursements. | Concurrent cap checks, retry and duplicate handling, reconciliation and reversal history. |
| 9. Settlement and reporting | Reconcile, approve and close contracts. Provide scoped audit and financial reports. | Posting-versus-closure race checks, reproducible snapshot, access-scoped export and correctly grouped currency totals. |

Each slice includes backend, frontend, Flyway migrations and automated tests. Explicitly align the test dependencies to JUnit 5 and its matching Platform, and verify Maven test discovery in slice 1. Use JUnit 5 and Mockito for business rules and application interactions, MVC tests for API and security behavior, and Testcontainers with MySQL 8.4 for migrations, constraints, transactions and concurrency. Check both clean installation and upgrades as migrations accrue. Run Maven verify, frontend type checking, tests and production build, and a representative browser smoke test against the Compose deployment. Report which commands ran, their outcomes and any remaining work.
