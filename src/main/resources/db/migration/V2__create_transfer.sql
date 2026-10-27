CREATE TABLE transfer (
    id                       UUID PRIMARY KEY,
    user_id                  BIGINT                   NOT NULL,
    recipient_id             UUID                     NOT NULL REFERENCES recipient (id),
    recipient_name           VARCHAR(201)             NOT NULL,
    recipient_routing_number VARCHAR(20)              NOT NULL,
    -- AES-GCM ciphertext (base64)
    recipient_account_number VARCHAR(255)             NOT NULL,
    recipient_bank_name      VARCHAR(100)             NOT NULL,
    idempotency_key          VARCHAR(64)              NOT NULL,
    request_fingerprint      VARCHAR(64)              NOT NULL,
    amount                   NUMERIC(19, 2)           NOT NULL,
    fee                      NUMERIC(19, 2)           NOT NULL,
    net_amount               NUMERIC(19, 2)           NOT NULL,
    currency                 VARCHAR(3)               NOT NULL,
    status                   VARCHAR(20)              NOT NULL,
    review_reason            VARCHAR(30),
    -- equals user_id while status is CREATED or WALLET_DEBITED, NULL otherwise (see ADR 004)
    in_flight_user_id        BIGINT,
    wallet_debit_id          VARCHAR(64),
    wallet_refund_id         VARCHAR(64),
    provider_payment_id      VARCHAR(64),
    failure_reason           VARCHAR(500),
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    version                  BIGINT                   NOT NULL,
    CONSTRAINT uq_transfer_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT uq_transfer_in_flight UNIQUE (in_flight_user_id),
    CONSTRAINT ck_transfer_amounts CHECK (amount > 0 AND fee >= 0 AND net_amount > 0 AND amount = fee + net_amount)
);

CREATE INDEX idx_transfer_user_created ON transfer (user_id, created_at DESC);
CREATE INDEX idx_transfer_status_updated ON transfer (status, updated_at);
