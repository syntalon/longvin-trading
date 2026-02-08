-- Migration to add is_locate_route column to routes table
-- This column explicitly marks routes that are exclusively for locate orders.
-- Previously, we relied on routeType being set, but routes can have routeType set
-- and still be used for other order types. This new column makes the intent explicit.

-- Add is_locate_route column to routes table
ALTER TABLE routes 
    ADD COLUMN IF NOT EXISTS is_locate_route BOOLEAN NOT NULL DEFAULT FALSE;

-- Create index for efficient lookups
CREATE INDEX IF NOT EXISTS idx_route_is_locate_route 
    ON routes(is_locate_route);

-- Update existing routes: if route_type is set, mark as locate route
-- This migrates existing data where routeType was used as the indicator
UPDATE routes 
SET is_locate_route = TRUE 
WHERE route_type IS NOT NULL;

-- Add comment to document the column
COMMENT ON COLUMN routes.is_locate_route IS 
    'Whether this route is exclusively for locate orders. If true, this route should only be used for locate orders (BUY orders to borrow stock for short selling). If false, this route can be used for any order type.';

