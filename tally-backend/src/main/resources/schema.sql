CREATE TABLE IF NOT EXISTS auth_users (
    username VARCHAR(128) PRIMARY KEY,
    display_name VARCHAR(256),
    password_hash VARCHAR(255) NOT NULL,
    roles VARCHAR(2000),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    first_seen_at TIMESTAMP,
    last_seen_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tally_sync_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    entity_type VARCHAR(100) NOT NULL,
    entity_id BIGINT NOT NULL,
    tally_type VARCHAR(100),
    request_xml LONGTEXT,
    response_xml LONGTEXT,
    status VARCHAR(40) NOT NULL,
    error_message TEXT,
    tally_guid VARCHAR(255),
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_tally_sync_logs_entity ON tally_sync_logs(entity_type, entity_id);
CREATE INDEX idx_tally_sync_logs_status ON tally_sync_logs(status);

CREATE TABLE IF NOT EXISTS tally_ledger_mappings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    business_unit_id BIGINT NOT NULL,
    erp_ledger_type VARCHAR(100) NOT NULL,
    erp_ledger_name VARCHAR(255),
    tally_ledger_name VARCHAR(255) NOT NULL,
    tally_group_name VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_tally_ledger_mappings_bu_type ON tally_ledger_mappings(business_unit_id, erp_ledger_type, is_active);

CREATE TABLE IF NOT EXISTS payment_reminders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    party_name VARCHAR(255) NOT NULL,
    reminder_type VARCHAR(50) NOT NULL,
    contact_name VARCHAR(255),
    email VARCHAR(255),
    mobile VARCHAR(50),
    due_date DATE NOT NULL,
    amount DECIMAL(18, 2) NOT NULL,
    currency_code VARCHAR(20) NOT NULL,
    status VARCHAR(40) NOT NULL,
    notes TEXT,
    created_by VARCHAR(128),
    updated_by VARCHAR(128),
    last_sent_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_payment_reminders_due_date ON payment_reminders(due_date);
CREATE INDEX idx_payment_reminders_status ON payment_reminders(status);

CREATE TABLE IF NOT EXISTS tally_sync_runs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    status VARCHAR(40) NOT NULL,
    connector_id VARCHAR(255),
    company VARCHAR(255),
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    error_message TEXT
);

CREATE INDEX idx_tally_sync_runs_started_at ON tally_sync_runs(started_at);

