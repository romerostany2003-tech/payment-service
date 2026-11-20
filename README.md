# Payment Service

## Introduction

Payment Service is a Spring Boot application responsible for safely accepting and recording payment requests.

Its main goal is to ensure that client retries and concurrent duplicate requests do not create multiple payments. Every accepted payment is persisted with a `PENDING` status.

The service currently handles payment creation only. It does not move money, check account balances, or complete a payment.

## What the service solves

- Creates and persists a payment request.
- Validates payment input before processing it.
- Prevents duplicate payments caused by client retries.
- Protects against concurrent requests using the same idempotency key.
- Detects when an idempotency key is reused for a different request.
- Creates the payment and its idempotency record atomically.
- Uses decimal types suitable for monetary values.
- Controls database schema changes through versioned migrations.

## Technology stack

Language              -> Java 21 
Framework             -> Spring Boot 4.1.1
HTTP API              -> Spring Web MVC
Persistence           -> Spring Data JPA and Hibernate 
Database              -> MySQL 
Schema migrations     -> Flyway 
Validation            -> Jakarta Bean Validation 
Build                 -> Maven and Maven Wrapper
Boilerplate reduction -> Lombok 
Unit testing          -> JUnit 5 and Mockito 
Integration testing   -> Spring Boot Test and Testcontainers

### Main components

- `PaymentController` exposes `POST /payments` and returns `201 Created` for a new payment or `200 OK` for a replay.
- `PaymentService` owns the idempotency algorithm and request fingerprint.
- `PaymentCreator` creates the payment and idempotency record in one transaction.
- `PaymentMapper` converts API requests to entities and entities to API responses.
- `PaymentRepository` and `IdempotencyRecordRepository` provide database access.
- `GlobalExceptionHandler` converts selected validation and domain exceptions into HTTP error responses.

## API

### Create a payment

```http
POST /payments
Content-Type: application/json
Idempotency-Key: <unique-key>
```

### Request body

```json
{
  "sourceAccountId": "11111111-1111-1111-1111-111111111111",
  "destinationAccountId": "22222222-2222-2222-2222-222222222222",
  "amount": 125.50,
  "currency": "INR",
  "description": "Order 4821"
}
```

### Validation rules

|       Value            |                 Rules                       |
|------------------------|---------------------------------------------|
| `Idempotency-Key`      | Required, non-blank, maximum 255 characters |
| `sourceAccountId`      | Required UUID                               |
| `destinationAccountId` | Required UUID and must differ from source   |
| `amount`               | Required and must be at least `0.01`        |
| `currency`             | Required; `USD`, `EUR`, `INR`, or `GBP`     |
| `description`          | Optional; maximum 255 characters            |


### New payment response

Status: `201 Created`

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
    "createdAt": "2026-11-20T08:30:00Z"
  },
  "replayed": false
}
```

### Replayed request

If the same key and equivalent request are submitted again, the existing payment is returned with `200 OK` and `replayed` set to `true`.

### Idempotency conflict

Reusing a key with different payment details returns `409 Conflict`:

```json
{
  "message": "Idempotency key: key-123 already used with different request",
  "statusCode": 409,
  "timestamp": "2026-11-20T14:00:00"
}
```

## Payment creation flow

### New request

1. Spring validates the `Idempotency-Key` header and request body.
2. `PaymentService` creates a deterministic SHA-256 fingerprint from the request.
3. The service searches for an existing idempotency record.
4. If the key is new, `PaymentCreator.create()` starts a transaction.
5. A payment is saved with status `PENDING`.
6. An idempotency record is saved and flushed.
7. Both records commit together.
8. The API returns `201 Created` with `replayed: false`.

```text
POST /payments
      |
      v
Validate and fingerprint request
      |
      v
Find idempotency key
      |
      +-- exists and same request --> return existing payment (200)
      |
      +-- exists and different ----> conflict (409)
      |
      +-- does not exist ----------> create both records (201)
