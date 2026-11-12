# PayLedger

A secure wallet and transaction REST API built with **Java 21, Spring Boot, MySQL and JWT**. It handles income, expense and transfer transactions and stays correct under concurrent requests: no overdrafts, no deadlocks, and retried requests never move money twice.

**Live API (Swagger UI):** https://payledger-production-24a2045d.up.railway.app/swagger-ui/index.html

## Try it in 2 minutes

1. Open the Swagger UI link above.
2. `POST /api/users`: register (name, email, password of 8+ characters).
3. `POST /api/auth/login`: log in and copy the `token` from the response.
4. Click **Authorize** (top right), paste the token (without the word "Bearer"), and confirm.
5. `POST /api/users/{userId}/accounts`: create two accounts (for example type `BANK` and `CASH`).
6. `POST /api/users/{userId}/transactions`: create a `TRANSFER` between them. Set an `Idempotency-Key` header (any unique string up to 64 characters).
7. Send the same request again with the same key: you get the same transaction back and the balance changes only once.

The first request after a period of inactivity can take a few seconds while the host wakes up.

## Features

- Stateless **JWT authentication**, BCrypt password hashing, `USER` and `ADMIN` roles
- **Ownership checks**: a user can only read or change their own accounts and transactions (401 without a token, 403 for another user's data)
- Accounts, plus **income, expense and transfer** transactions with paginated, sortable history
- **Pessimistic row locking** (`SELECT ... FOR UPDATE`) with **ordered lock acquisition** to prevent overdrafts and deadlocks
- **Idempotency keys** backed by a database unique constraint, so retries never duplicate a payment
- Global exception handling with a consistent JSON error format
- OpenAPI / Swagger UI documentation with a JWT **Authorize** button
- Multi-stage Docker build and Docker Compose setup, deployed on Railway with MySQL

## API overview

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/users` | Public | Register |
| POST | `/api/auth/login` | Public | Log in, receive a JWT |
| GET | `/api/users/{id}` | Owner or admin | Get a user |
| GET | `/api/users` | Admin | List users |
| DELETE | `/api/users/{id}` | Admin | Delete a user |
| POST | `/api/users/{userId}/accounts` | Owner | Create an account |
| GET | `/api/users/{userId}/accounts` | Owner | List accounts |
| POST | `/api/users/{userId}/transactions` | Owner | Create a transaction (requires `Idempotency-Key` header) |
| GET | `/api/users/{userId}/transactions/{transactionId}` | Owner | Get a transaction |
| GET | `/api/users/{userId}/transactions/account/{accountId}` | Owner | Paginated history for an account |
| GET | `/api/health` | Public | Health check |

## Concurrency testing

`ConcurrencyTest` runs against a real MySQL database with real threads, all released at the same instant.

| Test | Scenario | Result |
|---|---|---|
| Overdraft | 50 parallel withdrawals of 100 from a balance of 1,000 | Exactly 10 succeed, final balance 0.00 |
| Idempotency | 20 threads send the same request with the same key | Money moves once (balance 900.00) |
| Opposing transfers | 1,000 transfers A to B and B to A on 32 threads | 0 failures, 0 deadlocks, balances exact (about 69 transfers/second on a local machine) |

Raw output from the runs is in [`docs/`](docs/). Run the tests yourself with a local MySQL database named `payledger_test`:

```bash
./mvnw test -Dtest=ConcurrencyTest
```

### Why it works

- **Locking:** the original read, check, write sequence let two requests both pass the balance check. `FOR UPDATE` makes the second request wait and then see the updated balance.
- **Lock ordering:** transfers always lock the lower account id first, so A to B and B to A can never each hold one lock and wait for the other.
- **Idempotency:** `UNIQUE(user_id, idempotency_key)` lets the database decide the winner when identical requests race. The loser rolls back, including its balance changes, and its retry returns the stored result.

More reasoning is in [DECISIONS.md](DECISIONS.md).

## Run locally with Docker

```bash
cp .env.example .env        # then set DB_PASSWORD and JWT_SECRET
docker compose up --build
```

Open http://localhost:8080/swagger-ui/index.html

`JWT_SECRET` must be at least 32 characters. Generate one with PowerShell:

```powershell
$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

## Run without Docker

Requires Java 21 and MySQL. Create a database named `payledger`, then set these environment variables:

```
SPRING_DATASOURCE_PASSWORD=<your MySQL password>
JWT_SECRET=<32+ character secret>
```

```bash
./mvnw spring-boot:run
```

Optional: `SPRING_DATASOURCE_URL` and `SPRING_DATASOURCE_USERNAME` override the defaults (`jdbc:mysql://localhost:3306/payledger` and `root`).

## Tech stack

Java 21, Spring Boot, Spring Security, Spring Data JPA (Hibernate), MySQL, JWT (jjwt), springdoc-openapi, JUnit 5, Maven, Docker, Railway.

## Limitations

- Transfers move money between a single user's own accounts (a personal wallet, not peer-to-peer payments between users).
- The schema is created by Hibernate (`ddl-auto=update`) instead of versioned Flyway migrations.
- There is no separate double-entry ledger table yet.
- Account creation accepts an initial balance for convenience, which is not production-grade.

## Roadmap

Double-entry ledger with a nightly reconciliation job, transactional outbox, Flyway migrations, GitHub Actions CI, and peer-to-peer transfers between users.