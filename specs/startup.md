# Startup Modes and Environments

## Overview

The application can be started in two modes: normal mode and production mode.

## Modes

### Normal Mode (`make start`)

Starts the application using the Clojure backend directly.

- Reads `config.edn` for configuration
- If `:shadow? true` is set: starts shadow-cljs in watch mode (hot reload for ClojureScript)
- If `:shadow?` is not set or false: builds optimized JS with `shadow-cljs release`
- Starts the Clojure backend with nREPL on port 7888

### Production Mode (`make start-prod`)

Builds an uberjar and runs it directly with Java.

- Builds the application using `clj -T:build uber`
- Runs the standalone jar with JVM flags for Netty/XTDB compatibility
- Intended for local testing of the production build before deployment

## Configuration (`config.edn`)

On first startup, if `config.edn` doesn't exist, it is auto-created with defaults:

```edn
{:db {:type :xtdb2-in-memory}
 :pre-seed? true}
```

This means: use an in-memory database and seed it with demo data on startup.

### Configuration Options

| Key | Description |
|-----|-------------|
| `:db` | Database configuration (`:type` and optionally `:path`) |
| `:pre-seed?` | If `true`, seed database with demo data on startup |
| `:shadow?` | If `true`, use shadow-cljs watch mode for hot reload |

### Example Configurations

For development with hot reload:
```edn
{:db {:type :xtdb2-in-memory}
 :pre-seed? true
 :shadow? true}
```

For demos (optimized JS, no hot reload):
```edn
{:db {:type :xtdb2-in-memory}
 :pre-seed? true}
```

For persistent storage:
```edn
{:db {:type :xtdb2-on-disk
      :path "data/xtdb"}
 :pre-seed? false}
```

## Production Environment Detection

The application detects it is running in production by checking for the `ADMIN_PASSWORD` environment variable.

When `ADMIN_PASSWORD` is set:
- Password authentication is required for all users
- The admin user authenticates with the value of `ADMIN_PASSWORD`
- Regular users authenticate with passwords stored in the database
- JWT tokens are used for session management

When `ADMIN_PASSWORD` is not set:
- No password is required
- Users can login by simply clicking on their persona in the login modal
- This makes it easy to test and demo the application

## Admin User

The admin user is special:
- Name: `admin`
- Email: `admin@localhost`
- Password: value of `ADMIN_PASSWORD` env var (in production)
- Has access to the Settings tab to manage personas

## Seeding

When `pre-seed?` is `true` in `config.edn`:
- The seed script (`scripts/seed-db.sh`) runs automatically after startup
- Creates demo personas (alice, bob) with sample identities and relations
- Useful for demos and development

When `pre-seed?` is `false`:
- Database starts empty (or with existing data if using persistent storage)
- Used in production where real data should persist

## Quick Reference

| Command | Description |
|---------|-------------|
| `make start` | Start app (uses config.edn for shadow? setting) |
| `make start-prod` | Build and run uberjar locally |
| `make stop` | Stop the application |
| `make restart` | Restart the application |
| `make restart-prod` | Restart in production mode |
