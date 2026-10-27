# ADR 003 — Fee deducted from the amount sent, behind a FeePolicy port, with BigDecimal money

**Status:** Accepted

## Context

A 10% fee applies when the transfer executes, and "how to apply it" is our choice. The "How much money do you want to send?" screen shows: *You send 20 USD · Transaction fee 2.00 · Recipient gets 18 USD*. Product and finance will want to change the rule (tiers, promos, caps).

## Decision

- **Fee is deducted from the amount sent**: wallet is debited `amount`, provider pays `netAmount = amount − fee`, Ontop keeps `fee`. This matches the screen and means the user never needs more balance than what they typed.
- `fee = amount × rate`, rounded to 2 decimals with `RoundingMode.HALF_EVEN`; `netAmount = amount − fee` (computed by subtraction, not by multiplying by 0.9, so `amount = fee + netAmount` always holds to the cent).
- `FeePolicy` is a domain interface; `PercentageFeePolicy(rate)` is the first implementation, with `rate` from configuration (`ontop.fees.withdrawal-rate=0.10`). The fee amount is **stored on each transfer**, so history stays correct after the rule changes.
- `Money` value object over `BigDecimal` with currency; `double` is never used for amounts. Validation: amount > 0, scale ≤ 2, `netAmount > 0` (so tiny amounts like 0.01 are rejected), configurable min/max.
- The same `FeePolicy` powers `POST /v1/transfers/quotes`, so the screen and the execution can never disagree.

## Alternatives considered

| Option | Why not |
|---|---|
| Fee on top (debit 22, pay 20) | User needs more balance than typed; contradicts the screen. Kept as a possible `FeePolicy` if product wants "recipient gets exactly X". |
| Fee as a separate wallet transaction | Two debits per transfer, double the failure modes, and the wallet mock gives no grouping. Better recorded in our own ledger (ADR 005). |
| Hardcoded `0.10` in the service | Breaks open/closed; every pricing experiment becomes a deploy. |

## Consequences

- Rounding rule must be agreed with Finance (open question). HALF_EVEN avoids systematic bias across many transfers.
- The fee is recognized as revenue only when the transfer is `COMPLETED`; refunded transfers return it.