```

### Concurrent duplicate requests

The initial lookup alone cannot prevent a race. Two requests may both find that the key does not exist and then attempt creation.

The database unique constraint on `idempotency_key` provides the final guarantee:

```text
Request A inserts key-123 --> succeeds
Request B inserts key-123 --> unique constraint violation
```

The payment and idempotency insert are in the same transaction. The losing request rolls back its payment insert, reads the winning idempotency record, and returns the winning payment as a replay.

This works across application restarts and multiple instances because the correctness guarantee is stored in MySQL rather than an in-memory lock.

## Request fingerprint

The fingerprint is calculated from:

```text
sourceAccountId|destinationAccountId|normalizedAmount|currency|description
```

The amount is normalized using `stripTrailingZeros().toPlainString()`. Values such as `10.5`, `10.50`, and `10.500` are therefore treated as equivalent.

A missing description becomes an empty string. The canonical value is hashed with SHA-256, and the resulting hexadecimal digest is stored in the idempotency record.

## Database schema

Flyway owns the schema. Hibernate uses `ddl-auto: validate`, so it checks the entity mappings without creating or modifying tables.

### `payments`

Stores the payment business record.

| Column                   | Type            | Description                       |
|--------------------------|-----------------|-----------------------------------|
| `id`                     | `BINARY(16)`    | Payment UUID and primary key      |
| `source_account_id`      | `BINARY(16)`    | Source account UUID               |
| `destination_account_id` | `BINARY(16)`    | Destination account UUID          |
| `amount`                 | `DECIMAL(19,4)` | Payment amount                    |
| `currency`               | `VARCHAR(3)`    | Currency code                     |
| `status`                 | `VARCHAR(30)`   | `PENDING`, `SUCCESS`, or `FAILED` |
| `description`            | `VARCHAR(255)`  | Optional description              |
| `created_at`             | `DATETIME(6)`   | Creation timestamp                |
| `updated_at`             | `DATETIME(6)`   | Last update timestamp             |

Database constraints require a positive amount and different source and destination accounts.

### `idempotency_records`

Stores request-deduplication metadata.

| Column            | Type           | Description                         |
|-------------------|----------------|-------------------------------------|
| `id`              | `BINARY(16)`   | Record UUID and primary key         |
| `idempotency_key` | `VARCHAR(255)` | Client-provided globally unique key |
| `request_hash`    | `VARCHAR(64)`  | SHA-256 request fingerprint         |
| `payment_id`      | `BINARY(16)`   | ID of the associated payment        |
| `created_at`      | `TIMESTAMP(6)` | Creation timestamp                  |

The unique constraint on `idempotency_key` prevents two records from being committed for the same key.

### Entity relationship

The tables have a logical relationship:

```text
idempotency_records.payment_id
              |
              v
        payments.id
