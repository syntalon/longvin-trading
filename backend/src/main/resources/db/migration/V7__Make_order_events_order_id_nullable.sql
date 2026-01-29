-- Migration to make order_events.order_id nullable and remove foreign key constraint
-- This allows events to be created independently of orders (event-driven architecture)
-- Events can exist even if the order doesn't exist yet in the database

-- Drop the foreign key constraint
DO $$
DECLARE
    fk_name TEXT;
BEGIN
    -- Find the foreign key constraint name
    SELECT constraint_name INTO fk_name
    FROM information_schema.table_constraints
    WHERE table_name = 'order_events'
      AND constraint_type = 'FOREIGN KEY'
      AND constraint_name LIKE '%order_id%';
    
    -- Drop the constraint if it exists
    IF fk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE order_events DROP CONSTRAINT IF EXISTS %I', fk_name);
        RAISE NOTICE 'Dropped foreign key constraint: %', fk_name;
    ELSE
        RAISE NOTICE 'No foreign key constraint found for order_events.order_id';
    END IF;
END $$;

-- Make order_id nullable
ALTER TABLE order_events 
    ALTER COLUMN order_id DROP NOT NULL;

-- Add comment to document the change
COMMENT ON COLUMN order_events.order_id IS 
    'Optional reference to orders table. NULL if order does not exist yet (event-driven architecture). Events can be created independently and linked to orders later.';

