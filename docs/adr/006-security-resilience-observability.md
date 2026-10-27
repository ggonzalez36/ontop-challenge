# ADR 006: JWT, PII encryption, circuit breakers and metrics

**Status:** Accepted

## Context

A withdrawal API moves real money and stores bank data. It must know who the caller is, protect account numbers if the database leaks, survive a slow or failing dependency without making things worse, and let on-call see problems before users report them.

## Decision

**Authentication: OAuth2 resource server with JWT.** The user id is the token subject, never a request field, so a user cannot act on another user's data by changing a parameter. Production validates tokens issued by the identity provider (`JWT_ISSUER_URI`, keys from its JWKS). Locally and in tests, tokens are HS256 with a development secret, so no identity provider is needed. Every query is scoped by user id, and a resource of another user answers 404, never 403, so ids cannot be probed.

**Provider callback: HMAC signature, not JWT.** Providers sign webhooks with a shared secret. `X-Signature: sha256=<hex>` is checked over the raw body in constant time before parsing. In production the endpoint is also only reachable from the provider's IP range.

**PII at rest: AES-256-GCM in a JPA `AttributeConverter`.** The national id and both account numbers (recipient and transfer snapshot) are encrypted with a random IV per value. GCM also detects tampering. The domain never sees ciphertext. Responses expose only the last 4 digits. The key comes from configuration here; in production from KMS/Vault with a key id prefix for rotation (⬜).

**Resilience: timeouts plus a Resilience4j circuit breaker per remote service.** 4xx answers do not count as failures. An open circuit means the call was not sent, which is a definite answer (unlike a timeout), so it is mapped to "rejected" and the saga compensates safely. There are no automatic retries on money-moving calls.

**Observability: Micrometer to Prometheus.** One counter per committed state change, `ontop_transfers_events_total{type}`, published after commit so rolled-back changes are never counted. From it come success, refund and review rates. Reconciliation results are published as gauges (`ontop_reconciliation_unbalanced`, `ontop_transfers_stale{status}`). HTTP client latency and circuit breaker state come built in.

## Alternatives considered

| Option | Why not |
|---|---|
| API keys per user | No expiry or standard claims; the gateway and identity provider already speak OAuth2. |
| Database-level encryption only (TDE) | Protects disks and backups, not a SQL dump or an over-privileged reader; column encryption limits who can read PII to the service. |
| Retries with backoff on the payment POST | Without provider idempotency a retry can pay twice; recovery by lookup is safer. |
| Logs only, no metrics | Rates and stuck counts are what alerts need; logs are for investigating after the alert. |

## Consequences

- Clients need a token; Postman ships a long-lived development token for user 1000.
- Encrypted columns cannot be searched or indexed by value. Search by account number would need a keyed hash (blind index) column.
- Secrets in `application.yml` are development defaults only; deployments must override `JWT_ISSUER_URI`, `PII_ENCRYPTION_KEY` and `PAYMENTS_WEBHOOK_SECRET`.
