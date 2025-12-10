-- Migration to update LocateRequest and OrderGroup for quote-style locate protocol
-- Based on DAS Short Locate Quote Request/Response (MsgType R/S)

-- Add new columns to order_groups table
ALTER TABLE order_groups ADD COLUMN symbol VARCHAR(50);
ALTER TABLE order_groups ADD COLUMN total_target_qty DECIMAL(18, 8);
ALTER TABLE order_groups ADD COLUMN state VARCHAR(30) NOT NULL DEFAULT 'LOCATE_PENDING';

CREATE INDEX idx_order_group_symbol ON order_groups(symbol);
CREATE INDEX idx_order_group_state ON order_groups(state);

-- Update locate_requests table: remove old columns, add new ones for quote protocol
-- Note: We'll keep order_id for backward compatibility but add order_group_id

-- Add order_group_id column
ALTER TABLE locate_requests ADD COLUMN order_group_id UUID;
ALTER TABLE locate_requests ADD CONSTRAINT fk_locate_request_order_group 
    FOREIGN KEY (order_group_id) REFERENCES order_groups(id);

CREATE INDEX idx_locate_request_order_group_id ON locate_requests(order_group_id);

-- Rename fix_locate_req_id to fix_quote_req_id (tag 131)
ALTER TABLE locate_requests RENAME COLUMN fix_locate_req_id TO fix_quote_req_id;

-- Remove old columns that don't apply to quote protocol
ALTER TABLE locate_requests DROP COLUMN IF EXISTS available_qty;
ALTER TABLE locate_requests DROP COLUMN IF EXISTS borrowed_qty;
ALTER TABLE locate_requests DROP COLUMN IF EXISTS locate_id;

-- Add new columns for quote response (tag 133, 135)
ALTER TABLE locate_requests ADD COLUMN locate_route VARCHAR(100);
ALTER TABLE locate_requests ADD COLUMN offer_px DECIMAL(18, 8);
ALTER TABLE locate_requests ADD COLUMN offer_size DECIMAL(18, 8);
ALTER TABLE locate_requests ADD COLUMN approved_qty DECIMAL(18, 8);

CREATE INDEX idx_locate_request_quote_req_id ON locate_requests(fix_quote_req_id);

