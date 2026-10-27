# Implementation plan — execute transfer endpoint

Only `POST /v1/transfers` is coded. Everything else is in [architecture-design.md](architecture-design.md).

## 1. Package layout

```
src/main/java/com/ontop/transfers
├── TransfersApplication.java
├── domain
│   ├── model        Transfer, TransferId, TransferStatus, Money, Recipient, BankAccount, UserId
│   ├── fee          FeePolicy, PercentageFeePolicy
│   └── exception    DomainException, InvalidAmountException, InsufficientFundsException,
│                    IllegalTransferTransitionException
├── application
│   ├── port/in      ExecuteTransferUseCase, ExecuteTransferCommand
│   ├── port/out     WalletBalancePort, WalletTransactionPort, PaymentProviderPort,
│   │                PaymentResult (Accepted | Rejected | Unknown), TransferRepository,
│   │                RecipientRepository
│   ├── exception    RecipientNotFoundException, IdempotencyKeyConflictException,
│   │                TransferInProgressException, WalletUnavailableException, WalletUserNotFoundException
│   └── service      ExecuteTransferService
├── adapter
│   ├── in/web       TransferController, CreateTransferRequest, TransferResponse,
│   │                GlobalExceptionHandler (ProblemDetail)
│   └── out
│       ├── wallet       WalletHttpAdapter, WalletProperties, wallet DTOs
│       ├── payments     PaymentsHttpAdapter, PaymentsProperties (Ontop source account), DTOs
│       └── persistence  TransferJpaEntity, RecipientJpaEntity, Spring Data repos,
│                        TransferPersistenceAdapter, RecipientPersistenceAdapter, mappers
└── config           BeanConfiguration (wires ExecuteTransferService with ports + FeePolicy),
                     HttpClientConfiguration (RestClient + timeouts + circuit breakers)

src/main/resources
├── application.yml          mock URLs, Ontop account, fee rate, timeouts
└── db/migration             V1__recipient.sql, V2__transfer.sql, V3__seed_recipients.sql
```

`ExecuteTransferService` is a plain class instantiated in `BeanConfiguration`, so the application layer has no Spring annotations.

## 2. Algorithm of `ExecuteTransferService.execute(command)`

```
1. existing = transfers.findByUserAndKey(userId, key)
   if existing: same hash → return existing (replay) else throw IdempotencyKeyConflict
2. recipient = recipients.findByIdAndUser(recipientId, userId) or throw RecipientNotFound
3. transfer = Transfer.create(userId, recipient, amount, feePolicy, key, hash)   // validates amount
4. balance = walletBalance.get(userId); if balance < amount → throw InsufficientFunds (nothing persisted)
5. transfers.saveNew(transfer)            // unique key + in-flight index may throw → 409
6. walletTx.withdraw(userId, amount)
     ok        → transfer.markWalletDebited(id); save
     4xx       → transfer.reject(reason); save; throw mapped exception
     5xx/timeout → transfer.markOutcomeUnknown(reason); save; return (202)
7. result = payments.pay(ontopAccount, recipient, netAmount, transferId)
     Accepted  → markPaymentSubmitted(paymentId); save; return (201 PROCESSING)
     Rejected  → markPaymentFailed(reason); save; refund()
     Unknown   → markOutcomeUnknown(reason); save; return (202 IN_REVIEW)
8. refund(): walletTx.topup(userId, amount)
     ok    → markRefunded(id); save
     error → keep PAYMENT_FAILED (recovery job retries); log + metric
```

Every `save` is its own short DB transaction; no DB transaction is held open across an HTTP call.

## 3. Test plan (at least one per layer + exceptions)

| Layer | Test class | Tool | Cases |
|---|---|---|---|
| Domain | `MoneyTest`, `PercentageFeePolicyTest` | JUnit 5 | 20 → 2 / 18; rounding 0.05 → 0.00/0.05 (HALF_EVEN) and 33.35 → 3.34; `amount = fee + net`; negative / zero / 3-decimal amounts rejected |
| Domain | `TransferTest` | JUnit 5 | valid transitions; illegal transition throws `IllegalTransferTransitionException`; recipient snapshot copied |
| Application | `ExecuteTransferServiceTest` | Mockito | happy path → PROCESSING; insufficient funds → no port write; recipient of another user → 404; provider rejects → REFUNDED with full amount; refund fails → PAYMENT_FAILED; provider timeout → IN_REVIEW and **no refund**; idempotent replay → no port calls; key conflict → 409 |
| Web adapter | `TransferControllerTest` | `@WebMvcTest` | 201 body + Location; missing Idempotency-Key → 400; validation → 400 problem+json; each exception → its status |
| Wallet adapter | `WalletHttpAdapterTest` | WireMock | maps 200 / 404 / 500 / timeout to result or exception; sends negative amount for withdraw |
| Payments adapter | `PaymentsHttpAdapterTest` | WireMock | builds body with configured Ontop source; 200 → Accepted; 400/500 → Rejected; timeout → Unknown |
| Persistence | `TransferPersistenceAdapterTest` | `@DataJpaTest` (H2 or Testcontainers PG) | round-trip mapping; duplicate key → exception; second in-flight transfer → exception |
| Architecture | `HexagonalArchitectureTest` | ArchUnit | domain has no Spring/JPA imports; application does not depend on adapters |
| End to end | `ExecuteTransferIT` | `@SpringBootTest` + WireMock | full slice, happy path and provider-failure refund |

## 4. Deliverables checklist (from the PDF)

- [ ] Public Git repository
- [ ] README with build/run instructions (`./gradlew test`, `./gradlew bootRun`, `docker compose up`)
- [ ] Postman collection with the endpoint and example executions (happy path, refund, insufficient funds, idempotent replay)
- [ ] Unit tests per layer + exception tests
- [ ] Design docs (this folder)
- [ ] Answers to the Tech Lead questions
- [ ] "What I would do with more time" section (recovery job, callbacks, outbox/Kafka, ledger, recipients CRUD, auth, multi-currency)

## 5. Build order

1. Gradle project, domain + domain tests (no Spring).
2. Ports + service + Mockito tests (all saga branches).
3. Web adapter + exception handler + `@WebMvcTest`.
4. Wallet and payments adapters + WireMock tests (confirm contracts against the real mocks first).
5. Persistence + Flyway + `@DataJpaTest`.
6. ArchUnit, integration test, README, Postman collection, Docker Compose.
