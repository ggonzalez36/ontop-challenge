-- national_id and account_number hold AES-GCM ciphertext (base64), see PiiCipher.
CREATE TABLE recipient (
    id             UUID PRIMARY KEY,
    user_id        BIGINT                   NOT NULL,
    first_name     VARCHAR(100)             NOT NULL,
    last_name      VARCHAR(100)             NOT NULL,
    national_id    VARCHAR(255)             NOT NULL,
    routing_number VARCHAR(20)              NOT NULL,
    account_number VARCHAR(255)             NOT NULL,
    bank_name      VARCHAR(100)             NOT NULL,
    currency       VARCHAR(3)               NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_recipient_user ON recipient (user_id);
