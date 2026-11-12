# Payment Service — Project Documentation

## 1. Executive summary

This repository contains the **Payment Service** for a planned payments platform called **Parity**. Its current responsibility is narrow: accept a payment-creation request, persist a payment in a `PENDING` state, and make that creation safe to retry by using an idempotency key.

The service addresses a common distributed-systems failure mode: a client can time out after sending a request and retry without knowing whether the first request succeeded. Without protection, the retry could create a second payment. This implementation stores each idempotency key with a fingerprint of its request and uses a database uniqueness constraint to ensure that one logical request creates at most one payment, including when duplicate requests arrive concurrently.

The repository is **not yet a complete payment processor**. It does not move funds, contact a payment provider, update a wallet, publish events, authenticate callers, or expose payment lookup/status endpoints. Those capabilities appear in the broader roadmap in `FULL.md`, but they are not implemented here.

## 2. What problem the service solves

### Business problem

A caller needs to initiate a transfer-like payment from a source account to a destination account. Payment requests may be retried because of network failures, client timeouts, or duplicate delivery. A retry must not create a second payment.

### Implemented solution

The caller sends a unique `Idempotency-Key` header with the payment request. The service:

1. Validates the request.
2. Creates a deterministic SHA-256 fingerprint from the payment fields.
3. Looks up the idempotency key.
4. If the key already exists with the same fingerprint, returns the associated payment as a replay.
5. If the key exists with a different fingerprint, returns `409 Conflict`.
6. If the key is new, creates both the payment and its idempotency record in one transaction.
7. If two requests race, the database unique constraint selects one winner. The other transaction handles the constraint violation and replays the winner's result.

This guarantees one payment record per idempotency key as long as all creation goes through this service and its database constraints remain intact.

## 3. Current scope versus roadmap

| Capability | Current state |
|---|---|
| Create a payment | Implemented |
| Request validation | Implemented |
| Idempotent retry | Implemented |
| Concurrent duplicate protection | Implemented through a database unique constraint |
| Persist payment and idempotency record atomically | Implemented |
| Payment status lifecycle | Modelled as `PENDING`, `SUCCESS`, and `FAILED`; only creation as `PENDING` is implemented |
| Retrieve or list payments | Not implemented |
| Move or reserve funds | Not implemented |
| Wallet/account ownership | Not implemented; account IDs are accepted as references only |
| Kafka events / transactional outbox | Roadmap only |
| Ledger / double-entry accounting | Roadmap only |
| Order saga and compensation | Roadmap only |
| Authentication / authorization | Not implemented |
| Observability and correlation IDs | Roadmap only |
| Docker Compose / Testcontainers | Deferred in `TODO.md` |

## 4. Technology stack

| Area | Technology | Role |
|---|---|---|
| Language | Java 21 | Application language |
| Framework | Spring Boot 4.1.1 | Application bootstrapping and dependency management |
| HTTP API | Spring Web MVC | REST controller and HTTP response handling |
| Validation | Jakarta Bean Validation / Spring Validation | Header and request-body validation |
| Persistence | Spring Data JPA and Hibernate | Entity mapping and repositories |
| Database | MySQL | Durable payment and idempotency storage |
| Schema migrations | Flyway with MySQL support | Versioned database creation and validation |
| Boilerplate reduction | Lombok | Constructors, accessors, and builders |
| Build | Maven with Maven Wrapper | Compilation, dependency management, and tests |
| Tests | JUnit through Spring Boot test starters | Currently only an application-context smoke test |

Runtime configuration is in `src/main/resources/application.yaml`. The application listens on port `9090`, connects to `payment_db` on local MySQL, and reads credentials from `DB_USERNAME` and `DB_PASSWORD`.

## 5. Architecture

The code uses a conventional layered Spring architecture:

```text
HTTP client
    |
    v
PaymentController       HTTP mapping, validation, status-code selection
    |
    v
PaymentService          Idempotency lookup, fingerprinting, replay/conflict logic
    |
    +--> PaymentCreator Transactional creation boundary
    |        |
    |        +--> PaymentRepository
    |        +--> IdempotencyRecordRepository
    |
    +--> PaymentRepository / IdempotencyRecordRepository for replay
             |
             v
           MySQL
```

### Main components

