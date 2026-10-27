# ADR 002 — Orchestrated saga: debit the wallet first, pay second, compensate with a refund

**Status:** Accepted

## Context

A withdrawal changes two systems with no shared transaction: the wallet service (user balance) and the external payments provider (real money). Any of the calls can succeed, fail, or time out with an unknown result. The "Recent transactions" screen already shows a "Refund from bank transfer · Refunded to your wallet" entry, which tells us the business expects compensation by refund.

## Decision

1. **Order of steps:** check balance → persist `CREATED` → **withdraw from wallet** → persist `WALLET_DEBITED` → **pay via provider** → persist `PROCESSING`. Completion (`COMPLETED`) arrives later by callback or polling.
2. **Persist before and after every external call.** The transfer row is the saga log; after a crash, the recovery job knows exactly which step was last confirmed.
3. **Compensation:** if the provider returns a definitive failure, mark `PAYMENT_FAILED` and top up the wallet with the full amount (fee included) → `REFUNDED`. A failed refund stays `PAYMENT_FAILED` and is retried by the recovery job.
4. **Unknown outcome is its own state.** A timeout or connection reset on the payment call goes to `IN_REVIEW`, never to refund. Refunding a payment that actually went through pays the user twice and is unrecoverable without contacting the user.
5. **Orchestration, not choreography:** a single service drives the steps. There are only two remote steps; events between services would add a broker to the critical path without adding clarity.

## Why debit first

| Order | If the second step fails | Who is exposed |
|---|---|---|
| **Debit wallet, then pay** | User's wallet is short until the refund runs | User, for seconds/minutes; fully recoverable by Ontop |
| Pay, then debit wallet | Money already left Ontop's bank; wallet debit can fail (no funds after a concurrent withdrawal) | Ontop loses real money, recovery requires collecting from the user |

Ontop's bank account backs **all** wallets, so paying before debiting can spend other users' money. Debit-first keeps the invariant *sum of wallet balances ≤ funds in Ontop's account*.

## Synchronous vs asynchronous execution

The endpoint runs the debit and the payment request **synchronously** (the provider answers "Processing" quickly) and returns 201 `PROCESSING`. The final state is asynchronous.

Fully asynchronous (202 + worker + outbox) is the scale-up path if provider latency grows or we need to absorb bursts; the state machine does not change, only who drives it. Not chosen now because it adds a broker and a worker to the coded scope without changing the correctness argument.

## Consequences

- A recovery job is mandatory in production: it picks transfers stuck in `CREATED`, `WALLET_DEBITED`, `PAYMENT_FAILED` or `IN_REVIEW` older than a threshold and drives them forward (with `@Version` optimistic locking and ShedLock so only one instance runs it).
- The provider should receive the transfer id as an external reference, so reconciliation can look the payment up. To confirm with the provider contract.
- The coded version implements steps 1–4 inline; the recovery job and callbacks are designed only.
