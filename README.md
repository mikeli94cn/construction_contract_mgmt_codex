# Construction Contract Management

The current slices provide local account activation, browser sign-in, organization selection, member administration, and a project directory with project-level role assignments. Organization administrators can create, archive, and reactivate projects. Project managers can edit project details and manage assignments. Contract editors and viewers can read assigned projects. The [architecture and delivery plan](docs/architecture-and-delivery-plan.md) describes the remaining slices.

## Run locally

Use Docker Compose and an untracked `.env` file in the project root. Set `MYSQL_PASSWORD` and `MYSQL_ROOT_PASSWORD` to distinct generated secrets. For the first boot, set `APP_BOOTSTRAP_ENABLED=true`, `APP_BOOTSTRAP_CODE`, `APP_BOOTSTRAP_NAME`, `APP_BOOTSTRAP_EMAIL`, and `APP_BOOTSTRAP_PASSWORD`. The administrator password must be at least 12 characters and at most 72 UTF-8 bytes. Remove the bootstrap variables and recreate the backend after the initial sign-in.

```sh
docker compose up --build --detach
```

Open `http://localhost:8080` and sign in with the bootstrap account. An organization administrator can add a member and give the new account holder the one-time activation link using a private channel. The link expires after 24 hours. Password reset and activation link reissue are pending. Authenticated API documentation is available at `/api/openapi`.

The Compose deployment is for loopback development and uses HTTP, a non-Secure session cookie, and a single application and migration database identity. Production deployment requires TLS, secure cookies, separate migration credentials and restricted runtime permissions, including insert-only access to audit events.

## Verification commands

Use JDK 21, Maven, Node 22.12 or later, and Docker for the MySQL integration test.

```sh
mvn -f backend/pom.xml verify
mvn -f backend/pom.xml -Dtest=IdentityHttpIT test
npm --prefix frontend ci
npm --prefix frontend test
npm --prefix frontend run build
```

For the browser smoke check, sign in as the bootstrap administrator, provision a new member, activate the account in a separate browser session, and sign in as the new member. Verify that the member cannot open the member directory and that disabling membership revokes organization access.

For the project smoke check, create a project as the organization administrator and assign an active member the project viewer role. In the member session, open the project and verify the edit and membership controls are absent. Remove the assignment in the administrator session and verify the project disappears after refreshing the member session. Disabling an organization membership suspends access. Existing assignments become effective again if the membership is re-enabled, unless removed separately.
