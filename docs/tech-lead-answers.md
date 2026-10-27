# Questions for a Tech Lead

## 1. How would you ensure the integrity of financial transactions and wallet balances, considering that the Payments API does not update the wallet balance?

I treat it as a distributed transaction without a coordinator, and design for the failure cases first.

- **One source of truth per step.** The transfer row is a persisted state machine; it is saved before and after every external call. After a crash we always know the last confirmed step.
- **Debit the wallet before paying.** Ontop's bank account backs all wallets, so paying first could spend other users' money. If the payment fails, we refund the full amount, fee included (the refund line the history screen already shows).
- **Never treat a timeout as a failure.** An unknown outcome goes to `IN_REVIEW` and is resolved by looking the payment up at the provider, not by refunding. Refunding a payment that did go through is the most expensive bug in this flow.
- **No double execution.** Mandatory `Idempotency-Key`, a unique constraint per user and key, at most one in-flight debit per user, and optimistic locking between the API and background jobs.
- **Prove it with a ledger.** An append-only double-entry ledger records money in transit, fees earned and refunds. A daily three-way reconciliation compares our ledger, wallet transactions and the provider's statement. Every mismatch becomes a case with an owner, never a silent automatic fix.
- **Make it visible.** Alerts on transfers stuck in non-final states, on reconciliation differences, and on circuit breakers opening. A runbook says what on-call does with each.

Longer term, I would ask the wallet team to reject overdrafts and accept idempotency keys. The service that owns a balance should protect it.

## 2. How would you work with stakeholders during the definition and validation of the 10% fee, balancing revenue goals and user experience?

I treat the fee as a product hypothesis with a business owner, not as a constant in the code.

1. **Agree on the goal and the guardrails before the number.** With Finance and Product: what revenue per transfer do we need, and which metrics must not get worse (conversion on the "send" screen, withdrawal frequency, churn, support tickets about fees). With Legal/Compliance: disclosure rules per country and refund obligations.
2. **Bring data, not opinions.** Cost per transfer from the provider, competitors' pricing, and how our users' transfer amounts are distributed. A flat 10% hurts large withdrawals the most, which are often our best users. Options to show: percentage with a cap, a flat fee, tiers, free first withdrawal per month.
3. **Make the system cheap to change.** `FeePolicy` behind a port, the rate in configuration, the applied rate stored on each transfer, and a quote endpoint so the user always sees fee and net amount before confirming. That is the most important UX decision: no surprises.
4. **Validate with an experiment.** Feature-flagged cohorts (e.g. 10% vs 10% capped at 15 USD), a pre-agreed decision date and success metric, and a dashboard shared with every stakeholder.
5. **Close the loop.** Decide in a short review with the owners, record the decision and its reasons (an ADR-style one-pager), and keep watching support tickets and conversion after rollout.

My role is to make the trade-off visible and the change safe. The final call on pricing belongs to the business.

## 3. How would you ensure your team stays focused and motivated, and understands and owns the main goals of the company?

- **Connect work to outcomes.** Every quarter, translate company goals into two or three team outcomes with metrics the team can move (e.g. "withdrawal success rate ≥ 99.5%", "time to money in account"). The roadmap is discussed against those outcomes, not as a feature list.
- **Give context, not only tickets.** Engineers join discovery with Product, hear user calls, and see support tickets. People own goals they understand.
- **Protect focus.** Limit work in progress, a clear priority order, a rotating on-call/interrupt shield so the rest of the team can focus, and saying no (or "not now") explicitly.
- **Ownership with autonomy.** The team proposes the "how" (ADRs, design reviews) and owns what it ships in production: dashboards, alerts, postmortems without blame.
- **Growth and recognition.** Regular 1:1s, individual growth plans tied to real work (e.g. leading a design), and public recognition of impact, including quiet work like reconciliation and reliability.
- **Healthy feedback loops.** Short retros that end with one or two concrete actions, and checking at the next retro that they were done.

Motivation comes from autonomy, mastery and purpose. My job is to remove obstacles and keep the purpose visible.