CREATE TABLE IF NOT EXISTS tally_voucher_write_queue (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    connector_path VARCHAR(255) NOT NULL,
    http_method VARCHAR(20) NOT NULL,
    action_name VARCHAR(50),
    query_string TEXT,
    request_body LONGTEXT,
    content_type VARCHAR(255),
    connector_id VARCHAR(255),
    connector_base_url VARCHAR(500),
    agent_key VARCHAR(255),
    company VARCHAR(255),
    requested_by VARCHAR(255),
    status VARCHAR(40) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 10,
    last_error TEXT,
    response_body LONGTEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_attempt_at TIMESTAMP,
    next_attempt_at TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_tally_voucher_write_queue_status ON tally_voucher_write_queue(status, next_attempt_at);
CREATE INDEX idx_tally_voucher_write_queue_created_at ON tally_voucher_write_queue(created_at);
ALTER TABLE tally_voucher_write_queue ADD COLUMN IF NOT EXISTS entity_type VARCHAR(100);
ALTER TABLE tally_voucher_write_queue ADD COLUMN IF NOT EXISTS entity_name VARCHAR(255);
ALTER TABLE tally_voucher_write_queue ADD COLUMN IF NOT EXISTS sync_direction VARCHAR(40) DEFAULT 'DB_TO_TALLY';
ALTER TABLE tally_voucher_write_queue ADD COLUMN IF NOT EXISTS conflict_state VARCHAR(40) DEFAULT 'NONE';
ALTER TABLE tally_voucher_write_queue ADD COLUMN IF NOT EXISTS conflict_payload LONGTEXT;
ALTER TABLE tally_voucher_write_queue ADD COLUMN IF NOT EXISTS review_state VARCHAR(40) DEFAULT 'PENDING_REVIEW';
ALTER TABLE tally_voucher_write_queue ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(255);
ALTER TABLE tally_voucher_write_queue ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_tally_voucher_write_queue_entity ON tally_voucher_write_queue(company, entity_type, status);

CREATE TABLE IF NOT EXISTS tally_dataset_snapshots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT,
    dataset_key VARCHAR(100) NOT NULL,
    snapshot_key VARCHAR(255) NOT NULL,
    status VARCHAR(40) NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT FALSE,
    connector_id VARCHAR(255),
    company VARCHAR(255),
    request_params_json TEXT,
    content_hash VARCHAR(64),
    range_start VARCHAR(20),
    range_end VARCHAR(20),
    row_count INT NOT NULL DEFAULT 0,
    stale_after_ms BIGINT NOT NULL DEFAULT 300000,
    error_message TEXT,
    fetched_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE INDEX idx_tally_dataset_snapshots_lookup ON tally_dataset_snapshots(dataset_key, snapshot_key, is_current);
CREATE INDEX idx_tally_dataset_snapshots_fetched_at ON tally_dataset_snapshots(fetched_at);
ALTER TABLE tally_dataset_snapshots ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

CREATE TABLE IF NOT EXISTS tally_companies_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    name VARCHAR(255),
    reserved_name VARCHAR(255),
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_companies_snapshot_rows_snapshot ON tally_companies_snapshot_rows(snapshot_id, row_index);

CREATE TABLE IF NOT EXISTS tally_groups_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    name VARCHAR(255),
    parent VARCHAR(255),
    reserved_name VARCHAR(255),
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_groups_snapshot_rows_snapshot ON tally_groups_snapshot_rows(snapshot_id, row_index);

CREATE TABLE IF NOT EXISTS tally_ledgers_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    name VARCHAR(255),
    parent VARCHAR(255),
    reserved_name VARCHAR(255),
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_ledgers_snapshot_rows_snapshot ON tally_ledgers_snapshot_rows(snapshot_id, row_index);

CREATE TABLE IF NOT EXISTS tally_uoms_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    name VARCHAR(255),
    original_name VARCHAR(255),
    reserved_name VARCHAR(255),
    decimal_places VARCHAR(50),
    is_simple_unit VARCHAR(50),
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_uoms_snapshot_rows_snapshot ON tally_uoms_snapshot_rows(snapshot_id, row_index);

CREATE TABLE IF NOT EXISTS tally_currencies_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    name VARCHAR(255),
    original_name VARCHAR(255),
    symbol VARCHAR(255),
    decimal_symbol VARCHAR(255),
    decimal_places VARCHAR(50),
    warning_note TEXT,
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_currencies_snapshot_rows_snapshot ON tally_currencies_snapshot_rows(snapshot_id, row_index);

CREATE TABLE IF NOT EXISTS tally_stock_groups_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    name VARCHAR(255),
    parent VARCHAR(255),
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_stock_groups_snapshot_rows_snapshot ON tally_stock_groups_snapshot_rows(snapshot_id, row_index);

CREATE TABLE IF NOT EXISTS tally_stock_items_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    name VARCHAR(255),
    parent VARCHAR(255),
    base_units VARCHAR(255),
    gst_applicable VARCHAR(255),
    quantity DECIMAL(18, 3),
    rate DECIMAL(18, 3),
    item_value DECIMAL(18, 3),
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_stock_items_snapshot_rows_snapshot ON tally_stock_items_snapshot_rows(snapshot_id, row_index);

CREATE TABLE IF NOT EXISTS tally_company_currency_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    name VARCHAR(255),
    books_from VARCHAR(20),
    mailing_name VARCHAR(255),
    currency_name VARCHAR(255),
    decimal_symbol VARCHAR(255),
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_company_currency_snapshot_rows_snapshot ON tally_company_currency_snapshot_rows(snapshot_id, row_index);

CREATE TABLE IF NOT EXISTS tally_company_features_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    name VARCHAR(255),
    books_from VARCHAR(20),
    email VARCHAR(255),
    pincode VARCHAR(50),
    country_name VARCHAR(255),
    state_name VARCHAR(255),
    is_inventory_on VARCHAR(50),
    is_gst_on VARCHAR(50),
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_company_features_snapshot_rows_snapshot ON tally_company_features_snapshot_rows(snapshot_id, row_index);

CREATE TABLE IF NOT EXISTS tally_day_book_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    voucher_date VARCHAR(20),
    voucher_type VARCHAR(255),
    voucher_number VARCHAR(255),
    party_ledger VARCHAR(255),
    amount DECIMAL(18, 2),
    narration TEXT,
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_day_book_snapshot_rows_snapshot ON tally_day_book_snapshot_rows(snapshot_id, row_index);

CREATE TABLE IF NOT EXISTS tally_ledger_vouchers_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    voucher_date VARCHAR(20),
    ledger_name VARCHAR(255),
    voucher_type VARCHAR(255),
    debit_amount DECIMAL(18, 2),
    credit_amount DECIMAL(18, 2),
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_ledger_vouchers_snapshot_rows_snapshot ON tally_ledger_vouchers_snapshot_rows(snapshot_id, row_index);
CREATE INDEX idx_tally_ledger_vouchers_snapshot_rows_lookup ON tally_ledger_vouchers_snapshot_rows(snapshot_id, ledger_name, voucher_date);

CREATE TABLE IF NOT EXISTS tally_balance_sheet_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    name VARCHAR(255),
    amount DECIMAL(18, 2),
    kind VARCHAR(50),
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_balance_sheet_snapshot_rows_snapshot ON tally_balance_sheet_snapshot_rows(snapshot_id, row_index);

CREATE TABLE IF NOT EXISTS tally_profit_loss_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    name VARCHAR(255),
    amount DECIMAL(18, 2),
    kind VARCHAR(50),
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_profit_loss_snapshot_rows_snapshot ON tally_profit_loss_snapshot_rows(snapshot_id, row_index);

CREATE TABLE IF NOT EXISTS tally_generic_snapshot_rows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    row_index INT NOT NULL,
    payload_json LONGTEXT
);
CREATE INDEX idx_tally_generic_snapshot_rows_snapshot ON tally_generic_snapshot_rows(snapshot_id, row_index);
