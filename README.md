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
| [docs/implementation-plan.md](docs/implementation-plan.md) | Package layout, what gets coded, test plan per layer |
| [docs/open-questions.md](docs/open-questions.md) | Questions for Ontop and the assumption taken for each one |
| [docs/tech-lead-answers.md](docs/tech-lead-answers.md) | Answers to the three Tech Lead questions |

## Decisions in one line each

1. **Hexagonal architecture** — the domain knows nothing about Spring, HTTP or JPA; mocks are adapters behind ports. ([ADR 001](docs/adr/001-hexagonal-architecture.md))
2. **Debit the wallet first, then pay; refund if the payment fails** — an orchestrated saga with a persisted state machine. ([ADR 002](docs/adr/002-transfer-saga-debit-first.md))
3. **Fee is deducted from the amount sent** (send 20 → fee 2 → recipient gets 18), `BigDecimal`, behind a `FeePolicy` port. ([ADR 003](docs/adr/003-fee-policy-and-money.md))
4. **Idempotency-Key + one in-flight transfer per user** to avoid double debits and balance races. ([ADR 004](docs/adr/004-idempotency-and-concurrency.md))
5. **Internal ledger + reconciliation** against the wallet service and the provider, because the provider never touches the wallet. ([ADR 005](docs/adr/005-ledger-and-reconciliation.md))

## Tech stack

Java 21 · Spring Boot 3.3 · Gradle · PostgreSQL + Flyway (H2 for local) · Spring `RestClient` + Resilience4j · JUnit 5, Mockito, WireMock, ArchUnit, Testcontainers · Docker Compose.

## Build and run

> Filled in during the implementation phase.
