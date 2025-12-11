-- Initialize schema for local Docker development
-- This script runs automatically when PostgreSQL container starts for the first time

-- Create the schema
CREATE SCHEMA IF NOT EXISTS svc_longvin;

-- Grant usage on the schema
GRANT USAGE ON SCHEMA svc_longvin TO svc_longvin_user;

-- Grant create privilege (needed for Flyway migrations)
GRANT CREATE ON SCHEMA svc_longvin TO svc_longvin_user;

-- Set default privileges for future objects
ALTER DEFAULT PRIVILEGES IN SCHEMA svc_longvin 
    GRANT ALL PRIVILEGES ON TABLES TO svc_longvin_user;

ALTER DEFAULT PRIVILEGES IN SCHEMA svc_longvin 
    GRANT ALL PRIVILEGES ON SEQUENCES TO svc_longvin_user;

ALTER DEFAULT PRIVILEGES IN SCHEMA svc_longvin 
    GRANT EXECUTE ON FUNCTIONS TO svc_longvin_user;

