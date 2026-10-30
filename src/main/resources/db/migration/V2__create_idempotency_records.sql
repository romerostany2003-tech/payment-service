CREATE TABLE idempotency_records (
    id BINARY(16) PRIMARY KEY,

    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    payment_id BINARY(16) NOT NULL,

    created_at TIMESTAMP(6) NOT NULL,

    CONSTRAINT uk_idempotency_records_key
        UNIQUE (idempotency_key)
);