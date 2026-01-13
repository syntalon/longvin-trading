-- Migration to add ex_destination column to orders table
-- This column stores the route (ExDestination) used for the order
-- PostgreSQL compatible

-- Add ex_destination column to orders table
ALTER TABLE orders ADD COLUMN IF NOT EXISTS ex_destination VARCHAR(50);

-- Add comment to document the column
COMMENT ON COLUMN orders.ex_destination IS 'Route (ExDestination) used for this order. FIX tag 100 (or tag 30 in FIX 4.2). Specifies where the order should be routed (e.g., SMAT, OPAL, NYSE, NASDAQ, LOCATE_A).';

