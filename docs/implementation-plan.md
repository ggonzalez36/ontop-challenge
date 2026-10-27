# Implementation

The whole design in [architecture-design.md](architecture-design.md) is coded, except the API gateway, the event consumers and the comparison against wallet/provider statements (no such APIs in the mocks).

## 1. Package layout

```
src/main/java/com/ontop/transfers
├── domain                          plain Java, no framework
│   ├── model        Transfer (aggregate + state machine), TransferStatus, ReviewReason, Money, Recipient,
│   │                BankAccount, RecipientSnapshot, IdempotencyKey, TransferState
│   ├── event        TransferEvent (sealed): TransferCreated, WalletDebited, PaymentSubmitted, TransferCompleted,
│   │                PaymentFailed, TransferRefunded, TransferSentToReview, TransferRejected
│   ├── fee          FeePolicy, PercentageFeePolicy, FeeBreakdown
│   ├── ledger       LedgerPostings (event → balanced double-entry lines), LedgerEntry
│   └── exception    InvalidAmount, InvalidRecipient, InsufficientFunds, IllegalTransferTransition
├── application
│   ├── port/in      ExecuteTransfer, GetTransfer, QuoteTransfer, ManageRecipients, GetTransactionHistory,
│   │                ApplyPaymentOutcome, RecoverStuckTransfers, ReconcileTransfers
│   ├── port/out     WalletBalancePort, WalletTransactionPort, PaymentProviderPort, PaymentStatusPort,
│   │                TransferRepository, RecipientRepository, TransactionHistoryRepository, ReconciliationRepository
│   └── service      one class per use case + PaymentOutcomeHandler (shared second half of the saga)
├── adapter
│   ├── in/web       controllers, DTOs, GlobalExceptionHandler (RFC 9457), security/ (JWT resource server)
│   ├── in/scheduler RecoveryScheduler, ReconciliationScheduler (ShedLock)
│   └── out
│       ├── wallet       WalletHttpAdapter (RestClient + circuit breaker)
│       ├── payments     PaymentsHttpAdapter (pay + lookup, circuit breaker)
│       ├── persistence  JPA entities and adapters, PiiCipher + EncryptedStringConverter, OutboxStore
│       ├── messaging    OutboxRelay (outbox → Kafka)
│       └── metrics      TransferMetrics (counter per committed event)
└── config           BeanConfiguration (wires use cases), SchedulingConfiguration, properties, DemoDataLoader
```

Use cases are plain classes created in `BeanConfiguration`, so the application layer has no Spring annotations (ArchUnit enforces it). Remote results are sealed interfaces (`Succeeded | Rejected | Unknown`, `Found | NotFound | Unavailable`) handled with exhaustive `switch` expressions: a new outcome does not compile until every flow handles it.

## 2. How a state change is stored

```
service: transfer.markWalletDebited(id)          // domain validates the transition, records WalletDebited
service: transfers.save(transfer)
adapter (one DB transaction):
   update transfer (optimistic lock on version)
   for each pulled event:
       insert outbox_event                       // relay → Kafka later
       insert ledger_entry lines                 // LedgerPostings.forEvent
       insert wallet_movement                    // history read model (withdrawals, refunds)
       publish Spring event                      // → TransferMetrics after commit
```

No DB transaction is held open across an HTTP call.

## 3. Tests (160: 149 run locally, 11 PostgreSQL tests need Docker and run in CI)

| Layer | Test class | Tool | What it proves |
|---|---|---|---|
| Domain | `MoneyTest`, `PercentageFeePolicyTest`, `TransferTest`, `RecipientTest`, `LedgerPostingsTest` | JUnit 5 | fee and rounding, transitions, events, recipient validation, balanced postings |
| Application | `ExecuteTransferServiceTest` | Mockito | every branch of the saga, including rejected vs ambiguous refund |
| Application | `TransferRecoveryServiceTest` | Mockito | each stuck status, provider found / not found / unavailable, conflicts |
| Application | `ApplyPaymentOutcomeServiceTest` | Mockito | callback idempotency, refund on failure, contradictions |
| Application | `QueryServicesTest`, `GetTransferServiceTest` | Mockito | quote, recipients, history cursor, reconciliation report |
| Web | `TransferControllerTest`, `RecipientAndHistoryControllerTest`, `PaymentEventsControllerTest` | `@WebMvcTest` + Spring Security test | status codes, JWT (401/403), HMAC signature, problem details |
| HTTP adapters | `WalletHttpAdapterTest`, `PaymentsHttpAdapterTest` | `MockRestServiceServer` | request shapes, outcome mapping, open circuit, lookup |
| Persistence | `TransferPersistenceAdapterTest` | `@DataJpaTest` + Flyway (H2) | constraints, optimistic locking, encryption at rest, atomic outbox/ledger/history, keyset paging, reconciliation queries |
| Persistence | `PostgresPersistenceIT` | Testcontainers PostgreSQL 16 | the same tests on PostgreSQL (CI fails if they are skipped) |
| Messaging | `OutboxRelayKafkaTest` | Embedded Kafka | events reach the topic in order, keyed by transfer, once |
| Architecture | `HexagonalArchitectureTest` | ArchUnit | dependency rule |
| End to end | `ExecuteTransferIntegrationTest` | `@SpringBootTest` + fake mocks on loopback | JWT, execute, replay, signed callback → COMPLETED, refund, 202, recipients, quote, history, metrics |

## 4. CI

`.github/workflows/ci.yml`: build and all tests on JDK 21 (Docker is available on the runner, so the PostgreSQL tests run and the job fails if they were skipped), test reports as an artifact, then a Docker image build.

## 5. Deliverables checklist (from the PDF)

- [x] Public Git repository
- [x] README with build/run instructions
- [x] Postman collection ([postman/](../postman/ontop-transfers.postman_collection.json)); example executions against the real mocks still pending (mock host not reachable from here)
- [x] Unit tests per layer + exception tests
- [x] Design docs (this folder)
- [x] Answers to the Tech Lead questions
- [x] What is left and why (README)
