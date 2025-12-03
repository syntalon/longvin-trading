# Flyway Database Migrations

## Overview

This project uses Flyway for database schema version control. All migrations are located in `src/main/resources/db/migration/`.

## Migration Files

### V1 - Initial Schema
Creates the complete database schema in a single migration:

**Account Management Tables:**
- `brokers` - Broker entities
- `das_login_ids` - DAS Login ID entities
- `accounts` - Account entities (with FK to brokers)
- `account_das_login_ids` - Many-to-many join table

**Order Management Tables:**
- `order_groups` - Groups of related orders (primary + shadows)
- `orders` - Current order state (with FKs to accounts and order_groups)
- `order_events` - Immutable event history (with FK to orders, ON DELETE CASCADE)

**Locate Request Tables:**
- `locate_requests` - Locate requests for short orders (with FKs to orders and accounts)

**All indexes and foreign key constraints are included in this single migration.**

## Configuration

Flyway is configured in `application.yml`:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0
  jpa:
    hibernate:
      ddl-auto: validate  # Use Flyway for migrations, validate schema matches entities
```

## Running Migrations

### Automatic (Recommended)
Migrations run automatically when the Spring Boot application starts.

### Manual (Maven)
```bash
mvn flyway:migrate -pl backend
```

### Check Migration Status
```bash
mvn flyway:info -pl backend
```

## Adding New Migrations

1. Create a new file in `src/main/resources/db/migration/`
2. Naming convention: `V{version}__{description}.sql`
   - Example: `V11__Add_order_notes_column.sql`
3. Version numbers must be sequential and unique
4. Use descriptive names in the description

## Rollback

Flyway does not support automatic rollbacks. To rollback:

1. Create a new migration that reverses the changes
2. Or manually fix the database and mark the migration as resolved

## Best Practices

1. **Never modify existing migrations** - Always create new migrations for changes
2. **Test migrations** - Test on a copy of production data before applying
3. **Review foreign keys** - Ensure dependencies are correct
4. **Use transactions** - H2 supports DDL in transactions
5. **Indexes** - Add indexes for frequently queried columns

## Database Schema Diagram

```
brokers (1) ──< (N) accounts
das_login_ids (N) ──< (N) accounts (via account_das_login_ids)
order_groups (1) ──< (N) orders
accounts (1) ──< (N) orders
orders (1) ──< (N) order_events
orders (1) ──< (N) locate_requests
accounts (1) ──< (N) locate_requests
```

## Notes

- UUIDs are stored as VARCHAR(36) in H2 (matches UUID string representation)
- DECIMAL(18, 8) is used for all monetary/quantity values
- Timestamps use TIMESTAMP type with DEFAULT CURRENT_TIMESTAMP
- Foreign keys use ON DELETE CASCADE for order_events (cascade delete events when order is deleted)
- Indexes are created for frequently queried columns (FIX IDs, status, timestamps)

