# ADR 001 — Hexagonal architecture with explicit ports

**Status:** Accepted

## Context

Ontop uses hexagonal architecture. The service talks to three systems it does not own (wallet balance, wallet transactions, payments provider), and today they are mocks. Their contracts will change, and the provider may be replaced. The fee rule and the transfer state machine are the business logic that must stay stable and testable without the network.

## Decision

Three layers in one Gradle module, separated by package and enforced with ArchUnit:

```
com.ontop.transfers
├── domain          Transfer, Money, Recipient, TransferStatus, FeePolicy, domain exceptions
│                   (plain Java, no Spring, no JPA, no Jackson)
├── application
│   ├── port.in     ExecuteTransferUseCase, ExecuteTransferCommand
│   ├── port.out    WalletBalancePort, WalletTransactionPort, PaymentProviderPort,
│   │               TransferRepository, RecipientRepository
│   └── service     ExecuteTransferService (orchestrates the saga, @Transactional-free)
├── adapter
│   ├── in.web      TransferController, DTOs, GlobalExceptionHandler
│   └── out
│       ├── wallet      WalletHttpAdapter (RestClient)
│       ├── payments    PaymentsHttpAdapter (RestClient)
│       └── persistence JPA entities, Spring Data repos, mappers
└── config          Spring @Configuration that wires services to ports
```

- Use cases are interfaces (`port.in`); the controller depends on the interface, not the service (**D**ependency inversion, **I**nterface segregation).
- Each output port has one reason to change (**S**RP): balance and wallet transactions are two ports even though the same team owns them, because the quote use case only needs balance.
- New fee rules or providers are new implementations of an existing port (**O**pen/closed, **L**iskov).
- JPA entities are separate from domain objects and mapped in the adapter. More code, but the domain keeps its invariants (no public setters, no no-arg constructor for Hibernate).

## Alternatives considered

| Option | Why not |
|---|---|
| Classic layered (controller → service → repository) with JPA entities as the model | Faster to write, but the domain becomes anemic and tied to Hibernate; mocks leak HTTP types into services. Ontop explicitly asked for hexagonal. |
| Multi-module Gradle (domain / application / adapters as modules) | Compiler-enforced boundaries, but heavy for a one-endpoint challenge. ArchUnit gives the same guarantee with less ceremony; can be split later. |

## Consequences

- Each layer is unit-testable alone: domain with plain JUnit, service with Mockito on ports, adapters with WireMock / `@DataJpaTest`, controller with `@WebMvcTest`.
- Mapping code between DTO ↔ command ↔ domain ↔ entity. Accepted cost.
