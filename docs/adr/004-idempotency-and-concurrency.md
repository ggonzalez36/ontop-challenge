# ADR 004 — Idempotency-Key and one in-flight debit per user

**Status:** Accepted

## Context

Two risks move real money twice:

1. **Retries.** Mobile networks drop responses; the app or the user taps "Continue" again. Without protection, each retry debits the wallet and pays again.
2. **Races.** The balance check reads a remote service. Two concurrent withdrawals of 80 on a balance of 100 both see 100 and both pass (time-of-check / time-of-use). The wallet mock does not reject negative balances.

## Decision

**Idempotency**
- `Idempotency-Key` header is **required** on `POST /v1/transfers` (client generates a UUID per transfer intent).
- `UNIQUE (user_id, idempotency_key)` on `transfer`, plus a SHA-256 hash of the normalized request body.
- Same key + same hash → return the stored transfer (200) without calling any port. Same key + different hash → 409.
- The insert of the `CREATED` row is the lock: a concurrent duplicate fails on the unique constraint and is answered with the winner's transfer.

**Concurrency per user**
- Partial unique index `ON transfer (user_id) WHERE status IN ('CREATED','WALLET_DEBITED')`: a second transfer for the same user is rejected with 409 `transfer-in-progress` while the first is between "decided" and "debited". After the debit, the wallet itself reflects the lower balance, so later checks are correct.
- This works across many instances because PostgreSQL enforces it, unlike a JVM lock.
- `@Version` optimistic locking on `transfer` so the request thread and the recovery job never overwrite each other's status.

## Alternatives considered

| Option | Why not |
|---|---|
| `synchronized` / in-memory lock per user | Only works with one instance. |
| `SELECT … FOR UPDATE` on a user row during the whole saga | Holds a DB transaction open during HTTP calls to slow services; connection pool exhaustion under load. |
| Distributed lock (Redis / Redisson) | New infrastructure and lease-expiry edge cases; the DB constraint already gives the guarantee. |
| Rely on the wallet service to reject overdrafts | The right long-term fix (the owner of the balance enforces it), but we cannot assume it from the mock. Listed as a request to the wallet team. |

## Consequences

- The client must send the header; documented in the Postman collection and the API.
- Idempotency keys can expire after 24–48 h (cleanup job), designed only.
- Strictly one in-flight debit per user. Acceptable for a withdrawal flow, where parallel withdrawals by the same user are rare and suspicious.
