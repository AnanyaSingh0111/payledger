# Design Decisions

Short notes on why PayLedger is built the way it is, including the trade-offs.

## 1. Pessimistic locking for balance changes

**Decision:** lock account rows with `SELECT ... FOR UPDATE` before reading the balance.

**Why:** money movement has high contention on a few hot rows. With only optimistic locking (`@Version`), concurrent requests on the same account collide and the losers are rejected, so valid transfers fail and clients have to retry. With row locks the requests queue up and all valid ones succeed.

**Trade-off:** requests on the same account are serialized, which limits throughput on a single hot account and holds a database connection while waiting. `@Version` stays on the `Account` entity as a safety net.

## 2. Lowest-ID-first lock ordering

**Decision:** a transfer always locks the account with the smaller id first.

**Why:** if A to B locks A then B, while B to A locks B then A, each can end up holding one lock and waiting for the other, which is a deadlock. A fixed global order removes the cycle. The 1,000 opposing transfers test exercises exactly this pattern.

## 3. Idempotency through a database unique constraint

**Decision:** `UNIQUE(user_id, idempotency_key)` on the transactions table, plus a lookup before processing.

**Why:** two identical requests can race past an application-level check at the same time. The database is the single source of truth, so only one insert can win. The loser's transaction rolls back, balance changes included, and a retry hits the replay path and returns the stored transaction.

**Details:** the same key with a different request body returns 422, so a key cannot be reused for a different payment. The key is scoped per user so two users cannot collide.

## 4. Stateless JWT with ownership checks

**Decision:** JWT in the `Authorization` header, validated by a filter. The user id in the URL is compared to the id in the token with `@PreAuthorize`.

**Why:** stateless sessions scale horizontally without shared session storage. Keeping `userId` in the URL meant the services did not need to change, and the check prevents one user from reading another user's data (403).

**Trade-off:** tokens cannot be revoked before they expire (1 hour). A refresh-token flow and a revocation list are future work.

## 5. Application-level exception handling

**Decision:** a single `@RestControllerAdvice` maps domain exceptions to HTTP status codes with a consistent JSON body.

**Why:** an earlier version returned HTTP 200 with the error only inside the body. Proper status codes (400, 401, 403, 404, 409, 422) make the API usable by real clients. The handler deliberately has no catch-all for `Exception`, so Spring Security's own 401 and 403 responses are not swallowed.

## 6. Double-entry ledger

**Decision:** every transaction writes two immutable ledger entries (a debit and a credit of the same amount) in the same database transaction as the balance change. Income and expense use a null "external" account as the counterparty, and opening balances are recorded as income.

**Why:** a balance column alone cannot explain how it got its value, and a bug or manual edit corrupts it silently. With a ledger the system total is always zero and each balance can be recomputed from its entries and checked.

**Trade-off:** more writes per transaction, and the stored balance is kept as a denormalized copy for fast reads, so it must be checked against the ledger.

## 7. Reconciliation as a separate read-only check

**Decision:** a service compares each account's stored balance with the sum of its ledger entries and checks that the whole ledger nets to zero. It runs from an admin-only endpoint and from a nightly scheduled job.

**Why:** the stored balance is a copy kept for fast reads, so it can drift because of a bug or a manual edit. The ledger is the source of truth, and comparing the two detects silent corruption. The whole check runs in one read-only transaction, so it sees a consistent snapshot and a transfer committing mid-check cannot cause a false alarm.

**Trade-off:** it detects problems but does not repair them or alert anyone yet (it logs an error). With several server instances the job would need a scheduler lock.

## 8. `ddl-auto=update` for now

**Decision:** let Hibernate create and update tables.

**Why:** fastest path to a working deployment while the schema was still changing.

**Trade-off:** not safe for production schema changes. The plan is Flyway migrations.

## 9. Secrets only through environment variables

**Decision:** database credentials and the JWT secret are read from environment variables, with no default for either secret.

**Why:** nothing sensitive lives in the repository, and the app refuses to start without a secret instead of falling back to a weak one. A secret shorter than 32 characters is rejected by the JWT library.

## What I would do next

- Add a ledger integrity test under concurrent load and GitHub Actions CI.
- Add alerting for reconciliation mismatches and a scheduler lock for multiple instances.
- Add a transactional outbox for events such as notifications.
- Move schema management to Flyway and add GitHub Actions CI.