-- Migration to fix circular reference and add unique constraint
-- 1. Fixes circular reference between orders and order_groups by adding ON DELETE CASCADE
-- 2. Adds unique constraint on order_groups.primary_order_id for one-to-one mapping
-- PostgreSQL compatible
--
-- Note: If there are duplicate primary_order_id values in existing data,
-- this migration will fail. Please clean up duplicates manually before running this migration.

-- ============================================================================
-- Step 1: Fix circular reference between orders and order_groups
-- ============================================================================

-- Drop existing foreign key constraints and re-add with ON DELETE CASCADE
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    -- Find and drop orders.order_group_id foreign key
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'orders'::regclass
    AND confrelid = 'order_groups'::regclass
    AND contype = 'f'
    LIMIT 1;
    
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', constraint_name);
        RAISE NOTICE 'Dropped constraint: %', constraint_name;
    END IF;
    
    -- Find and drop order_groups.primary_order_id foreign key
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'order_groups'::regclass
    AND confrelid = 'orders'::regclass
    AND contype = 'f'
    AND pg_get_constraintdef(oid) LIKE '%primary_order_id%'
    LIMIT 1;
    
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE order_groups DROP CONSTRAINT %I', constraint_name);
        RAISE NOTICE 'Dropped constraint: %', constraint_name;
    END IF;
END $$;

-- Re-add orders.order_group_id foreign key with ON DELETE CASCADE
-- When order group is deleted, delete all orders in it
ALTER TABLE orders 
    ADD CONSTRAINT orders_order_group_id_fkey 
    FOREIGN KEY (order_group_id) 
    REFERENCES order_groups(id) 
    ON DELETE CASCADE;

-- Re-add order_groups.primary_order_id foreign key with ON DELETE CASCADE
-- When primary order is deleted, delete the order group (which cascades to all shadow orders)
ALTER TABLE order_groups 
    ADD CONSTRAINT fk_order_groups_primary_order 
    FOREIGN KEY (primary_order_id) 
    REFERENCES orders(id) 
    ON DELETE CASCADE;

-- Add comments for foreign key constraints
COMMENT ON CONSTRAINT orders_order_group_id_fkey ON orders IS 
    'Foreign key to order_groups. ON DELETE CASCADE: deleting an order group will delete all orders in it.';

COMMENT ON CONSTRAINT fk_order_groups_primary_order ON order_groups IS 
    'Foreign key to primary order. ON DELETE CASCADE: deleting a primary order will delete its order group (which cascades to all shadow orders).';

-- ============================================================================
-- Step 2: Add unique constraint on primary_order_id for one-to-one mapping
-- ============================================================================

-- Add unique constraint on primary_order_id to ensure one-to-one mapping
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'uq_order_groups_primary_order_id'
    ) THEN
        ALTER TABLE order_groups 
            ADD CONSTRAINT uq_order_groups_primary_order_id 
            UNIQUE (primary_order_id);
    END IF;
END $$;

-- Add comment to document the unique constraint
COMMENT ON CONSTRAINT uq_order_groups_primary_order_id ON order_groups IS 
    'Ensures one-to-one mapping: each primary order has exactly one order group. All shadow orders copied from this primary order belong to this group.';

