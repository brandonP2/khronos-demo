# Khronos

Khronos is a REST API for scheduling staff at venues such as restaurants, hotels or event halls. An **owner** registers their establishments and splits each one into **zones** (for example *Terrace*, *Kitchen* or *Main Hall*). The owner then schedules their **workers** into shifts in those zones and assigns them to **events** happening there.

The API stops scheduling mistakes before they are saved. For example, it won't let a worker have two overlapping shifts, and it won't assign a worker to an event that their shift doesn't cover.

## Tech stack

| | |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 4 (Web MVC, Data JPA, Validation, Security, Actuator) |
| Database | PostgreSQL (Supabase in production) |
| Migrations | Flyway |
| Auth | JWT bearer tokens (HS256), valid for 1 hour, stateless |
| Email | Resend |
| Docs | springdoc OpenAPI / Swagger UI |
| Build | Maven (wrapper included), Docker |
| CI | GitHub Actions: the tests run against PostgreSQL on every push and pull request |

## Domain model

<p align="center">
  <img width="466" height="582" alt="image" src="https://github.com/user-attachments/assets/790faaea-37d8-4e96-8b22-af4c2697d1b8" />
</p>
<br>

| Entity | What it represents |
|---|---|
| **User** | An account that can log in. It has a username, password, name, email, optional phone number, a **role** and who created it. |
| **Establishment** | A venue, with a name and a unique address. Each one belongs to one user with the `OWNER` role. |
| **Zone** | An area inside an establishment. Zone names are unique within their establishment. |
| **Worker** | A user with the `WORKER` role who works at one establishment. The worker shares its ID with its user, and it can be marked active or inactive. |
| **WorkerShift** | A block of time a worker spends in a zone: a date, a start time and an end time. |
| **Event** | Something scheduled in a zone at a specific date and time, with a title, an expected number of people and a description. |
| **WorkerEvent** | The assignment of a worker to an event. |

Deleting a parent also deletes everything under it. For example, deleting an establishment removes its zones, workers, shifts and events.

## Roles and permissions

| Role | What they can do |
|---|---|
| `PLATFORM_ADMIN` | Everything. This is the only role that creates users of any role and creates or deletes establishments. |
| `OWNER` | Manages their own establishments: edits them and manages their zones, workers, shifts, events and assignments. They can create users with the `WORKER` role, link to their establishments only the accounts they created, and reset their workers' passwords. |
| `WORKER` | Can read their own profile, shifts and event assignments, and see the zones, events and assignments of the establishment they work at. They see who the owner is but not the owner's email or phone. They can change their own password. |

Each endpoint checks access with `@PreAuthorize` rules backed by `AccessGuard`. A request for data that belongs to another owner returns **403**.

## Authentication

Clients sign in once and then send a short-lived token with every request.

1. `POST /api/auth/login` with `{ "username": "...", "password": "..." }`. This is the only endpoint that accepts a password.
2. The response holds the token and when it expires:
   ```json
   { "accessToken": "eyJ...", "tokenType": "Bearer", "expiresAt": "2026-11-17T15:42:00Z", "expiresIn": 3600 }
   ```
3. Every other request sends `Authorization: Bearer <accessToken>`.

The token is a JWT signed with HS256. It holds only the user id (`sub`), the role, and the `iat` and `exp` times. **It expires one hour after login.** After that the API answers **401** and the client has to sign in again. There is no refresh token in this beta, so sessions stop at the one-hour mark.

The user is reloaded from the database on every request, so some changes take effect before the token expires:

| Change | Effect on existing tokens |
|---|---|
| A worker is deactivated | Rejected immediately with **401** "This account is disabled" |
| The user changes their password, or an admin or owner resets it | Every token issued before the change is rejected, which signs out other devices |
| The user is deleted | Rejected immediately |
| The role or username changes | The token keeps working with the new values, because it identifies the user by id |

HTTP Basic is no longer accepted. If it were, anyone holding a password could skip the one-hour limit.

**Failed logins are limited.** After 5 failed attempts for the same username from the same IP address within 15 minutes, `POST /api/auth/login` answers **429 Too Many Requests** with a `Retry-After` header (in seconds) until those 15 minutes are over, even if the password is right. A successful login clears the count. The counts are kept in memory, per instance, and reset on restart. Behind a reverse proxy, set `SERVER_FORWARD_HEADERS_STRATEGY=native` so the API sees the client's IP and not the proxy's.

