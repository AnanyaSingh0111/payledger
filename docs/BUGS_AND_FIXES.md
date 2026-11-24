# Bugs I Broke On Purpose, and How I Fixed Them

The rule for this project was: build the naive version, break it with a concurrent test, then fix it.
This note records each bug, how it was reproduced, the measured result, and the fix.
Every number below comes from a real run against MySQL 8. The raw output is linked in each section.

| # | Bug | Without the fix | With the fix |
|---|---|---|---|
| 1 | Lost update (no locking) | 50 of 50 withdrawals "succeed" from 1,000; 157.00 destroyed in transfers | Exactly 10 succeed; money conserved |
| 2 | Deadlocks and mass rejections (optimistic only) | 912 of 1,000 transfers fail, 798 of them deadlocks | 1,000 of 1,000 succeed, 0 deadlocks |
| 3 | Duplicate payment on retry | Every retry is a new payment | 20 parallel retries move money once |
| 4 | Idempotency race returns a database error | Loser gets 409 with raw MySQL text | All 20 callers get the same transaction, 0 errors |
| 5 | Daily limit bypassed (isolation level) | 10 of 10 succeed: 300,000 sent against a 200,000 limit | Exactly 6 succeed |
| 6 | Silent rounding of amounts | Client is told 10.005, database stores 10.01 | Request rejected with a clear 400 |

---

## 1. Lost update: two requests read the same balance

### The naive code
```java
Account acc = accountRepository.findById(id);          // plain SELECT
if (acc.getBalance().compareTo(amount) < 0) throw ...;  // check
acc.setBalance(acc.getBalance().subtract(amount));      // write
```

### Why it breaks
Read, check, write is three steps, and nothing stops two requests interleaving:

| Time | Request A | Request B | Stored balance |
|---|---|---|---|
| t1 | reads 1,000 | | 1,000 |
| t2 | | reads 1,000 | 1,000 |
| t3 | 1,000 ≥ 100, writes 900 | | 900 |
| t4 | | 1,000 ≥ 100, writes 900 | **900** |

Two withdrawals of 100 were paid out, but the balance only dropped by 100. B's write silently overwrote A's.

### Measured
`LockingBenchmarkTest`, strategy `NO_LOCKING` ([raw output](locking-benchmark.md)):
- 50 parallel withdrawals of 100 from a balance of 1,000: **all 50 succeeded**. That's 5,000 paid out, yet the balance only fell to 800.
- 1,000 opposing transfers between two wallets of 10,000: the total ended at **19,843.00**, so 157.00 vanished.

### The fix
Lock the row before reading it:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)            // SELECT ... FOR UPDATE
@Query("select a from Account a join fetch a.user where a.id = :id")
Optional<Account> findByIdForUpdate(Long id);
```
Request B now waits at t2 until A commits. B then reads 900, not 1,000. A locking read always returns the latest committed row, whatever the isolation level.

The database adds a second guard: `CHECK (balance >= 0)` on `accounts`. Even a future bug cannot commit a negative balance.

### Result
50 parallel withdrawals: **exactly 10 succeed, final balance 0.00** (`ConcurrencyTest.parallelWithdrawalsNeverOverdrawAndExactlyTenSucceed`).

---

## 2. Optimistic locking alone: deadlocks and mass rejections

### The attempt
My first fix was `@Version` on `Account`. Hibernate then writes `UPDATE accounts ... WHERE id = ? AND version = ?`, and if another transaction changed the row first, zero rows match and the update fails. That prevents lost updates, but under contention two new problems appeared.

### Measured
Recorded run of that version ([FAILED_RUN.txt](FAILED_RUN.txt)), 1,000 opposing transfers on 32 threads:
```
TRANSFER TEST -> succeeded=88 failed=912
777 x  Deadlock found when trying to get lock (update accounts ...)
 21 x  Deadlock found when trying to get lock (insert into transactions ...)