- `PaymentController` exposes the HTTP endpoint and returns `201` for a new payment or `200` for a replay.
- `PaymentService` owns the idempotency algorithm and request fingerprint.
- `PaymentCreator` creates the payment and idempotency record inside one transaction.
- `PaymentMapper` converts API requests to entities and entities to API responses.
- `PaymentRepository` and `IdempotencyRecordRepository` provide JPA persistence.
- `GlobalExceptionHandler` formats selected validation, conflict, and not-found failures.
- Flyway migrations define the actual MySQL schema.

`PaymentCreator` is a separate Spring component so its public `@Transactional` method is called through a Spring proxy. This avoids the self-invocation limitation that would occur if a method in `PaymentService` called another transactional method on the same instance.

## 6. Payment creation flow

### New request

1. Spring validates the `Idempotency-Key` header and JSON body.
2. `PaymentService` canonicalizes the request and hashes it with SHA-256.
3. No idempotency record is found.
4. `PaymentCreator.create()` starts a transaction.
5. A payment is inserted with status `PENDING`.
6. An idempotency record linking the key, fingerprint, and payment ID is inserted and flushed.
7. Both records commit together.
8. The API returns `201 Created` with `replayed: false`.

### Same key and same request

1. The existing idempotency record is found.
2. Its fingerprint matches the incoming request.
3. The associated payment is loaded.
4. The API returns `200 OK` with `replayed: true`.

The implementation reloads the payment when replaying, so the response represents the payment's **current persisted state**. It does not store and reproduce the original serialized HTTP response.

### Same key and different request

The fingerprints differ, so the API returns `409 Conflict`. This prevents a caller from accidentally reusing a key for a different payment.

### Concurrent duplicates

Two requests can both perform the initial lookup before either has committed. Both may attempt creation, but MySQL's unique constraint on `idempotency_key` permits only one idempotency record. `saveAndFlush()` forces the constraint to be checked inside the creation call. The losing transaction rolls back its payment insert; `PaymentService` then reads the winning record and applies the normal replay/conflict rules.

## 7. API endpoints

The service currently exposes **one application endpoint**.

### `POST /payments`

Creates a payment idempotently.

#### Header

| Header | Required | Rules | Purpose |
|---|---|---|---|
| `Idempotency-Key` | Yes | Non-blank, maximum 255 characters | Identifies one logical payment-creation operation |

Clients should generate a new high-entropy key, such as a UUID, for each new logical operation and reuse that same key only when retrying the same request.

#### Request body