```

The application follows this relationship explicitly with `PaymentRepository.findById()`. There is currently no JPA association or MySQL foreign-key constraint.

Keeping the JPA entities independent avoids automatic entity loading and cascade behavior. The missing foreign key means MySQL does not prevent an idempotency record from referencing a nonexistent payment. Normal application creation is protected by the shared transaction, but manual database writes, deletion, or future bugs could create an orphaned record. This is a known trade-off in the current design.


## Important design decisions

### Database-backed idempotency

Idempotency records are stored in MySQL rather than memory. Retry protection therefore survives restarts and works across multiple service instances.

### Separate idempotency table

Payment business data and HTTP retry metadata are stored separately. This keeps the payment entity focused on the payment while allowing idempotency behavior to evolve independently.

### Database constraint as concurrency protection

The initial key lookup is an optimization. The unique database constraint is the correctness guarantee when requests race.

### Atomic creation

The payment and idempotency record are created in one transaction. They either both commit or both roll back.

### Separate `PaymentCreator`

Spring applies `@Transactional` through a proxy. Calling a transactional creation method from another method in the same class would be self-invocation and would bypass that proxy.

`PaymentCreator` is a separate Spring bean so the call passes through the transaction proxy. It also ensures that a failed concurrent creation is rolled back before `PaymentService` reads the winning record.

### Payments begin as `PENDING`

Creation only means that the request was accepted and recorded. The current service does not implement transitions to `SUCCESS` or `FAILED`.

### Decimal representation for money

The application uses Java `BigDecimal` and MySQL `DECIMAL(19,4)` instead of floating-point types, avoiding binary floating-point errors.

### Flyway-managed schema

Flyway migrations make schema changes explicit and version controlled. Hibernate validates that the entities match the migrated schema.

## Testing

The test suite includes unit, context, and MySQL integration tests.

### `PaymentServiceTest`

Uses Mockito to verify:

- A new idempotency key delegates to `PaymentCreator`.
- An existing key with the same request returns a replay.
- An existing key with a different request produces a conflict.
- A record referencing a missing payment produces an error.
- A simulated concurrent winner is replayed after a constraint failure.
- An unrelated creation failure is rethrown when no winning key exists.

### `PaymentCreatorTest`

Verifies that creation sets the status to `PENDING`, saves the payment, creates the expected idempotency record, and returns a non-replayed result.

### `PaymentServiceIntegrationTest`

Uses Testcontainers with MySQL 8.4 to verify:

- A new request persists one payment and one idempotency record.
- Replaying the same request returns the original payment.
- Reusing a key with different content causes a conflict.
- MySQL rejects a duplicate idempotency key.

The integration test is skipped when Docker is unavailable.

### `PaymentServiceApplicationTests`

Loads the Spring application context. With the default configuration, a reachable MySQL database and valid credentials are required.

### Important remaining test scenarios

- Two truly concurrent requests create exactly one committed payment.
- A forced idempotency insert failure rolls back the payment insert.
- Equivalent decimal forms produce the same fingerprint.
- Controller-level validation and error responses.

## Running locally

### Prerequisites

- Java 21
- MySQL
- Maven or the included Maven Wrapper
- A database named `payment_db`

### Create the database

```sql
CREATE DATABASE payment_db;
```

Flyway creates the required tables when the application starts.

### Configure credentials

Linux or macOS:

```bash
export DB_USERNAME=payment_user
export DB_PASSWORD=your_password
```

PowerShell:

```powershell
$env:DB_USERNAME = "payment_user"
$env:DB_PASSWORD = "your_password"
```

### Start the service

Linux or macOS:

```bash
./mvnw spring-boot:run
```

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

Alternatively, with Maven installed:

```bash
mvn spring-boot:run
```

The service listens on `http://localhost:9090`.

### Example request

```bash
curl --request POST http://localhost:9090/payments \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: order-4821" \
  --data '{
    "sourceAccountId": "11111111-1111-1111-1111-111111111111",
    "destinationAccountId": "22222222-2222-2222-2222-222222222222",
    "amount": 125.50,
    "currency": "INR",
    "description": "Order 4821"
  }'
```

Run the same request again with the same key to receive a replay. Change the amount while retaining the key to receive `409 Conflict`.

## Project structure

```text
payment-service/
|-- pom.xml
|-- postman/
|   `-- payment-service.postman_collection.json
|-- src/
|   |-- main/
|   |   |-- java/com/parity/payments/
|   |   |   |-- controller/
|   |   |   |-- dto/
|   |   |   |-- entity/
|   |   |   |-- enums/
|   |   |   |-- exception/
|   |   |   |-- mapper/
|   |   |   |-- repository/
|   |   |   `-- service/
|   |   `-- resources/
|   |       |-- application.yaml
|   |       `-- db/migration/
|   `-- test/java/com/parity/payments/
`-- README.md
```

## Current limitations

The service currently does not:

- Move or reserve money.
- Check whether accounts exist or have sufficient balances.
- Expose an endpoint for retrieving payments.
- Advance payments from `PENDING` to a terminal status.
- Expire or clean up idempotency records.
- Store an exact snapshot of the original HTTP response.
- Authenticate or authorize callers.
- Enforce the payment/idempotency relationship with a foreign key.
- Normalize every framework-generated error into `ErrorResponse`.
- Expose production health checks, metrics, or tracing.

## Summary

Payment Service provides a reliable boundary for accepting payment commands. Its central guarantee is:

> Repeated or concurrent requests using the same idempotency key will not create multiple committed payments.

The service provides that guarantee through deterministic request fingerprints, a unique database constraint, transactional creation, explicit replay handling, and defensive input validation.