-- Initial database schema for Longvin Trading System
-- PostgreSQL compatible migration
-- This migration creates all tables, indexes, and foreign key constraints

-- ============================================================================
-- Account Management Tables
-- ============================================================================

-- Create brokers table
CREATE TABLE IF NOT EXISTS brokers (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    code VARCHAR(50),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

-- Create das_login_ids table
CREATE TABLE IF NOT EXISTS das_login_ids (
    id BIGSERIAL PRIMARY KEY,
    login_id VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

-- Create accounts table
CREATE TABLE IF NOT EXISTS accounts (
    id BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    broker_id BIGINT NOT NULL,
    account_type VARCHAR(20) NOT NULL DEFAULT 'SHADOW',
    strategy_key VARCHAR(64),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    FOREIGN KEY (broker_id) REFERENCES brokers(id)
);

CREATE INDEX IF NOT EXISTS idx_account_broker_id ON accounts(broker_id);
CREATE INDEX IF NOT EXISTS idx_account_account_type ON accounts(account_type);
CREATE INDEX IF NOT EXISTS idx_account_active ON accounts(active);

-- Create account_das_login_ids join table (many-to-many relationship)
CREATE TABLE IF NOT EXISTS account_das_login_ids (
    account_id BIGINT NOT NULL,
    das_login_id_id BIGINT NOT NULL,
    PRIMARY KEY (account_id, das_login_id_id),
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    FOREIGN KEY (das_login_id_id) REFERENCES das_login_ids(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_account_das_login_ids_account_id ON account_das_login_ids(account_id);
CREATE INDEX IF NOT EXISTS idx_account_das_login_ids_das_login_id_id ON account_das_login_ids(das_login_id_id);

-- ============================================================================
-- Order Management Tables
-- ============================================================================

-- Create order_groups table
-- Note: primary_order_id foreign key will be added after orders table is created
CREATE TABLE IF NOT EXISTS order_groups (
    id UUID NOT NULL PRIMARY KEY,
    strategy_key VARCHAR(100),
    primary_order_id UUID,
    symbol VARCHAR(50),
    total_target_qty DECIMAL(18, 8),
    state VARCHAR(30) NOT NULL DEFAULT 'LOCATE_PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_order_group_strategy_key ON order_groups(strategy_key);
CREATE INDEX IF NOT EXISTS idx_order_group_created_at ON order_groups(created_at);
CREATE INDEX IF NOT EXISTS idx_order_group_primary_order_id ON order_groups(primary_order_id);
CREATE INDEX IF NOT EXISTS idx_order_group_symbol ON order_groups(symbol);
CREATE INDEX IF NOT EXISTS idx_order_group_state ON order_groups(state);
CREATE INDEX IF NOT EXISTS idx_order_group_symbol ON order_groups(symbol);
CREATE INDEX IF NOT EXISTS idx_order_group_state ON order_groups(state);

-- Create orders table
CREATE TABLE IF NOT EXISTS orders (
    id UUID NOT NULL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    order_group_id UUID,
    fix_order_id VARCHAR(100),
    fix_cl_ord_id VARCHAR(100),
    fix_orig_cl_ord_id VARCHAR(100),
    symbol VARCHAR(50) NOT NULL,
    side CHAR(1) NOT NULL,
    ord_type CHAR(1),
    time_in_force CHAR(1),
    order_qty DECIMAL(18, 8),
    price DECIMAL(18, 8),
    stop_px DECIMAL(18, 8),
    exec_type CHAR(1),
    ord_status CHAR(1) NOT NULL DEFAULT '0',
    cum_qty DECIMAL(18, 8),
    leaves_qty DECIMAL(18, 8),
    avg_px DECIMAL(18, 8),
    last_px DECIMAL(18, 8),
    last_qty DECIMAL(18, 8),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES accounts(id),
    FOREIGN KEY (order_group_id) REFERENCES order_groups(id)
);

CREATE INDEX IF NOT EXISTS idx_order_account_id ON orders(account_id);
CREATE INDEX IF NOT EXISTS idx_order_order_group_id ON orders(order_group_id);
CREATE INDEX IF NOT EXISTS idx_order_fix_order_id ON orders(fix_order_id);
CREATE INDEX IF NOT EXISTS idx_order_fix_cl_ord_id ON orders(fix_cl_ord_id);
CREATE INDEX IF NOT EXISTS idx_order_created_at ON orders(created_at);
CREATE INDEX IF NOT EXISTS idx_order_updated_at ON orders(updated_at);

-- Add foreign key constraint for order_groups.primary_order_id
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'fk_order_groups_primary_order'
    ) THEN
        ALTER TABLE order_groups 
            ADD CONSTRAINT fk_order_groups_primary_order 
            FOREIGN KEY (primary_order_id) REFERENCES orders(id);
    END IF;
END $$;

-- Create order_events table (immutable events from ExecutionReports)
CREATE TABLE IF NOT EXISTS order_events (
    id UUID NOT NULL PRIMARY KEY,
    order_id UUID NOT NULL,
    fix_exec_id VARCHAR(100) NOT NULL,
    exec_type CHAR(1) NOT NULL,
    ord_status CHAR(1) NOT NULL,
    fix_order_id VARCHAR(100),
    fix_cl_ord_id VARCHAR(100),
    fix_orig_cl_ord_id VARCHAR(100),
    symbol VARCHAR(50),
    side CHAR(1),
    ord_type CHAR(1),
    time_in_force CHAR(1),
    order_qty DECIMAL(18, 8),
    price DECIMAL(18, 8),
    stop_px DECIMAL(18, 8),
    last_px DECIMAL(18, 8),
    last_qty DECIMAL(18, 8),
    cum_qty DECIMAL(18, 8),
    leaves_qty DECIMAL(18, 8),
    avg_px DECIMAL(18, 8),
    account VARCHAR(100),
    transact_time TIMESTAMP,
    text VARCHAR(500),
    event_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    raw_fix_message TEXT,
    session_id VARCHAR(200),
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_order_event_order_id ON order_events(order_id);
CREATE INDEX IF NOT EXISTS idx_order_event_fix_exec_id ON order_events(fix_exec_id);
CREATE INDEX IF NOT EXISTS idx_order_event_event_time ON order_events(event_time);
CREATE INDEX IF NOT EXISTS idx_order_event_exec_type ON order_events(exec_type);

-- ============================================================================
-- Locate Request Tables
-- ============================================================================

-- Create locate_requests table
-- Note: Uses quote protocol columns (fix_quote_req_id) from the start
CREATE TABLE IF NOT EXISTS locate_requests (
    id UUID NOT NULL PRIMARY KEY,
    order_id UUID NOT NULL,
    order_group_id UUID,
    account_id BIGINT NOT NULL,
    symbol VARCHAR(50) NOT NULL,
    quantity DECIMAL(18, 8) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    response_message VARCHAR(500),
    fix_quote_req_id VARCHAR(100),
    locate_route VARCHAR(100),
    offer_px DECIMAL(18, 8),
    offer_size DECIMAL(18, 8),
    approved_qty DECIMAL(18, 8),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (order_id) REFERENCES orders(id),
    FOREIGN KEY (order_group_id) REFERENCES order_groups(id),
    FOREIGN KEY (account_id) REFERENCES accounts(id)
);

CREATE INDEX IF NOT EXISTS idx_locate_request_order_id ON locate_requests(order_id);
CREATE INDEX IF NOT EXISTS idx_locate_request_order_group_id ON locate_requests(order_group_id);
CREATE INDEX IF NOT EXISTS idx_locate_request_account_id ON locate_requests(account_id);
CREATE INDEX IF NOT EXISTS idx_locate_request_symbol ON locate_requests(symbol);
CREATE INDEX IF NOT EXISTS idx_locate_request_status ON locate_requests(status);
CREATE INDEX IF NOT EXISTS idx_locate_request_created_at ON locate_requests(created_at);
CREATE INDEX IF NOT EXISTS idx_locate_request_quote_req_id ON locate_requests(fix_quote_req_id);