```json
{
  "sourceAccountId": "11111111-1111-1111-1111-111111111111",
  "destinationAccountId": "22222222-2222-2222-2222-222222222222",
  "amount": 125.50,
  "currency": "INR",
  "description": "Order 4821"
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| `sourceAccountId` | UUID | Yes | Must differ from `destinationAccountId` |
| `destinationAccountId` | UUID | Yes | Must differ from `sourceAccountId` |
| `amount` | Decimal | Yes | Minimum `0.01`; database precision is 19 digits with 4 decimal places |
| `currency` | Enum | Yes | One of `USD`, `EUR`, `INR`, `GBP` |
| `description` | String | No | Maximum 255 characters |

#### New-payment response — `201 Created`

```json
{
  "payment": {
    "paymentId": "7e2fd2e0-f8cc-42ef-89c4-0c4adb538abe",
    "sourceAccountId": "11111111-1111-1111-1111-111111111111",
    "destinationAccountId": "22222222-2222-2222-2222-222222222222",
    "amount": 125.5000,
    "currency": "INR",
    "status": "PENDING",
    "description": "Order 4821",
    "createdAt": "2026-11-11T08:30:00Z"
  },
  "replayed": false
}
```

#### Successful replay — `200 OK`

The response has the same structure, with `replayed` set to `true`. The returned payment is the record associated with the original request.

#### Conflict — `409 Conflict`

Returned when the same idempotency key is used with different request content.

```json
{
  "message": "Idempotency key: 90cb... already used with different request",
  "statusCode": 409,
  "timestamp": "2026-11-11T14:00:00"
}
```

#### Validation failures — `400 Bad Request`

Body validation failures handled by `GlobalExceptionHandler` use the same error structure. Framework-level errors, such as malformed JSON, an unknown enum value, or a missing header, are not explicitly handled and may use Spring Boot's default error representation.

#### Other possible failures

- An idempotency record that points to a missing payment produces an unhandled `IllegalStateException`, normally resulting in `500 Internal Server Error`.
- A `PaymentNotFoundException` handler exists, but no current endpoint or service path throws that exception.

## 8. Data model

### `payments`

| Column | Type | Notes |
|---|---|---|
| `id` | `BINARY(16)` | UUID primary key |
| `source_account_id` | `BINARY(16)` | Required; no local account foreign key |
| `destination_account_id` | `BINARY(16)` | Required; must differ from source |
| `amount` | `DECIMAL(19,4)` | Required and greater than zero |
| `currency` | `VARCHAR(3)` | Java enum: USD, EUR, INR, GBP |
| `status` | `VARCHAR(30)` | Java enum: PENDING, SUCCESS, FAILED |
| `description` | `VARCHAR(255)` | Optional |
| `created_at` | `DATETIME(6)` | Hibernate creation timestamp |
| `updated_at` | `DATETIME(6)` | Hibernate update timestamp |

The migration enforces positive amounts and different source/destination accounts in the database as well as in API validation. This defense in depth protects invariants even if data is written through a path other than the controller.

### `idempotency_records`

| Column | Type | Notes |
|---|---|---|
| `id` | `BINARY(16)` | UUID primary key |
| `idempotency_key` | `VARCHAR(255)` | Globally unique |
| `request_hash` | `VARCHAR(64)` | Lowercase hexadecimal SHA-256 digest |
| `payment_id` | `BINARY(16)` | Associated payment ID |
| `created_at` | `TIMESTAMP(6)` | Creation time |

The idempotency data is kept in a separate table rather than on `payments`. This isolates request-deduplication metadata from the payment domain record and leaves room for future lifecycle policies. Currently records never expire.

The migration does **not** define a foreign key from `idempotency_records.payment_id` to `payments.id`, although `TODO.md` indicates that a foreign key was intended. This allows an orphaned record at the database level and is an important follow-up decision.

## 9. Request fingerprint design

The fingerprint uses these values in order:

```text
sourceAccountId|destinationAccountId|normalizedAmount|currency|description
```

- `BigDecimal.stripTrailingZeros().toPlainString()` makes values such as `10.5` and `10.50` equivalent.
- UUIDs and currency use stable text forms.
- A missing description becomes an empty string.
- The description is last, so a `|` inside it cannot create ambiguity with a later field.
- SHA-256 produces the stored 64-character hexadecimal hash.

This design avoids storing the full request body in the idempotency table. Its trade-off is that future request fields must be deliberately added to the canonical representation; otherwise changing such a field would not be detected as conflicting reuse.

## 10. Key technical decisions and trade-offs

| Decision | Why it helps | Trade-off / implication |
|---|---|---|
| Database-backed idempotency | Durable across restarts and multiple service instances | Adds a write and lookup for each logical request |
| Unique key as concurrency arbiter | Correct under races without relying on an in-memory lock | Correctness depends on the database constraint and transaction behavior |
| Payment and idempotency record in one transaction | Prevents a committed payment without its deduplication record when creation fails | Covers only one database; it does not solve cross-service atomicity |
| `saveAndFlush()` for the idempotency record | Surfaces the unique-key violation before the transactional method returns | Forces an early flush and extra synchronization with the database |
| Separate `PaymentCreator` bean | Ensures Spring applies the transaction proxy | Splits creation logic across two service-level classes |
| Request hash rather than raw request storage | Compact and avoids retaining full input | Cannot reconstruct or audit the original request from this table alone |
| Replay by reloading the payment | Returns the latest status if the payment later changes | Is not a literal replay of the original HTTP response |
| Payment begins as `PENDING` | Supports a later asynchronous processing lifecycle | No current code moves it to `SUCCESS` or `FAILED` |
| Flyway plus `ddl-auto: validate` | Migrations own schema changes while Hibernate checks entity/schema compatibility | Schema/entity mismatches fail application startup, as intended |
| UUID identifiers stored as `BINARY(16)` | Compact, non-sequential public identifiers | UUID storage mapping must remain consistent across Hibernate and migrations |
| Money stored as `BigDecimal` / `DECIMAL(19,4)` | Avoids binary floating-point errors | The API validation does not explicitly limit scale or maximum precision before persistence |

## 11. Configuration and local operation

### Prerequisites

- Java 21
- A MySQL instance reachable at `localhost:3306`
- A database named `payment_db`
- `DB_USERNAME` and `DB_PASSWORD` environment variables

Example in PowerShell:

```powershell
$env:DB_USERNAME = "payment_user"
$env:DB_PASSWORD = "replace-with-local-password"
.\mvnw.cmd spring-boot:run
```

Flyway applies `V1__create_payments_table.sql` and `V2__create_idempotency_records.sql` on startup. Hibernate then validates the mapped entities against the migrated schema. The API is available at `http://localhost:9090`.

