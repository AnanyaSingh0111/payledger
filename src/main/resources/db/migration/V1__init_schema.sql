-- PayLedger schema. Hibernate only validates it (ddl-auto=validate); Flyway owns every change.

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB;

CREATE TABLE accounts (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    version    BIGINT        NOT NULL,
    user_id    BIGINT        NOT NULL,
    name       VARCHAR(100)  NOT NULL,
    type       VARCHAR(20)   NOT NULL,
    currency   CHAR(3)       NOT NULL,
    balance    DECIMAL(19,2) NOT NULL,
    status     VARCHAR(20)   NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_accounts_user_name UNIQUE (user_id, name),
    -- last line of defence against overdrafts: even a buggy code path cannot commit a negative balance
    CONSTRAINT ck_accounts_balance_non_negative CHECK (balance >= 0)
) ENGINE = InnoDB;

CREATE TABLE transactions (
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    user_id                  BIGINT        NOT NULL,
    idempotency_key          VARCHAR(64)   NOT NULL,
    type                     VARCHAR(20)   NOT NULL,
    status                   VARCHAR(20)   NOT NULL,
    source_account_id        BIGINT        NULL,
    destination_account_id   BIGINT        NULL,
    amount                   DECIMAL(19,2) NOT NULL,
    currency                 CHAR(3)       NOT NULL,
    category                 VARCHAR(100)  NULL,
    description              VARCHAR(255)  NULL,
    refund_of_transaction_id BIGINT        NULL,
    created_at               DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_tx_user        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_tx_source      FOREIGN KEY (source_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_tx_destination FOREIGN KEY (destination_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_tx_refund_of   FOREIGN KEY (refund_of_transaction_id) REFERENCES transactions (id),
    -- a retried request can never insert a second row: the database picks one winner
    CONSTRAINT uk_tx_user_idem UNIQUE (user_id, idempotency_key),
    -- a transfer can be refunded at most once, even under concurrent refund requests
    CONSTRAINT uk_tx_refund_of UNIQUE (refund_of_transaction_id),
    CONSTRAINT ck_tx_amount_positive CHECK (amount > 0)
) ENGINE = InnoDB;

-- used by history queries and by the fraud rules (daily limit, rapid transfers)
CREATE INDEX idx_tx_source_created ON transactions (source_account_id, created_at);
CREATE INDEX idx_tx_destination_created ON transactions (destination_account_id, created_at);

CREATE TABLE ledger_entries (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    transaction_id BIGINT        NOT NULL,
    account_id     BIGINT        NULL, -- NULL = the external world (money entering or leaving the system)
    direction      VARCHAR(10)   NOT NULL,
    amount         DECIMAL(19,2) NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_le_tx      FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_le_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_le_amount_positive CHECK (amount > 0)
) ENGINE = InnoDB;

CREATE INDEX idx_le_account ON ledger_entries (account_id, id);
CREATE INDEX idx_le_tx ON ledger_entries (transaction_id);

CREATE TABLE outbox_events (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    attempts        INT          NOT NULL,
    last_error      VARCHAR(500) NULL,
    created_at      DATETIME(6)  NOT NULL,
    next_attempt_at DATETIME(6)  NOT NULL,
    published_at    DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE INDEX idx_outbox_poll ON outbox_events (status, next_attempt_at, id);

CREATE TABLE audit_logs (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT        NULL, -- NULL = the system (scheduled jobs)
    action        VARCHAR(50)   NOT NULL,
    entity_type   VARCHAR(50)   NOT NULL,
    entity_id     VARCHAR(64)   NULL,
    details       VARCHAR(1000) NULL,
    created_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE INDEX idx_audit_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_actor ON audit_logs (actor_user_id);

CREATE TABLE fraud_flags (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    transaction_id BIGINT        NOT NULL,
    account_id     BIGINT        NULL,
    rule_name      VARCHAR(50)   NOT NULL,
    reason         VARCHAR(255)  NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    reviewed_by    BIGINT        NULL,
    reviewed_at    DATETIME(6)   NULL,
    review_note    VARCHAR(255)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ff_tx FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_ff_account FOREIGN KEY (account_id) REFERENCES accounts (id)
) ENGINE = InnoDB;

CREATE INDEX idx_ff_status ON fraud_flags (status, id);

-- Append-only, enforced by the database itself, not only by the application.
CREATE TRIGGER trg_ledger_entries_no_update BEFORE UPDATE ON ledger_entries
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ledger_entries is append-only';

CREATE TRIGGER trg_ledger_entries_no_delete BEFORE DELETE ON ledger_entries
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ledger_entries is append-only';

CREATE TRIGGER trg_audit_logs_no_update BEFORE UPDATE ON audit_logs
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only';

CREATE TRIGGER trg_audit_logs_no_delete BEFORE DELETE ON audit_logs
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only';