```
- **912 of 1,000 valid transfers were rejected.** Every version conflict is a failed request that the client must retry.
- **798 failures were MySQL deadlocks.** A transfer A→B updates row A then row B; a transfer B→A updates B then A. Each takes an exclusive lock on its first row and waits for the other's, which is a cycle. InnoDB detects it and kills one transaction.

### The fix
Pessimistic locks, acquired in one global order: the lowest account id first, always.
```java
private Map<Long, Account> lockInOrder(Long... ids) {
    TreeSet<Long> ordered = new TreeSet<>();   // ascending
    ...
    for (Long id : ordered) locked.put(id, accountRepository.findByIdForUpdate(id)...);
}
```
A→B and B→A now both lock A first. Whoever gets A proceeds to B; the other waits on A while holding nothing, so a cycle cannot form. Refunds use the same helper, so "A pays B" racing "B refunds A" is safe too.

### Result
1,000 opposing transfers: **1,000 succeeded, 0 failed, 0 deadlocks, balances exact** (`ConcurrencyTest.thousandOpposingTransfersKeepExactBalancesAndNeverDeadlock`, [PASSED_RUN.txt](PASSED_RUN.txt)).

Head-to-head on the same workload ([locking-benchmark.md](locking-benchmark.md)):

| Strategy | Served | Retries | Time |
|---|---|---|---|
| Optimistic, no retry | 55 / 1,000 | 0 | 1.2 s |
| Optimistic + retry | 969 / 1,000 | 23,670 | 17.1 s |
| **Pessimistic, ordered** | **1,000 / 1,000** | **0** | **8.8 s** |

Optimistic locking is the better choice when conflicts are rare. Money on a few hot accounts is the opposite case.

---

## 3. Duplicate payment on retry

### The problem
A mobile client sends "pay 100", the payment commits, and the response is lost (timeout, network drop, app killed). The client cannot know whether it worked, so it retries. Without idempotency the server has no way to tell a retry from a new payment, and pays twice. The server is "correct" every time; the customer is charged twice anyway.

### The fix
The client sends an `Idempotency-Key` header, unique per payment. The server stores it with the transaction, under a database constraint:
```sql
CONSTRAINT uk_tx_user_idem UNIQUE (user_id, idempotency_key)
```
- Same key, same request: return the stored transaction and move no money.
- Same key, different request: **422**, because a key can't be reused for a different payment.
- Keys are scoped per user, and the `sys:` prefix is reserved for internal operations.

### Result
20 threads send the same request with the same key at the same instant: **money moves once (balance 900.00)** (`ConcurrencyTest`).

---

## 4. The idempotency race returned a raw database error

### The bug
Checking "was this key used?" and then inserting is again two steps. Two identical requests can both see "not used", both move money, and both try to insert. The unique constraint correctly lets only one insert win, and the loser's transaction rolls back with all its balance changes. But the old exception handler returned the raw database message to the loser's client, for example:
```
409 Conflict  { "message": "Duplicate entry '7-pay-001' for key 'transactions.uk_tx_user_idem'" }
```
That's an error for a request that actually succeeded (as the winner), and it leaked database internals. Retries are supposed to be safe; this one looked like a failure.

### The fix (two layers)
1. **Re-check after locking.** Same-key requests on the same account queue on the row lock. Once a request gets the lock, it looks the key up again, sees the winner's committed row, and replays it. This relies on READ COMMITTED (see bug 5).
2. **Replay on conflict.** `TransactionFacade` (deliberately not `@Transactional`) catches the constraint violation *after* the losing transaction has rolled back. It then looks up the winner in a **new** transaction and returns it:
```java
try {
    return transactionService.createTransaction(userId, key, request);
} catch (DataIntegrityViolationException e) {
    return transactionService.findReplay(userId, key, request).orElseThrow(() -> e);
}
```
The global exception handler also no longer returns database messages to clients; it logs them.

### Result
20 parallel identical requests: **1 distinct transaction id, returned to all 20 callers, 0 errors** ([PASSED_RUN.txt](PASSED_RUN.txt): `IDEMPOTENCY TEST -> distinct ids=[2] errors=0 finalBalance=900.00`).

---

## 5. The daily limit could be bypassed (isolation level)

This is the subtle one.

### The rule
A wallet may send at most 200,000 per day. Before a transfer, the fraud rule runs:
```sql
SELECT SUM(amount) FROM transactions WHERE source_account_id = ? AND created_at >= today
```
It runs *after* the account row is locked, so transfers from one wallet are already serialized. That looks safe.

### Why it wasn't
MySQL's default isolation level is **REPEATABLE READ**. A transaction's snapshot is fixed at its **first plain read**, and every later plain read sees that snapshot. Here, the first read is the idempotency lookup, which happens **before** waiting for the lock:

| Step | Transfer #7 (waiting in the queue) |
|---|---|
| 1 | Idempotency lookup: **snapshot taken now, 0 sent today** |
| 2 | Waits for the row lock while transfers #1–#6 commit 180,000 |
| 3 | Gets the lock. The balance read is a locking read, so it sees the real balance |
| 4 | `SUM(...)` is a plain read and uses the **old snapshot: 0 sent today** |
| 5 | 0 + 30,000 ≤ 200,000, allowed |

The lock serialized the transfers, but the snapshot made each one blind to the others.

### Measured
`FraudIntegrationTest.dailyLimitHoldsUnderConcurrency`: 10 threads each send 30,000 from a 500,000 wallet ([ISOLATION_BUG_RUN.txt](ISOLATION_BUG_RUN.txt)).
- REPEATABLE READ: **10 succeeded, 0 blocked**, in 3 out of 3 runs. That's 300,000 sent against a 200,000 limit.

### The fix
Money operations run at READ COMMITTED:
```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public TransactionResponse createTransaction(...)
```
Each statement now sees everything committed before it started. The row lock still serializes debits of one wallet, so the SUM after the lock is exact.

Reconciliation deliberately keeps REPEATABLE READ: it *wants* one frozen snapshot across several queries, so a transfer committing halfway through the check can't look like a mismatch.

### Result
READ COMMITTED: **6 succeeded, 4 blocked** (180,000, the correct answer).

**Lesson:** locking a row does not refresh your view of *other* rows. Which isolation level you need depends on which reads happen before and after a lock.

---

## 6. Amounts were rounded silently

### The bug
`amount` was validated only as `≥ 0.01`, so `10.005` was accepted. Java kept the full value, but the column is `DECIMAL(19,2)`, so MySQL stored `10.01`. The response was built from the Java object, so **the API told the client 10.005 while the database recorded 10.01**: a payment of a different amount than the one requested and confirmed.

### The fix
```java
@Digits(integer = 17, fraction = 2, message = "Amount can have at most 2 decimal places")
@DecimalMax(value = "10000000.00")
BigDecimal amount
```

### Result
`10.005` returns **400** with `errors.amount` naming the problem (`ApiFlowTest.badInputIsRejectedWithFieldErrors`).

---

## How I would explain this in an interview (30 seconds each)

- **Double spending:** "Read-check-write isn't atomic, so I lock the row with SELECT FOR UPDATE. A CHECK constraint backs it up in the database."
- **Deadlocks:** "Opposing transfers locked rows in opposite orders. I lock by ascending account id everywhere, so there's no cycle. 1,000 opposing transfers, 0 deadlocks."
- **Pessimistic vs optimistic:** "Optimistic rejected 912 of 1,000 under contention. With retries it needed 23,670 retries. Pessimistic served all 1,000. On low-contention data I'd pick optimistic."
- **Idempotency:** "A unique (user, key) constraint picks one winner. Losers re-check after the lock or replay the winner in a new transaction, so every retry gets the same answer."
- **Isolation:** "REPEATABLE READ fixes the snapshot at the first read, which was before the lock wait, so the daily-limit SUM was stale. READ COMMITTED fixed it: 10 of 10 passing became exactly 6."
- **Server crash mid-transfer:** "Balance updates, the transaction row, both ledger entries, the audit row and the outbox event are in one database transaction. A crash rolls back all of it. The client retries with the same key, which is safe."
