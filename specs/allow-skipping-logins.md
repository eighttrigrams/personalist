# Allow Skipping Logins

## Overview

The `:dangerously-skip-logins?` configuration option allows developers to login by simply clicking on a username without entering a password. This is intended exclusively for local development convenience.

## Configuration

In `config.edn`:

```clojure
{:db {:type :xtdb2-in-memory}
 :dangerously-skip-logins? true}
```

## Safety Guarantees

This feature has multiple safety checks to prevent accidental use in production:

1. **Runtime check via `allow-skip-logins?`**: The function returns `true` only when both conditions are met:
   - `:dangerously-skip-logins?` is `true` in config
   - `prod-mode?` returns `false`

2. **Startup validation in server**: At application startup (`-main`), if `:dangerously-skip-logins?` is `true` but the application is running in production mode, it throws an exception and exits immediately.

3. **Shell script validation**: The `scripts/start.sh` script checks for this configuration when starting in production mode (`./scripts/start.sh prod`) and refuses to start if the flag is enabled.

## Production Mode Detection

Production mode is determined by `prod-mode?` which returns `true` when:
- Running on Fly.io (detected via `FLY_APP_NAME` environment variable)
- `DEV` environment variable is not set to `"true"`
- `ADMIN_PASSWORD` environment variable is set

The `scripts/start.sh` script sets `DEV=true` when starting in dev mode (without `prod` argument), ensuring the application runs in development mode locally.

## Intended Use

- Enable during local development to quickly switch between personas
- Always disable before deploying or running in production mode
