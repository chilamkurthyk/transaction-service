# Idempotent Background Transaction Processing Service

A plain Java 17 + Maven + JUnit 5 assessment project demonstrating idempotency, per-account sequence ordering, retry-safe processing, validation, and processing summaries.

## Requirements

- JDK 17+
- Maven 3.8+

## Run tests

```bash
mvn clean test
```

## Run the sample

```bash
mvn -q package
java -cp target/classes com.example.transactions.Main
```

No external services are required.

## Design summary

- **Idempotency:** `transactionId` is the business idempotency key. Once a transaction ID is registered, later requests with that ID are classified as duplicates and do not reapply its effect.
- **Ordering:** sequence numbers start at 1 and are consecutive per account. Future sequence numbers wait in a pending map until missing earlier sequences arrive.
- **Retry:** simulated transient failures move to `RETRY_PENDING`; attempts are bounded by `maxAttempts`. Balance changes happen only after a successful attempt.
- **Consistency:** synchronized processor methods serialize operations in this single JVM. Balance updates and state changes occur within the same critical section.
- **Validation:** invalid records are failed independently so other records can continue.
- **Observability:** Java logging plus a processing summary exposes statuses, account balances, and sequence gaps.

## Important assumptions and limitations

1. Sequence numbers are consecutive and start at 1 for each account.
2. One currency is allowed per account in this example.
3. `transactionId` uniquely identifies a business effect. If a production client can reuse a transaction ID with different payload data, compare a canonical payload hash and reject mismatches rather than silently treating them as ordinary duplicates.
4. In-memory state is lost on restart and is not shared between service instances.
5. `synchronized` only protects one JVM. Production needs database transactions, unique constraints, row locking or optimistic concurrency, and durable state.
6. A failed transaction at the next expected sequence blocks later transactions for that account until an operator or recovery policy resolves it.
7. This sample simulates retries explicitly; it does not include a scheduler, durable queue, HTTP API, or JSON parser.

## Production hardening

- Persist transaction state, idempotency keys, ledger entries, balances, and retry attempts in a relational database.
- Use a unique constraint on the business transaction ID and an atomic database transaction for deduplication + ledger + balance update.
- Add backoff with jitter, retry scheduling, dead-letter handling, and an operator replay mechanism.
- Add metrics and alerts for failure rate, pending age, retry exhaustion, and sequence gaps.
- Define retention, audit, access controls, and payload-hash conflict behavior.
- Use a durable queue with at-least-once delivery and design consumers to remain idempotent.
- Add integration tests against the selected database and concurrency tests across multiple service instances.

## AI-assisted development note

If AI tools were used, document which tasks they helped with (for example, initial test-case brainstorming or code review), then record the checks you personally performed. Do not claim tests passed unless you ran them locally. Be prepared to explain every class and trade-off.
