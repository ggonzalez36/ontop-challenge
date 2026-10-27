# ADR 005 — Internal double-entry ledger and three-way reconciliation

**Status:** Accepted (internal reconciliation coded; comparison with wallet and provider statements pending APIs)

## Context

The provider moves money but never updates the wallet; the wallet changes balances but knows nothing about bank transfers. Each side can be correct on its own and still disagree with the other. Finance needs to prove, every day, that what users were debited equals what left the bank plus fees earned plus refunds.

## Decision

1. **Append-only double-entry ledger** owned by the transfers service. Each state change posts balanced entries (sum of debits = sum of credits per transfer):

   | Event | Debit | Credit |
   |---|---|---|
   | Wallet debited 20.00 | `user_wallet:{id}` 20.00 | `transfers_in_transit` 20.00 |
   | Payment completed | `transfers_in_transit` 20.00 | `ontop_bank` 18.00, `fee_revenue` 2.00 |
   | Payment failed, refund | `transfers_in_transit` 20.00 | `user_wallet:{id}` 20.00 |

   Rows are never updated or deleted; corrections are new entries. `transfers_in_transit` must return to zero for every closed transfer.

2. **Three-way reconciliation job** (daily, plus intraday for open transfers): compare
   - our transfers + ledger,
   - wallet transactions (by `wallet_transaction_id`),
   - provider payments / bank statement (by `payment id` / external reference).

   Every mismatch becomes a reconciliation case with an owner, never an automatic fix of money.

3. **Events through a transactional outbox** (`TransferCompleted`, `TransferRefunded`, `TransferInReview`) written in the same DB transaction as the status change, published to Kafka by a relay. Notifications and the history read model consume them.

## Consequences

- Finance gets a single source of truth for fees and money in transit.
- More tables and a job to operate. Worth it in a fintech: "how much money is in flight right now?" must be a query, not an investigation.
- The history endpoint (`GET /v1/transactions`) reads from this model, so refunds appear as their own line exactly as in the "Recent transactions" screen.
