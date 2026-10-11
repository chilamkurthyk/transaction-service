# Design Notes

## Architecture

The input adapter is represented by `Main` and the `processBatch` method. `TransactionProcessor` owns the processing workflow, while `TransactionRequest`, `TransactionRecord`, and enums define the domain. `ProcessingSummary` provides a compact operational report. The input mechanism can later be replaced by a file, REST endpoint, queue, or database poller without moving business rules into that adapter.

## Idempotency

`transactionId` is the business idempotency key. The processor registers a valid transaction ID before queuing it. Later submissions with the same ID return a separate `DUPLICATE` record and do not apply another balance change. In production, the idempotency key and ledger effect must be committed atomically with a unique database constraint. A payload hash should detect conflicting content for a reused ID.

## Ordering

Sequence-based processing is used. Each account begins at sequence 1. The processor applies only the next expected sequence and holds later sequences in a sorted pending map. When a missing event arrives, the processor drains all newly unblocked events. Missing sequence numbers are reported. This is appropriate for a simplified per-account ledger stream; it requires an explicit policy for permanently missing events and failed sequence numbers.

## Retry and failures

The `simulatedFailures` field makes transient failure reproducible in tests. Each attempt increments an attempt counter. A transient failure becomes `RETRY_PENDING` until the configured maximum attempt count is reached, then becomes `FAILED`. No balance mutation occurs on a failed attempt. Retrying a successful transaction has no effect because only `RETRY_PENDING` records can be retried. Validation and insufficient-balance failures are terminal business failures rather than transient errors.

## Consistency and concurrency

Methods are synchronized, so operations are serialized inside this JVM and a balance update cannot race with another thread in this process. This is not distributed locking and not durable atomicity. A production implementation should use a database transaction to atomically insert the unique transaction/ledger record, update the account balance, and transition the status.

## Observability

The sample uses Java logging without printing amounts or customer-sensitive payloads in every log line. `ProcessingSummary` provides counts by status and account balances for a demonstration environment. `pendingGaps()` exposes missing sequence numbers, retry waits, and failed sequence blockers.

## AI-assisted development

Describe the AI tool actually used, the prompts/tasks it assisted with, and the changes you reviewed. Validate generated code by running the complete test suite, reviewing race conditions and state transitions, and manually checking expected balances. Do not state that a command succeeded unless it was run.

## Known trade-offs / next steps

- In-memory data disappears on restart.
- The sample has no queue, HTTP API, JSON parser, scheduler, or durable retry queue.
- A failed event at the expected sequence blocks later events for that account.
- Currency is fixed per account for this example.
- Add database-backed atomicity, unique constraints, cross-instance concurrency tests, backoff, dead-letter handling, metrics, alerting, authentication, and audit controls before production.
