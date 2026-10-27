# Open questions for Ontop

The challenge invites questions. Each one lists the assumption taken so work is not blocked.

## Mocks and integrations

| # | Question | Assumption |
|---|---|---|
| 1 | Can you share the Postman collection (URLs, payloads, the exact triggers for the 404/500/timeout examples)? | Contracts in [architecture-design.md §12](architecture-design.md#12-integration-with-the-mocks); only adapters change if different. |
| 2 | Is the wallet transactions API idempotent (does it accept a client reference)? Does it reject a withdrawal that leaves a negative balance? | Not idempotent, no overdraft protection → we never retry the POST blindly and serialize debits per user (ADR 004). |
| 3 | Does the provider accept an external reference and offer a "get payment by reference" endpoint or status webhooks? | Yes in production (needed to resolve `IN_REVIEW`); not available in the mock, so `IN_REVIEW` is final in the coded version. |
| 4 | Which provider 500s are definitive and which may still execute the payment? | The mock's "500 with destination name" = definitive failure → refund; "500 timeout" = unknown → `IN_REVIEW`. |
| 5 | What does provider `status: Processing` evolve into, and how long until a payment is final? | `Processing → Completed | Failed` within minutes to 1–2 days (ACH). |
| 5b | Does the provider send status callbacks, and how are they signed? | Yes; `POST /internal/v1/payment-events` with `{reference, paymentId, status, reason}` and `X-Signature: sha256=<HMAC of the body>`. Only the controller changes if the format differs. |
| 5c | Do the wallet or the provider expose statements (transactions by date) for reconciliation? | Not in the mocks; reconciliation checks our own ledger and stale transfers until they do. |

## Business rules

| # | Question | Assumption |
|---|---|---|
| 6 | Is the 10% fee taken from the amount sent (send 20, receive 18) or added on top? | Taken from the amount sent, as the screen shows (ADR 003). |
| 7 | Rounding rule for the fee, and minimum/maximum per transfer or per day? | HALF_EVEN to the cent; min such that net > 0; max configurable. |
| 8 | Is the fee refunded when the transfer fails? | Yes, full amount back to the wallet. |
| 9 | Only USD, or will recipients have other currencies (FX)? | USD only; `Money` carries currency so FX can be added. |
| 10 | Must the recipient's name match the Ontop user (own account only) or can users send to third parties? | Screen says "your account"; we store full recipient data and do not enforce a name match (KYC/AML decision). |
| 11 | Validations for routing number (US ABA 9 digits + checksum?) and national ID per country? | ABA 9-digit + checksum for US; national ID as free text. |

## Non-functional

| # | Question | Assumption |
|---|---|---|
| 12 | Expected volume and latency targets? | Low thousands of transfers/day; p99 < 3 s for the execute call excluding provider latency. |
| 13 | How is the user identified (JWT from a gateway? which claim)? | JWT `sub` at the gateway; `X-User-Id` header in the challenge. |
| 14 | Preferred stack versions (Java 17/21, Spring Boot 3)? | Java 21, Spring Boot 3.3 (the PDF allows Java 8+ / Boot 2+). |
