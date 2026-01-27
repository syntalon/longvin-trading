-- Migration to create routes and copy_rules tables
-- Routes: Route (ExDestination) configuration for all order types
-- Copy Rules: Copy trade rules defining how orders are copied from primary to shadow accounts
-- PostgreSQL compatible

-- ============================================================================
-- Routes Table
-- ============================================================================

-- Create routes table
CREATE TABLE IF NOT EXISTS routes (
    id BIGSERIAL PRIMARY KEY,
    broker_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    route_type VARCHAR(20) CHECK (route_type IN ('TYPE_0', 'TYPE_1')),
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    priority INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (broker_id) REFERENCES brokers(id) ON DELETE CASCADE,
    CONSTRAINT uq_route_broker_name UNIQUE (broker_id, name)
);

-- Create indexes for routes table
CREATE INDEX IF NOT EXISTS idx_route_broker_id ON routes(broker_id);
CREATE INDEX IF NOT EXISTS idx_route_name ON routes(name);
CREATE INDEX IF NOT EXISTS idx_route_active ON routes(active);
CREATE INDEX IF NOT EXISTS idx_route_broker_name ON routes(broker_id, name);

-- Add comments to document routes table
COMMENT ON TABLE routes IS 'Stores route (ExDestination) configuration organized by broker. Routes can be used for all order types. route_type is only relevant for locate routes.';
COMMENT ON COLUMN routes.route_type IS 'Route type for locate orders: TYPE_0 (price inquiry first, then fills) or TYPE_1 (returns offer, requires accept/reject). NULL for non-locate routes.';
COMMENT ON COLUMN routes.priority IS 'Priority for route selection when multiple routes are available (lower number = higher priority)';

-- Note: Default routes are initialized in AccountDataInitializer after brokers are created

-- ============================================================================
-- Copy Rules Table
-- ============================================================================

-- Create copy_rules table
CREATE TABLE IF NOT EXISTS copy_rules (
    id BIGSERIAL PRIMARY KEY,
    primary_account_id BIGINT NOT NULL,
    shadow_account_id BIGINT NOT NULL,
    ratio_type VARCHAR(20) NOT NULL CHECK (ratio_type IN ('PERCENTAGE', 'MULTIPLIER', 'FIXED_QUANTITY')),
    ratio_value DECIMAL(18, 8) NOT NULL DEFAULT 1.0,
    order_types VARCHAR(50), -- Comma-separated list of OrdType values (e.g., "1,2,3")
    copy_route VARCHAR(100), -- Route to use when copying regular orders TO shadow account (e.g., "NYSE", "NASDAQ")
    locate_route VARCHAR(100), -- Route to use when copying locate orders TO shadow account (e.g., "LOCATE", "TESTSL")
    copy_broker VARCHAR(100), -- Broker to use when copying orders TO shadow account (e.g., "OPAL", "DAST"). If NULL, use shadow account's broker.
    min_quantity DECIMAL(18, 8),
    max_quantity DECIMAL(18, 8),
    priority INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500),
    config JSONB, -- JSON configuration map for flexible rule settings
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (primary_account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    FOREIGN KEY (shadow_account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT uq_copy_rule_primary_shadow UNIQUE (primary_account_id, shadow_account_id)
);

-- Create indexes for copy_rules table
CREATE INDEX IF NOT EXISTS idx_copy_rule_primary_account ON copy_rules(primary_account_id);
CREATE INDEX IF NOT EXISTS idx_copy_rule_shadow_account ON copy_rules(shadow_account_id);
CREATE INDEX IF NOT EXISTS idx_copy_rule_active ON copy_rules(active);
CREATE INDEX IF NOT EXISTS idx_copy_rule_primary_shadow ON copy_rules(primary_account_id, shadow_account_id);

-- Add comments to document copy_rules table
COMMENT ON TABLE copy_rules IS 'Defines how orders from primary accounts are copied to shadow accounts. Supports flexible copy ratios, triggers, and filters.';
COMMENT ON COLUMN copy_rules.ratio_type IS 'Copy ratio type: PERCENTAGE (ratio_value is %), MULTIPLIER (ratio_value is multiplier), or FIXED_QUANTITY (ratio_value is exact quantity)';
COMMENT ON COLUMN copy_rules.ratio_value IS 'Copy ratio value. For PERCENTAGE: 0.5=50%, 1.0=100%. For MULTIPLIER: 0.5=half, 1.0=same, 2.0=double. For FIXED_QUANTITY: exact quantity.';
COMMENT ON COLUMN copy_rules.order_types IS 'Comma-separated list of OrdType values to copy (e.g., "1,2" for MARKET and LIMIT). NULL = copy all types. Copy trigger is determined per order type (e.g., stop orders trigger on NEW, regular orders trigger on FILL).';
COMMENT ON COLUMN copy_rules.copy_route IS 'Route (ExDestination) to use when copying regular orders (market, limit, stop, etc.) TO shadow account. If NULL, use the same route as primary account.';
COMMENT ON COLUMN copy_rules.locate_route IS 'Route (ExDestination) to use when copying locate orders TO shadow account. If NULL, falls back to copy_route, then to primary account route.';
COMMENT ON COLUMN copy_rules.copy_broker IS 'Broker to use when copying orders TO shadow account. If NULL, use the shadow account''s broker.';
COMMENT ON COLUMN copy_rules.priority IS 'Priority when multiple rules match (lower number = higher priority)';
COMMENT ON COLUMN copy_rules.config IS 'JSON configuration map for flexible rule settings. Can store any additional configuration as key-value pairs.';

