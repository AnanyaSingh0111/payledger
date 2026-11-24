# Design Decisions

Why PayLedger is built the way it is, including the trade-offs and the bugs that shaped it.

## 1. Pessimistic locking for balance changes

**Decision:** lock account rows with `SELECT ... FOR UPDATE` before reading the balance.

**Why:** money movement has high contention on a few hot rows. With only optimistic locking (`@Version`), concurrent requests on the same account collide and the losers are rejected, so valid transfers fail and clients have to retry. With row locks the requests queue up and all valid ones succeed. `LockingBenchmarkTest` measures both (see the README table).

**Trade-off:** requests on the same account are serialized, which limits throughput on a single hot account and holds a database connection while waiting. `@Version` stays on `Account` as a safety net for any code path that forgets to lock.

## 2. One global lock order (lowest id first)

**Decision:** every path that locks more than one account (transfer, refund) goes through one helper, `lockInOrder`, which locks in ascending id order.

**Why:** if A→B locks A then B while B→A locks B then A, each can hold one lock and wait for the other: a deadlock. A single global order removes the cycle. Refunds use the same helper, so "A pays B" racing "B refunds A" is also safe. The 1,000 opposing transfers test exercises this.

## 3. Idempotency: a unique constraint, a re-check after locking, and a replay on conflict

**Decision:** `UNIQUE(user_id, idempotency_key)` on `transactions`, a lookup before processing, a second lookup after the locks are acquired, and `TransactionFacade`, which turns a constraint violation into a replay.

**Why:** two identical requests can both pass the first lookup. The database is the arbiter: only one insert can win. Earlier versions returned the loser a 409 containing the raw MySQL error. Now:
- if the loser was waiting on the same account lock, the post-lock re-check (READ COMMITTED, see 4) sees the winner and replays it;
- otherwise the loser's insert fails, its transaction rolls back (balance changes included), and the facade looks up the winner in a **new** transaction and returns it.

Every caller gets the same transaction back (`ConcurrencyTest` asserts 20 parallel retries → 1 id, 0 errors).

**Details:** the same key with a different body returns 422. Keys are scoped per user. The prefix `sys:` is reserved for internal operations (opening balances), so a client cannot pre-claim a key and block account creation.

## 4. READ COMMITTED for money movement, REPEATABLE READ for reconciliation

**Decision:** `@Transactional(isolation = READ_COMMITTED)` on transfers and refunds; reconciliation stays at REPEATABLE READ.

**Why (the subtle bug):** MySQL's default is REPEATABLE READ, where a transaction's snapshot is fixed at its first plain read. The idempotency lookup is that first read, and it happens *before* waiting for the account lock. The daily-limit rule then runs `SUM(today's outgoing)` *after* getting the lock. Under REPEATABLE READ that SUM reads the old snapshot and misses transfers committed while it waited, so ten parallel 30k transfers against a 200k limit could all pass. Under READ COMMITTED each statement sees the latest committed data, and the row lock still serializes the debits. `FraudIntegrationTest.dailyLimitHoldsUnderConcurrency` proves it: exactly 6 of 10 succeed.

The balance itself was never at risk: `FOR UPDATE` is a locking read and always sees the latest row, whatever the isolation level.

Reconciliation wants the opposite: one consistent snapshot across several queries, so a transfer committing halfway through cannot look like a mismatch.

## 5. Double-entry ledger, append-only at three levels

**Decision:** every transaction writes one DEBIT and one CREDIT of the same amount in the same database transaction as the balance change. A null account is the outside world (top-ups and withdrawals).

**Append-only:** `@Immutable` on the entity, a repository without update or delete methods, and MySQL triggers that reject `UPDATE` and `DELETE` on `ledger_entries` (and `audit_logs`). Even raw SQL is stopped (`LedgerIntegrityTest`).

**Why:** a balance column alone cannot explain how it got its value. With a ledger the system total is always zero and every balance can be recomputed and checked.

**Trade-off:** more writes per transaction, and the stored balance is a denormalized copy that must be checked (decision 7).

## 6. Database constraints as the last line of defence

**Decision:** `CHECK (balance >= 0)`, `CHECK (amount > 0)`, `UNIQUE(refund_of_transaction_id)`, foreign keys everywhere, all in Flyway migrations.