In the current Windows environment, the checked-in Maven Wrapper fails before Maven starts because its PowerShell bootstrap indexes a null `DirectoryInfo.Target` value. If the same error occurs locally, use an installed Maven executable temporarily or regenerate/fix the wrapper before relying on the commands above. This bootstrap failure is separate from the application code.

Example request:

```powershell
$headers = @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }
$body = @{
    sourceAccountId = "11111111-1111-1111-1111-111111111111"
    destinationAccountId = "22222222-2222-2222-2222-222222222222"
    amount = 125.50
    currency = "INR"
    description = "Order 4821"
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:9090/payments" `
    -Headers $headers `
    -ContentType "application/json" `
    -Body $body
```

## 12. Testing status

The only committed automated test is `PaymentServiceApplicationTests.contextLoads()`. It verifies that the Spring application context can start, but it does not verify payment behavior.

High-value missing tests are:

1. New request returns `201` and creates one payment and one idempotency record.
2. Same key and equivalent request returns `200` with `replayed: true`.
3. Equivalent decimal representations, such as `10.5` and `10.50`, replay successfully.
4. Same key and changed request returns `409`.
5. Concurrent same-key requests create exactly one payment.
6. A forced idempotency insert failure rolls back the payment insert.
7. Every request-validation rule and error representation.
8. Flyway migrations and Hibernate mappings against real MySQL using Testcontainers.

The context test needs database credentials and a reachable MySQL database unless test-specific configuration is added. `TODO.md` explicitly defers Testcontainers and Docker Compose.

## 13. Known gaps and risks

### Correctness and data integrity

- Add or consciously reject the missing foreign key from the idempotency record to its payment.
- Define retention/expiry semantics for idempotency records. They currently live forever and keys are globally unique forever.
- Decide whether replay should return the original response snapshot or the payment's current state.
- Add API-level precision and scale validation for amounts so oversized values fail predictably as `400` rather than at persistence time.
- Consider a database constraint limiting currency and status values, or rely explicitly on application/schema validation.

### API completeness

- There is no endpoint to retrieve a payment, despite the presence of `PaymentNotFoundException`.
- There is no endpoint or worker to change payment status.
- There is no OpenAPI/Swagger contract.
- Not all framework exceptions are normalized into `ErrorResponse`.
- No authentication, authorization, rate limiting, or correlation ID is present.

### Operational readiness

- No health/metrics dependency or observability configuration is present.
- No container image or local database orchestration is included.
- No production profiles, secrets integration, connection-pool tuning, or deployment manifests are present.
- The test suite does not cover the central idempotency guarantee.

### Roadmap integration

The broader `FULL.md` vision includes Kafka, an outbox, wallets, a ledger, notifications, and an order saga. Before adding those pieces, define which service owns balances and authoritative money state, the exact payment state machine, event schemas and versioning, and how a committed `PENDING` payment advances or compensates.

## 14. Suggested next steps

1. Add focused unit and MVC tests for validation, hashing, replay, and conflicts.
2. Add a MySQL Testcontainers integration test for the concurrent duplicate scenario and transactional rollback.
3. Resolve the `payment_id` foreign-key decision and add a new Flyway migration if chosen.
4. Specify the payment state machine and add a read endpoint before introducing asynchronous processing.
5. Normalize all public error responses and publish an OpenAPI contract.
6. Add health checks, metrics, structured logs, and correlation IDs.
7. Implement an outbox only when payment state changes/events are introduced; keep it in the same transaction as the state change it describes.

## 15. Repository map

```text
pom.xml                                      Maven project and dependencies
FULL.md                                     Broader platform vision and roadmap
TODO.md                                     Deferred decisions and tasks
src/main/java/com/parity/payments/
  PaymentServiceApplication.java            Application entry point
  controller/PaymentController.java         POST /payments
  dto/request/PaymentRequest.java            Input model and validation
  dto/response/*.java                        Success and error response models
  entity/*.java                              JPA entities
  enums/*.java                               Currency and payment status values
  exception/*.java                           Domain exceptions and HTTP handlers
  mapper/PaymentMapper.java                  Entity/API mapping
  repository/*.java                          Spring Data repositories
  service/PaymentService.java                Idempotency orchestration
  service/PaymentCreator.java                Transactional creation
src/main/resources/application.yaml          Runtime configuration
src/main/resources/db/migration/*.sql         Flyway migrations
src/test/java/...                             Spring context smoke test
```

