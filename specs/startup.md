# Startup Modes and Environments

## Overview

The application can be started in three different modes, each suited for different use cases.

## Modes

### Production Mode (`make start`)

Builds an uberjar and runs it directly with Java. This is the default mode.

- Builds the application using `clj -T:build uber`
- Runs the standalone jar with JVM flags for Netty/XTDB compatibility
- Intended for local testing of the production build before deployment

### Development Mode (`make start-dev`)

Full development environment with hot code reloading.

- Starts shadow-cljs in watch mode for ClojureScript hot reload
- Starts the Clojure backend with nREPL on port 7888
- Uses whatever database is configured in `config.edn`
- Best for active development

### Demo Mode (`make start-demo`)

Builds optimized JS but runs the Clojure backend directly (not as a jar).

- Runs `shadow-cljs release` to build optimized JS (no dev tooling)
- Starts the Clojure backend with nREPL
- Useful for demoing the app locally without the overhead of dev tooling

## Configuration (`config.edn`)

On first startup, if `config.edn` doesn't exist, it is auto-created with defaults:

```edn
{:db {:type :xtdb2-in-memory}
 :pre-seed? true}
```

This means: use an in-memory database and seed it with demo data on startup.

For persistent storage, use:

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

When `ADMIN_PASSWORD` is not set (dev/demo):
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

| Mode | Command | Hot Reload | Auth Required | Use Case |
|------|---------|------------|---------------|----------|
| Production | `make start` | No | Yes (if ADMIN_PASSWORD set) | Test prod build locally |
| Development | `make start-dev` | Yes | No | Active development |
| Demo | `make start-demo` | No | No | Quick demos |
