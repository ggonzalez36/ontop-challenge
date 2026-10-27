# Implementation — execute transfer endpoint

Only `POST /v1/transfers` (plus `GET /v1/transfers/{id}`) is coded. Everything else is in [architecture-design.md](architecture-design.md).

## 1. Package layout

```
src/main/java/com/ontop/transfers
├── TransfersApplication.java
├── domain
│   ├── model        Transfer, TransferState, TransferStatus, Money, Recipient, BankAccount,
│   │                RecipientSnapshot, IdempotencyKey
│   ├── fee          FeePolicy, PercentageFeePolicy
│   └── exception    DomainException, InvalidAmountException, InsufficientFundsException,
│                    IllegalTransferTransitionException
├── application
│   ├── port/in      ExecuteTransferUseCase, ExecuteTransferCommand, TransferOutcome, GetTransferUseCase
│   ├── port/out     WalletBalancePort, WalletTransactionPort, WalletOperationResult,
│   │                PaymentProviderPort, PaymentOrder, PaymentResult, TransferRepository, RecipientRepository
│   ├── exception    RecipientNotFoundException, TransferNotFoundException, IdempotencyKeyConflictException,
│   │                TransferInProgressException, WalletUnavailableException, WalletUserNotFoundException,
│   │                WalletOperationRejectedException
│   └── service      ExecuteTransferService, GetTransferService
├── adapter
│   ├── in/web       TransferController, CreateTransferRequest, TransferResponse, GlobalExceptionHandler
│   └── out
│       ├── wallet       WalletHttpAdapter, WalletProperties
│       ├── payments     PaymentsHttpAdapter, PaymentsProperties (Ontop source account)
│       └── persistence  JPA entities, Spring Data repositories, persistence adapters (mapping inside)
└── config           BeanConfiguration (wires services, ports, FeePolicy, RestClients), FeeProperties

src/main/resources
├── application.yml, application-postgres.yml
├── db/migration     V1__create_recipient.sql, V2__create_transfer.sql
└── db/seed          V100__seed_recipients.sql (demo recipients for user 1000)
```

Use cases are plain classes created in `BeanConfiguration`, so the application layer has no Spring annotations. Remote results are sealed interfaces (`Succeeded | Rejected | Unknown`) and the service handles them with exhaustive `switch` expressions: adding a new outcome does not compile until every flow handles it.

## 2. Algorithm of `ExecuteTransferService.execute(command)`

```
1. existing = transfers.findByUserIdAndIdempotencyKey(userId, key)
   if existing: same fingerprint → return it (replay, 200) else throw IdempotencyKeyConflict (409)
2. recipient = recipients.findByIdAndUserId(recipientId, userId) or throw RecipientNotFound (404)
3. balance = walletBalance.getBalance(userId)            // 404 / 503 propagate, nothing persisted
4. transfer = Transfer.create(...)                       // amount, currency, fee, funds (422)
5. transfers.saveNew(transfer)                           // unique key + in-flight constraint → 409
6. walletTransactions.withdraw(userId, amount)
     Succeeded → WALLET_DEBITED
     Rejected  → REJECTED, throw WalletOperationRejected (422)
     Unknown   → IN_REVIEW (202)
7. payments.pay(transferId, recipient snapshot, netAmount)
     Accepted  → PROCESSING (201)
     Rejected  → PAYMENT_FAILED → refund
     Unknown   → IN_REVIEW (202), never refunded automatically
8. refund: walletTransactions.topUp(userId, amount)
     Succeeded → REFUNDED (201)
     otherwise → stays PAYMENT_FAILED for the recovery job (designed)
```

Every `save` is its own short DB transaction; no transaction is held open across an HTTP call.

## 3. Tests (81, all passing)

| Layer | Test class | Tool | What it proves |
|---|---|---|---|
| Domain | `MoneyTest` | JUnit 5 | 2-decimal normalization, >2 decimals rejected, currency checks |
| Domain | `PercentageFeePolicyTest` | JUnit 5 params | 20 → 2.00; HALF_EVEN (33.35 → 3.34, 33.25 → 3.32); invalid rates |
| Domain | `TransferTest` | JUnit 5 | fee deducted from amount, funds/amount/currency rules, valid and illegal transitions |
| Application | `ExecuteTransferServiceTest` | Mockito | happy path; provider rejection → full refund; refund failure → PAYMENT_FAILED; provider timeout → IN_REVIEW without refund; wallet unknown → no payment; wallet rejection; insufficient funds persists nothing; foreign recipient; wallet down; replay; key conflict |
| Application | `GetTransferServiceTest` | Mockito | owner check |
| Web adapter | `TransferControllerTest` | `@WebMvcTest` | 201 + Location, 202, 200 replay, 400 (missing header, invalid body, 3 decimals), 404, 409 ×2, 422, 503 + Retry-After, 500 without internals |
| Wallet adapter | `WalletHttpAdapterTest` | `MockRestServiceServer` | request shape (negative amount for withdraw), 200 / 400 / 404 / 500 / timeout mapping |
| Payments adapter | `PaymentsHttpAdapterTest` | `MockRestServiceServer` | full request body with configured Ontop source, Accepted / Rejected / Unknown mapping |
| Persistence | `TransferPersistenceAdapterTest` | `@DataJpaTest` + Flyway (H2) | round trip, idempotency lookup, one in-flight transfer per user, reused key, optimistic locking |
| Architecture | `HexagonalArchitectureTest` | ArchUnit | domain and application free of Spring/JPA/adapters; adapters independent |
| End to end | `ExecuteTransferIntegrationTest` | `@SpringBootTest` + JDK `HttpServer` fake of the mocks | happy path + replay + GET, provider failure → refund, insufficient funds |

## 4. Deliverables checklist (from the PDF)

- [ ] Public Git repository
- [x] README with build/run instructions
- [x] Postman collection ([postman/](../postman/ontop-transfers.postman_collection.json)); example executions against the real mocks still pending (mock host not reachable from here)
- [x] Unit tests per layer + exception tests
- [x] Design docs (this folder)
- [x] Answers to the Tech Lead questions
- [x] "What I would do with more time" (README)