**Why:** application checks can have bugs; constraints cannot be bypassed. Even a buggy code path cannot commit a negative balance or refund a transfer twice.

## 7. Reconciliation

**Decision:** compare each account's stored balance with the sum of its ledger entries; check the whole ledger nets to zero and that every transaction has exactly two entries that net to zero. Runs nightly and on demand (admin). A mismatch writes an audit row and an outbox alert event.

**Trade-off:** it detects, it does not repair; a human decides. With several instances the nightly job would need a scheduler lock (ShedLock).

## 8. Transactional outbox

**Decision:** events (`TransactionCompleted`, `TransactionRefunded`, `AccountFrozen`, `FraudFlagRaised`, `ReconciliationMismatch`) are inserted into `outbox_events` in the same database transaction as the change. A poller publishes them with exponential backoff and parks them as `FAILED` after N attempts.

**Why:** "commit, then call the broker" loses the event if the process dies between the two; "call the broker, then commit" announces things that may roll back. The outbox makes the event exactly as durable as the change. `OutboxService.enqueue` uses `Propagation.MANDATORY` so it cannot be called outside a business transaction by mistake.

**Details:** the poller claims rows with `FOR UPDATE SKIP LOCKED`, so several instances can poll without double-processing. Delivery is at-least-once (a crash after publishing but before marking it published re-sends), so consumers de-duplicate by event id. The publisher is an interface; the default logs, Kafka would be one more bean.

## 9. Fraud rules with the Strategy pattern

**Decision:** each rule implements `FraudRule` and is a Spring bean; `FraudService` runs them all. Rules either BLOCK (daily limit) or FLAG (rapid transfers, large amount). Flags are stored for admin review.

**Why:** adding a rule is one new class, no changes elsewhere (open/closed). Rules run after the locks and before any balance changes, so a block moves no money.

**Detail:** a blocked attempt is audited with `Propagation.REQUIRES_NEW`, because the surrounding transaction is about to roll back and would take the audit row with it.

## 10. Audit log

**Decision:** an append-only `audit_logs` table written in the same transaction as each state change (registration, account creation, transactions, refunds, freezes, fraud reviews, reconciliation runs).

**Why:** the audit row commits if and only if the change commits, so the log never claims something happened that was rolled back.

## 11. Users are disabled, never deleted

**Decision:** the old `DELETE /api/users/{id}` is replaced by admin disable/enable.

**Why:** deleting a user cascaded to accounts that the ledger references (it failed with a foreign key error, and in the worst case would have erased financial history). Financial records must be kept.

## 12. JWT carries identity, not authority

**Decision:** the token holds only the user id (plus issuer and expiry). The filter loads the user by primary key on every request and takes role and status from the database.

**Why:** with the role in the token, a disabled or demoted user kept access until expiry. One indexed lookup per request is the price of immediate revocation.

**Other details:** login runs a BCrypt comparison even for unknown emails, so timing does not reveal which emails exist. Emails are normalized to lower case. Passwords are capped at 72 characters because BCrypt ignores anything beyond.

## 13. Amounts are validated to 2 decimal places

**Decision:** `@Digits(fraction = 2)` and a maximum on every amount.

**Why:** `DECIMAL(19,2)` silently rounds `10.005` to `10.01`. The response was built from the Java object, so the API confirmed a payment of 10.005 while the database recorded 10.01. Rejecting the request is honest; rounding it is not.

## 14. Flyway owns the schema

**Decision:** versioned SQL migrations; Hibernate only validates (`ddl-auto=validate`). Tests rebuild the schema from the same migrations on startup.

**Why:** reviewable, repeatable schema changes, and the tests prove the migrations (with their constraints and triggers) actually work.

## 15. Real MySQL in tests

**Decision:** integration and concurrency tests run against MySQL, not H2.

**Why:** locking, isolation levels, `SKIP LOCKED`, CHECK constraints and triggers behave differently (or do not exist) in an in-memory database. The bugs this project is about only show up on the real engine.

## What I would do next

- Testcontainers so tests start their own MySQL.
- ShedLock for the scheduled jobs when running more than one instance.
- Refresh tokens and rate limiting on login.
- A real broker (Kafka) behind `EventPublisher`, and a notification consumer.
- A system "external" account per currency instead of null-account ledger entries.
