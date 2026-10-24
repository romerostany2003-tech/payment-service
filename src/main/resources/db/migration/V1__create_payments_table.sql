CREATE TABLE payments (
    id                     BINARY(16)      NOT NULL,
    source_account_id      BINARY(16)      NOT NULL,
    destination_account_id BINARY(16)      NOT NULL,
    amount                 DECIMAL(19, 4)  NOT NULL,
    currency               VARCHAR(3)      NOT NULL,
    status                 VARCHAR(30)     NOT NULL,
    description            VARCHAR(255)    NULL,
    created_at             DATETIME(6)     NOT NULL,
    updated_at             DATETIME(6)     NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT chk_payments_amount_positive
        CHECK (amount > 0),
    CONSTRAINT chk_payments_different_accounts
        CHECK (source_account_id <> destination_account_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;