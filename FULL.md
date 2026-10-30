## What the application is

Parity is a simplified payments platform: a customer pays a merchant, and the system moves the money and keeps accurate books. The product matters less than the engineering goal, which is that **the system stays correct when things go wrong**: duplicate requests, crashed services, redelivered Kafka messages, and events arriving out of order.

## One example flow

A customer places an order and pays.

1. The request enters through the **API Gateway**.
2. The **Order Service** starts a saga: reserve inventory, then ask for payment.
3. The **Payment Service** creates the payment, once, even if the request is sent five times.
4. The **Wallet Service** holds or moves the customer's funds, without overdrawing.
5. Payment events go out through Kafka.
6. The **Ledger Service** records the money movement as balanced debit and credit entries, once, even if the event is delivered twice.
7. The **Notification Service** tells the customer.
8. If payment fails, the Order Service undoes the earlier steps (releases inventory, cancels the order).

## What each service does

| Service | Responsibility | Main problem it demonstrates |
|---|---|---|
| **API Gateway** | Single entry point: routing, JWT checks, correlation IDs | Security at the edge |
| **Payment Service** | Accepts payment requests, tracks payment status, publishes payment events | Idempotency under concurrency, and the outbox |
| **Wallet Service** | Owns accounts and balances; credit, debit, transfer | Concurrent updates without overdraft; atomic transfers |
| **Ledger Service** | Immutable double-entry record of every money movement | Debits always equal credits; append-only history |
| **Order Service** | Coordinates order, inventory, and payment steps | Orchestrated saga with compensating actions |
| **Notification Service** | Sends customer messages from events, using Redis for caching and dedupe | Idempotent consumers, plus a lightweight consumer example |
| **Other consumers** | Optional extras such as analytics or fraud checks | Shows Kafka fan-out |

## What connects them

- **Kafka** carries events between services, using `paymentId` as the partition key so one payment's events stay in order.
- **Each service has its own database.** No service reads another's tables.
- **The transactional outbox** guarantees an event is published if and only if the database change committed.
- **Retry topics and a dead-letter queue** handle events that keep failing.
- **Redis** supports caching and dedupe in the Notification Service.
- **Observability** (Micrometer, OpenTelemetry, correlation IDs) lets you trace one request across services.

## The ideas that tie it together

1. **Duplicates happen,** so every operation must be idempotent.
2. **There's no single transaction across services,** so you use sagas and compensation.
3. **Databases and message brokers can disagree,** so you use the outbox.
4. **Money must always balance,** so the ledger enforces debits equal credits.

## Where you are

Payment Service is mostly built, and Wallet is next. The wallet and the ledger each hold money-related state, so you'll need to decide which is authoritative. That's one of the Step 3 questions.