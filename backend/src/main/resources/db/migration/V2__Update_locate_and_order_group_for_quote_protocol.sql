-- Migration to update LocateRequest and OrderGroup for quote-style locate protocol
-- Based on DAS Short Locate Quote Request/Response (MsgType R/S)
-- PostgreSQL compatible

-- Add new columns to order_groups table
ALTER TABLE order_groups ADD COLUMN IF NOT EXISTS symbol VARCHAR(50);
ALTER TABLE order_groups ADD COLUMN IF NOT EXISTS total_target_qty DECIMAL(18, 8);
ALTER TABLE order_groups ADD COLUMN IF NOT EXISTS state VARCHAR(30) NOT NULL DEFAULT 'LOCATE_PENDING';

CREATE INDEX IF NOT EXISTS idx_order_group_symbol ON order_groups(symbol);
CREATE INDEX IF NOT EXISTS idx_order_group_state ON order_groups(state);

-- Update locate_requests table: remove old columns, add new ones for quote protocol
-- Note: We'll keep order_id for backward compatibility but add order_group_id

-- Add order_group_id column
ALTER TABLE locate_requests ADD COLUMN IF NOT EXISTS order_group_id UUID;

-- Add foreign key constraint if it doesn't exist
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'fk_locate_request_order_group'
    ) THEN
        ALTER TABLE locate_requests 
            ADD CONSTRAINT fk_locate_request_order_group 
            FOREIGN KEY (order_group_id) REFERENCES order_groups(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_locate_request_order_group_id ON locate_requests(order_group_id);

-- Rename fix_locate_req_id to fix_quote_req_id (tag 131)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'locate_requests' 
        AND column_name = 'fix_locate_req_id'
    ) THEN
        ALTER TABLE locate_requests RENAME COLUMN fix_locate_req_id TO fix_quote_req_id;
    END IF;
END $$;

-- Remove old columns that don't apply to quote protocol
DO $$
BEGIN
    -- Drop available_qty if exists
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'locate_requests' 
        AND column_name = 'available_qty'
    ) THEN
        ALTER TABLE locate_requests DROP COLUMN available_qty;
    END IF;
    
    -- Drop borrowed_qty if exists
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'locate_requests' 
        AND column_name = 'borrowed_qty'
    ) THEN
        ALTER TABLE locate_requests DROP COLUMN borrowed_qty;
    END IF;
    
    -- Drop locate_id if exists
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'locate_requests' 
        AND column_name = 'locate_id'
    ) THEN
        ALTER TABLE locate_requests DROP COLUMN locate_id;
    END IF;
END $$;

-- Add new columns for quote response (tag 133, 135)
ALTER TABLE locate_requests ADD COLUMN IF NOT EXISTS locate_route VARCHAR(100);
ALTER TABLE locate_requests ADD COLUMN IF NOT EXISTS offer_px DECIMAL(18, 8);
ALTER TABLE locate_requests ADD COLUMN IF NOT EXISTS offer_size DECIMAL(18, 8);
ALTER TABLE locate_requests ADD COLUMN IF NOT EXISTS approved_qty DECIMAL(18, 8);

CREATE INDEX IF NOT EXISTS idx_locate_request_quote_req_id ON locate_requests(fix_quote_req_id);