| Setting | Environment variable | Default |
|---|---|---|
| Signing secret (at least 32 bytes) | `JWT_SECRET` | none, the app refuses to start without it |
| Token lifetime (ISO-8601 duration) | `JWT_TTL` | `PT1H` |
| Failed logins allowed per username and IP | `LOGIN_MAX_ATTEMPTS` | `5` |
| Window for those failures (ISO-8601 duration) | `LOGIN_WINDOW` | `PT15M` |

Generate a secret with `openssl rand -base64 48`. Keep it out of version control. Changing it signs everyone out.

### First platform admin

Only a platform admin can create users, so the first one is created when the app starts. Set all three of `APP_ADMIN_USERNAME`, `APP_ADMIN_PASSWORD` and `APP_ADMIN_EMAIL`. If no user has that username yet, the app creates a `PLATFORM_ADMIN` with that password. If the user already exists it is left exactly as it is, so the variables can stay set. If any of the three is missing, nothing happens. The password must be at least 8 characters and at most 72 bytes, or the app refuses to start. It is never logged.

## Business rules

These rules are enforced in the service layer. Breaking one returns **409 Conflict** with a message explaining why.

**Users**
- Usernames, emails and phone numbers must be unique.
- Passwords need at least 8 characters and at most 72 bytes (BCrypt's limit; accented letters and emoji take more than one byte). Longer ones answer **400**.
- To change a password, the user must send their current password.
- A platform admin can reset the password of any user except another platform admin, and an owner can reset the password of their own workers, without the current password.
- A platform admin can't be deleted through the API.

**Establishments and zones**
- Two establishments can't share an address.
- An establishment's owner must have the `OWNER` role.
- A zone can't be moved to another establishment.

**Workers**
- Only users with the `WORKER` role can be registered as workers, and each user only once.
- An owner can only link an account they created themselves; otherwise the request gets **403**. Accounts created before this rule existed have no recorded creator, so only a platform admin can link them.
- A worker can't move to another establishment while they still have shifts or events.
- An inactive worker can't sign in, get new shifts or be assigned to events.

**Shifts**
- The end time must be after the start time, so a shift can't run past midnight.
- The zone must belong to the worker's establishment.
- A worker's shifts can't overlap on the same day. This also holds when two requests arrive at the same time: changes to the same worker's schedule take turns, so the second one sees the first.
- Shifts are created **in batches**: `POST /api/worker-shifts` takes a list. The whole batch is saved in a single transaction, so if any shift is invalid, none of them are saved. The error says which shift failed, for example `Shift [1]: ...`. The list is also checked against itself, so two shifts in the same request can't overlap.

**Events and assignments**
- Events are created **in batches**: `POST /api/events` takes a list, possibly across several zones. It's all or nothing: if one item is invalid or its zone doesn't exist, no events are saved.
- A batch of shifts, events or assignments can hold at most 200 items (`BATCH_MAX_SIZE`). A longer list answers **400**.
- An event with workers assigned to it can't be moved to another establishment.
- Assignments are created **in batches**: `POST /api/worker-events` takes a list, so an owner can assign one worker to several events (or several workers at once) in one request. As with shifts, it's all or nothing, and the error names the failing item, for example `Assignment [2]: ...`.
- A worker can only be assigned to an event once, including twice in the same batch.
- A worker can only be assigned to an event in their own establishment, and only if one of their shifts covers it: the shift must be in the event's zone, on the event's date, and running at the event's start time.
- If a worker's shift is edited or deleted and it no longer covers an event they are assigned to, they are unassigned from that event automatically. The same happens when an event is moved to a time or zone that the worker's shifts don't cover.

## Email notifications

Workers get an email through [Resend](https://resend.com) whenever their schedule changes:

| Trigger | Email |
|---|---|
| A shift is created for them (one per shift in a batch) | New shift |
| One of their shifts is edited | Shift changed, showing before and after |
| One of their shifts is deleted, or moved to another worker | Shift cancelled |
| They are assigned to an event (one per assignment in a batch) | Assigned to event |
| An event they are assigned to is edited | Event changed, showing before and after |
| They are unassigned, the event or its zone is deleted, or their shift no longer covers the event | No longer assigned, with the reason |

Emails are sent asynchronously and only after the transaction commits, so a rejected request (for example, a batch of shifts where one fails) sends nothing. A failed delivery never fails the API request: it is logged as a warning (recipient only, never the body). Every attempt, successful or not, is recorded in the `email_logs` table.

Resend is configured with `RESEND_API_KEY` (required) and `RESEND_FROM` (optional, defaults to `Khronos <onboarding@resend.dev>`). Calls to Resend give up after `RESEND_CONNECT_TIMEOUT` (default `PT5S`) to connect and `RESEND_READ_TIMEOUT` (default `PT10S`) to answer.

## API overview

All endpoints are under `/api` and need a bearer token, except `POST /api/auth/login` and `GET /actuator/health`. The full interactive reference is in Swagger UI (see [Running locally](#running-locally)).

| Resource | Base path | Notes |
|---|---|---|
| Auth | `/api/auth` | `POST /login` returns a token valid for one hour. |
| Users | `/api/users` | `GET /me` returns the logged-in user. `POST /create?role=...` is for admins only. `POST /create/worker` creates a user with the `WORKER` role. `PUT /{id}/password` changes the password and revokes the user's older tokens. `PUT /{id}/password/reset` lets an admin or owner set a new one. `GET /establishment/{id}` lists the accounts of an establishment's workers. |
| Establishments | `/api/establishments` | You can also list them by owner: `/owner/{ownerId}`. |
| Zones | `/api/zones` | You can also list them by establishment: `/establishment/{id}`. |
| Workers | `/api/workers` | Identified by their user ID. You can list them by establishment. `POST /with-user` creates the account and links it in one step. `PUT /{id}/active` turns a worker on or off. |
| Worker shifts | `/api/worker-shifts` | `POST` takes a **list**. You can list shifts by owner, worker or zone. |
| Events | `/api/events` | `POST` takes a **list**. You can list events by zone or establishment. |
| Worker events | `/api/worker-events` | `POST` takes a **list** of worker/event pairs. You can list assignments by worker, event or establishment. |
| Health | `/actuator/health` | Public. Answers `{"status":"UP"}` without details, or 503 if the database is down. |

**Date filters.** Every listing of shifts (by owner, worker or zone), events (by zone or establishment) and assignments by establishment accepts optional `from` and `to` query parameters, as `YYYY-MM-DD`, both inclusive. Events and assignments are filtered by the event's date. Without them the whole list comes back. For example `GET /api/worker-shifts/owner/3?from=2026-11-01&to=2026-11-30`. `from` after `to` answers **400**.

### Endpoints added in this version

| Method and path | Body | Answer | Who |
|---|---|---|---|
| `POST /api/workers/with-user` | `{ "establishmentId": 1, "user": { same fields as POST /api/users/create/worker } }` | **201** `{ "user": {...}, "worker": {...} }`. The account and the link are saved together or not at all. | The establishment's owner or an admin |
| `PUT /api/workers/{id}/active` | `{ "active": false }` | **200** the worker. Repeating it changes nothing. | The worker's owner or an admin |
| `PUT /api/users/{id}/password/reset` | `{ "newPassword": "..." }` | **204**. The user's older tokens stop working. | An admin (not on another admin) or the worker's owner |
| `GET /api/worker-events/establishment/{id}?from&to` | | **200** list of assignments | The owner, its workers or an admin |
| `GET /api/users/establishment/{id}` | | **200** list of users | The owner or an admin |

`DELETE /api/workers/deactivate/{id}` still works but is deprecated: it toggles the flag, so a retried request undoes itself. Use `PUT /api/workers/{id}/active`.

### Typical flow

0. Each user signs in with `POST /api/auth/login` and sends the returned token as `Authorization: Bearer ...` on the calls below.
1. An admin creates a user with the `OWNER` role (`POST /api/users/create?role=OWNER`) and an establishment for them (`POST /api/establishments`).
2. The owner creates zones (`POST /api/zones`).
3. The owner creates each worker's account and links it to the establishment in one call (`POST /api/workers/with-user`), or in two steps with `POST /api/users/create/worker` and `POST /api/workers`.
4. The owner schedules shifts:
   ```json
   POST /api/worker-shifts
   [
     { "workerId": 5, "zoneId": 2, "date": "2026-11-14", "startTime": "18:00", "endTime": "23:00" },
     { "workerId": 6, "zoneId": 2, "date": "2026-11-14", "startTime": "17:00", "endTime": "22:00" }
   ]
   ```
5. The owner creates events:
   ```json
   POST /api/events
   [
     { "title": "Wedding dinner", "dateTime": "2026-11-14T19:00:00", "zoneId": 2, "peopleNumber": 120, "description": "Set menu" },
     { "title": "Cocktail hour", "dateTime": "2026-11-14T21:00:00", "zoneId": 2, "peopleNumber": 80 }
   ]
   ```
6. The owner assigns workers whose shifts cover those events:
   ```json
   POST /api/worker-events
   [
     { "workerId": 5, "eventId": 10 },
     { "workerId": 5, "eventId": 11 }
   ]
   ```

### Error format

Every error comes back in the same shape:

```json
{
  "timestamp": "2026-11-10T10:15:30Z",
  "status": 409,
  "error": "Conflict",
  "message": "Shift [1]: The worker already has a shift that overlaps with ...",
  "path": "/api/worker-shifts",
  "fieldErrors": { "[0].startTime": "must not be null" }
}
```

`fieldErrors` only appears on validation errors.

| Status | When |
|---|---|
| 400 | Validation failed, or the JSON, a parameter or a date/time format is invalid, or a batch has too many items |
| 401 | Wrong username or password at login, or the token is missing, expired, revoked or belongs to a disabled account |
| 403 | You aren't allowed to access the resource |
| 404 | The resource or endpoint doesn't exist |
| 409 | A business rule was broken, or the data conflicts with existing records |
| 429 | Too many failed logins; wait the seconds given in `Retry-After` |

## Running locally

Copy `.env.example` to `.env` and fill it in. It lists every variable the app reads, with its default. At least set `JWT_SECRET` (`openssl rand -base64 48`), `RESEND_API_KEY` and, on a new database, `APP_ADMIN_USERNAME`, `APP_ADMIN_PASSWORD` and `APP_ADMIN_EMAIL` so you can sign in.

**With Docker** (no Java or PostgreSQL needed):

```bash
docker compose up --build
```

This starts PostgreSQL 16 and the API on http://localhost:8080. The API reads `.env` but always uses the compose database, whatever `DB_URL` says. `docker compose down -v` deletes the database.

**With Maven**, against your own PostgreSQL (the `DB_*` variables in `.env`): run `./mvnw spring-boot:run`, or start `KhronosDemoApplication` from your IDE.

Swagger UI is at http://localhost:8080/swagger-ui.html: call `POST /api/auth/login`, then paste the `accessToken` into **Authorize**.

To let a browser app on another origin call the API, list it in `CORS_ALLOWED_ORIGINS` (comma-separated, for example `http://localhost:5173`). It is empty by default, which allows no cross-origin calls.

### Database migrations

Flyway owns the schema. The scripts are in `src/main/resources/db/migration` and run on startup; Hibernate only checks that the entities match (`ddl-auto=validate`).

- `V1__baseline.sql` is the schema that `ddl-auto=update` used to create.
- On a database that already has those tables but no Flyway history (for example the Supabase database), the first start marks V1 as applied without running it, keeps the data, and runs only V2 onwards (`baseline-on-migrate`). `V2` adds the pieces an older database might be missing.
- Any schema change goes in a new script, `V4__what_it_does.sql`, `V5__...`. Never edit a script that has already run anywhere.

### Tests

The integration tests start the whole app against a real, **empty** PostgreSQL database given by `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`, and empty every table before and after each test. Never point them at a database whose data you need: the app also reads `DB_URL` from `.env`, so override it as below. As a safety net the tests refuse to start, before migrating anything, unless the database name contains `test`. `JWT_SECRET` and `RESEND_API_KEY` must also be set (any values; no email is sent).

```bash
docker run -d --name khronos-test-db -p 5433:5432 -e POSTGRES_USER=khronos -e POSTGRES_PASSWORD=khronos -e POSTGRES_DB=khronos_test postgres:16-alpine
DB_URL=jdbc:postgresql://localhost:5433/khronos_test DB_USERNAME=khronos DB_PASSWORD=khronos \
JWT_SECRET=test-only-secret-0123456789-abcdefghijkl RESEND_API_KEY=test ./mvnw test
```

`./mvnw test -Dtest=TokenFlowTests` checks the token rules without a database. CI runs the same suite on every push and pull request (`.github/workflows/ci.yml`).

## Project structure

```
src/main/java/com/moga/khronosdemo
├── config/        OpenAPI and password encoder beans, first admin bootstrap
├── controllers/   REST endpoints and @PreAuthorize rules
├── dtos/          Request DTOs (with validation) and response DTOs
├── entities/      JPA entities and the Role enum
├── exceptions/    Domain exceptions and GlobalExceptionHandler
├── notifications/ Notification events and the listener that emails workers
├── mappers/       Conversions between entities and DTOs
├── repositories/  Spring Data JPA repositories, including the overlap and coverage queries
├── security/      SecurityConfig, AccessGuard, the user-details service, JWT issuing and checking, login limits
├── services/      Service interfaces and their implementations (business rules)
└── validation/    Custom constraints: batch size and password length in bytes

src/main/resources/db/migration   Flyway migrations
```
