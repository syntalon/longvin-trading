-- Migration to add primary_order_cl_ord_id column and remove order_groups table
-- This migration:
-- 1. Adds primary_order_cl_ord_id column to orders table for linking shadow orders to primary orders
-- 2. Removes order_groups table and order_group_id columns from orders and locate_requests
-- Order grouping is now handled via primary_order_cl_ord_id column in orders table

-- Step 1: Add primary_order_cl_ord_id column to orders table
ALTER TABLE orders 
    ADD COLUMN IF NOT EXISTS primary_order_cl_ord_id VARCHAR(100);

-- Step 2: Create index for efficient lookups
CREATE INDEX IF NOT EXISTS idx_order_primary_order_cl_ord_id 
    ON orders(primary_order_cl_ord_id);

-- Step 3: Drop foreign key constraints that reference order_groups
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    -- Drop foreign key from orders.order_group_id
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'orders'::regclass
      AND confrelid = 'order_groups'::regclass
      AND contype = 'f';
    
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT IF EXISTS %I', constraint_name);
        RAISE NOTICE 'Dropped foreign key constraint: %', constraint_name;
    END IF;
    
    -- Drop foreign key from locate_requests.order_group_id
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'locate_requests'::regclass
      AND confrelid = 'order_groups'::regclass
      AND contype = 'f';
    
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE locate_requests DROP CONSTRAINT IF EXISTS %I', constraint_name);
        RAISE NOTICE 'Dropped foreign key constraint: %', constraint_name;
    END IF;
END $$;

-- Step 4: Drop indexes on order_group_id columns
DROP INDEX IF EXISTS idx_order_order_group_id;
DROP INDEX IF EXISTS idx_locate_request_order_group_id;

-- Step 5: Remove order_group_id columns
ALTER TABLE orders DROP COLUMN IF EXISTS order_group_id;
ALTER TABLE locate_requests DROP COLUMN IF EXISTS order_group_id;

-- Step 6: Drop order_groups table
DROP TABLE IF EXISTS order_groups CASCADE;

-- Step 7: Add comments to document the changes
COMMENT ON COLUMN orders.primary_order_cl_ord_id IS 
    'ClOrdID of the primary order (for shadow orders). This replaces the order_groups table for linking shadow orders to primary orders. NULL for primary orders.';

