# Ontop — Wallet to Bank Withdrawal

[![CI](https://github.com/ggonzalez36/ontop-challenge/actions/workflows/ci.yml/badge.svg)](https://github.com/ggonzalez36/ontop-challenge/actions/workflows/ci.yml)

Solution to the Ontop tech challenge (v1.1): a user withdraws money from their Ontop wallet to one of their saved bank accounts, paying a 10% fee.

The challenge asks to code the execute-transfer endpoint and design the rest. Both are done: the design is in [docs/](docs/), and the whole design is implemented with hexagonal architecture, tests per layer and CI.

## Where to start

| Document | What it answers |
|---|---|
| [docs/architecture-design.md](docs/architecture-design.md) | Context, containers, hexagon, domain model, state machine, sequences, data model, API, errors |
| [docs/adr/](docs/adr/) | Why each important decision was made, with the options discarded |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Package layout, how a state change is stored, the test suite, CI |
| [docs/open-questions.md](docs/open-questions.md) | Questions for Ontop and the assumption taken for each one |
| [docs/tech-lead-answers.md](docs/tech-lead-answers.md) | Answers to the three Tech Lead questions |
| [postman/](postman/ontop-transfers.postman_collection.json) | Postman collection for every endpoint, with a development token |

## Decisions in one line each

1. **Hexagonal architecture**: the domain knows nothing about Spring, HTTP or JPA; mocks are adapters behind ports. Enforced by ArchUnit. ([ADR 001](docs/adr/001-hexagonal-architecture.md))
2. **Debit the wallet first, then pay; refund if the payment fails; a timeout is not a failure.** Ambiguous outcomes go to `IN_REVIEW` and a recovery job resolves them only when it is sure. ([ADR 002](docs/adr/002-transfer-saga-debit-first.md))
3. **Fee deducted from the amount sent** (send 20, fee 2, recipient gets 18), `BigDecimal`, behind a `FeePolicy` port, shared by the quote and the transfer. ([ADR 003](docs/adr/003-fee-policy-and-money.md))
4. **Idempotency-Key + one in-flight transfer per user + optimistic locking**, enforced by the database. ([ADR 004](docs/adr/004-idempotency-and-concurrency.md))
5. **Double-entry ledger, transactional outbox to Kafka, daily reconciliation.** ([ADR 005](docs/adr/005-ledger-and-reconciliation.md))
6. **JWT, AES-GCM encryption of bank data, circuit breakers, Prometheus metrics.** ([ADR 006](docs/adr/006-security-resilience-observability.md))

## API

All `/v1` endpoints need `Authorization: Bearer <JWT>`; the user is the token subject.

| Method | Path | Purpose |
|---|---|---|
| POST | `/v1/recipients` | Save a bank account (name, national id, routing, account, bank) |
| GET | `/v1/recipients` | List saved accounts (masked) |
| DELETE | `/v1/recipients/{id}` | Delete a saved account |
| POST | `/v1/transfers/quotes` | Fee, net amount and available funds for an amount |
| **POST** | **`/v1/transfers`** | **Execute a transfer** (`Idempotency-Key` header required) |
| GET | `/v1/transfers/{id}` | Transfer detail |
| GET | `/v1/transactions?limit=&cursor=` | Recent transactions, newest first, cursor pagination |
| POST | `/internal/v1/payment-events` | Provider callback, HMAC-signed (no JWT) |
| GET | `/actuator/health`, `/actuator/prometheus` | Health and metrics |

| Execute transfer response | When |
|---|---|
| `201` with `status: PROCESSING` | Wallet debited, provider accepted the payment |
| `201` with `status: REFUNDED` | Provider rejected the payment, the full amount went back to the wallet |
| `202` with `status: IN_REVIEW` | Timeout or ambiguous error: money may have moved, resolved by recovery or a person |
| `200` | Same `Idempotency-Key` and body: the original transfer, no new side effects |
| `400` / `401` / `404` / `409` / `422` / `503` | `application/problem+json` with a stable `code` (see the error table in the design) |

## Build and run

Requirements: JDK 21 or newer. The Gradle wrapper downloads everything else.

```bash
./gradlew test       # 160 tests; the 11 PostgreSQL ones need Docker (they always run in CI)
./gradlew bootRun    # http://localhost:8080, in-memory H2, demo recipients for user 1000
```

On Windows use `gradlew.bat`. Full stack with PostgreSQL and Kafka (outbox relay on):

```bash
docker compose up --build
```

### Try it

Import the Postman collection; it already carries a development token for user 1000. Or with curl:

```bash
TOKEN=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMDAwIiwiaWF0IjoxNzkzMDg4MDAwLCJleHAiOjIxMDg3MDcyMDB9.CDO2-YKJb9FCAXw9rA8ZEwwMthYbYSIcEAfd2aeteOM

curl -i -X POST http://localhost:8080/v1/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"recipientId": "6f1c2b8e-3a4d-4e5f-9a6b-7c8d9e0f1a2b", "amount": 20.00, "currency": "USD"}'
```

The token is signed with the **development** secret in `application.yml`; it is useless against a deployment configured with `JWT_ISSUER_URI`.

### Configuration

| Env var | Default | Meaning |
|---|---|---|
| `WALLET_BASE_URL`, `PAYMENTS_BASE_URL` | `http://mockoon.tools.getontop.com:3000` | Ontop mocks |
| `ONTOP_ACCOUNT_NAME`, `ONTOP_ACCOUNT_NUMBER`, `ONTOP_ROUTING_NUMBER` | `ONTOP INC`, `0245253419`, `028444018` | Ontop's source bank account |
| `JWT_ISSUER_URI` | empty | Identity provider; when set, the dev HMAC secret is not used |
| `JWT_HMAC_SECRET`, `PII_ENCRYPTION_KEY`, `PAYMENTS_WEBHOOK_SECRET` | dev values | Must be overridden outside local development |
| `PAYMENTS_LOOKUP_ENABLED` | `false` | Provider "payment by reference" lookup used by the recovery job (the mocks lack it) |
| `OUTBOX_RELAY_ENABLED`, `KAFKA_BOOTSTRAP_SERVERS` | `false`, `localhost:9092` | Publish events to Kafka |
| `JOBS_ENABLED` | `true` | Recovery (every minute) and reconciliation (03:00) jobs |
| `SERVER_ADDRESS` | `127.0.0.1` | Bind address (the container uses `0.0.0.0`) |

The mock URLs and payloads are assumptions until the mocks' Postman collection is confirmed ([open questions](docs/open-questions.md)); only the two HTTP adapters depend on them.

## Project layout

```
src/main/java/com/ontop/transfers
├── domain        Transfer (aggregate, state machine, events), Money, Recipient, FeePolicy, ledger postings
├── application   use cases (port.in), ports to the outside (port.out), one service per use case
├── adapter
│   ├── in        web (REST, JWT, problem details), scheduler (recovery, reconciliation)
│   └── out       wallet and payments HTTP clients, JPA persistence + encryption + outbox, Kafka relay, metrics
└── config        wires use cases to adapters
```

## What is left

- **External reconciliation**: comparing the ledger with the wallet's transactions and the provider's statement needs APIs the mocks do not offer.
- **Provider lookup contract**: implemented against an assumed endpoint and off by default until the provider confirms it.
- **Operations**: alert rules on the published metrics, distributed tracing, KMS/Vault for keys with rotation, API gateway with rate limiting and AML amount limits, event consumers (notifications).
