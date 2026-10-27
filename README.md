# Ontop — Wallet to Bank Withdrawal

Design and implementation of the Ontop tech challenge (v1.1): a user withdraws money from their Ontop wallet and transfers it to one of their saved bank accounts, paying a 10% fee.

The whole business case is **designed** (recipients, quote, transfer execution, history, reconciliation). Only the **execute transfer** endpoint is **coded**, using the provided mocks as if they were other microservices.

| Legend | Meaning |
|---|---|
| ✅ Coded | Implemented in this repo and covered by tests |
| 🟡 Stubbed | Port and adapter exist with the real contract; logic is minimal or seeded |
| ⬜ Designed | Part of the target design; documented, not coded |

## Where to start

| Document | What it answers |
|---|---|
| [docs/architecture-design.md](docs/architecture-design.md) | Context, containers, hexagon, domain model, state machine, sequences, data model, API, errors |
| [docs/adr/](docs/adr/) | Why each important decision was made, with the options discarded |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Package layout, the coded algorithm, test plan per layer |
| [docs/open-questions.md](docs/open-questions.md) | Questions for Ontop and the assumption taken for each one |
| [docs/tech-lead-answers.md](docs/tech-lead-answers.md) | Answers to the three Tech Lead questions |
| [postman/](postman/ontop-transfers.postman_collection.json) | Postman collection with the endpoint and example executions |

## Decisions in one line each

1. **Hexagonal architecture**: the domain knows nothing about Spring, HTTP or JPA; mocks are adapters behind ports. Enforced by an ArchUnit test. ([ADR 001](docs/adr/001-hexagonal-architecture.md))
2. **Debit the wallet first, then pay; refund if the payment fails; a timeout is not a failure** (it goes to `IN_REVIEW`, never auto-refunded). ([ADR 002](docs/adr/002-transfer-saga-debit-first.md))
3. **Fee is deducted from the amount sent** (send 20, fee 2, recipient gets 18), `BigDecimal`, behind a `FeePolicy` port. ([ADR 003](docs/adr/003-fee-policy-and-money.md))
4. **Idempotency-Key + one in-flight transfer per user**, enforced by database constraints. ([ADR 004](docs/adr/004-idempotency-and-concurrency.md))
5. **Internal ledger + reconciliation** against the wallet and the provider (designed). ([ADR 005](docs/adr/005-ledger-and-reconciliation.md))

## Tech stack

Java 21 (bytecode target; builds on JDK 21+) · Spring Boot 3.5 · Gradle 9 (wrapper) · H2 in PostgreSQL mode for local runs, PostgreSQL via Docker Compose · Flyway · Spring `RestClient` · JUnit 5, Mockito, AssertJ, MockRestServiceServer, ArchUnit.

## Build and run

Requirements: JDK 21 or newer. Nothing else; the Gradle wrapper downloads Gradle.

```bash
./gradlew test       # 81 tests: domain, application, adapters, architecture, end-to-end
./gradlew bootRun    # http://localhost:8080, in-memory H2 with seeded recipients
```

On Windows use `gradlew.bat` instead of `./gradlew`.

With PostgreSQL (requires Docker):

```bash
docker compose up --build
```

### Configuration

| Property / env var | Default | Meaning |
|---|---|---|
| `WALLET_BASE_URL` | `http://mockoon.tools.getontop.com:3000` | Wallet balance and transactions mock |
| `PAYMENTS_BASE_URL` | `http://mockoon.tools.getontop.com:3000` | Payments provider mock |
| `ONTOP_ACCOUNT_NAME` / `_NUMBER` / `ONTOP_ROUTING_NUMBER` | `ONTOP INC` / `0245253419` / `028444018` | Ontop's source bank account |
| `ontop.fees.withdrawal-rate` | `0.10` | Fee rate |
| `SERVER_ADDRESS` | `127.0.0.1` | Bind address (the container uses `0.0.0.0`) |

The mock URLs and payloads are assumptions until the Postman collection with the mocks is confirmed (see [open questions](docs/open-questions.md)); only the two HTTP adapters depend on them.

### Try it

```bash
curl -i -X POST http://localhost:8080/v1/transfers \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 1000' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"recipientId": "6f1c2b8e-3a4d-4e5f-9a6b-7c8d9e0f1a2b", "amount": 20.00, "currency": "USD"}'
```

| Response | When |
|---|---|
| `201` with `status: PROCESSING` | Wallet debited, provider accepted the payment |
| `201` with `status: REFUNDED` | Provider rejected the payment, the full amount went back to the wallet |
| `202` with `status: IN_REVIEW` | Timeout or ambiguous error: money may have moved, resolved by reconciliation |
| `200` | Same `Idempotency-Key` and body: the original transfer, no new side effects |
| `400` / `404` / `409` / `422` / `503` | Validation, unknown recipient, key conflict or transfer in progress, insufficient funds, wallet down. Body is `application/problem+json` with a `code`. |

## Project layout

```
src/main/java/com/ontop/transfers
├── domain        Transfer (aggregate + state machine), Money, Recipient, FeePolicy — plain Java
├── application   use cases (port.in), ports to the outside (port.out), ExecuteTransferService
├── adapter
│   ├── in.web    REST controller, DTOs, RFC 9457 error handler
│   └── out       wallet and payments HTTP clients, JPA persistence
└── config        wires use cases to adapters
```

## What I would do with more time

- Recovery job for transfers stuck in `CREATED`, `WALLET_DEBITED`, `PAYMENT_FAILED` or `IN_REVIEW` (ShedLock + optimistic locking already in place), and the provider callback endpoint to move `PROCESSING` to `COMPLETED`.
- Transactional outbox + Kafka for `TransferCompleted` / `TransferRefunded` events, and the double-entry ledger with daily reconciliation (ADR 005).
- The rest of the designed API: recipients CRUD with encrypted PII, quotes, cursor-paginated history.
- Resilience4j circuit breakers per adapter, Micrometer metrics per status transition, JWT authentication instead of `X-User-Id`.
- Testcontainers with real PostgreSQL in CI and a GitHub Actions pipeline.
