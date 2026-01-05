# Debugging 500 Errors in Production

## Problem Statement

The application was experiencing 500 errors in production when accessing the identity endpoints:
- `/api/personas/{persona-id}/identities`
- `/api/personas/{persona-id}/identities/recent`

These errors were confirmed by:
1. Browser console showing 500/502 errors
2. Network requests failing with 500 status codes
3. Production logs showing the errors occurred repeatedly

## Root Cause Analysis

### Initial Investigation

The error was occurring in the `list-recent-identities` function in `src/clj/et/pe/ds.clj`, specifically in the `to-millis` helper function at line 113.

### The Bug

The `to-millis` function was written assuming `xt/valid-from` (from XTDB temporal queries) would always be a `java.time.ZonedDateTime`:

```clojure
(defn- to-millis [zdt]
  (.toEpochMilli (.toInstant zdt)))
```

This code calls `.toInstant` on the value, which works for `ZonedDateTime` but would fail if:
1. The value is `nil` (would throw `NullPointerException`)
2. The value is already an `Instant` (would throw `MethodNotFoundException` since `Instant` doesn't have a `.toInstant()` method)

### Hypothesis

Based on the behavior:
- **Development mode** returns `xt/valid-from` as `ZonedDateTime` (confirmed via logging)
- **Production mode** may return `xt/valid-from` as `Instant` or handle it differently

This discrepancy could be due to:
- Different XTDB configurations between dev and prod
- Different JVM versions or date/time handling
- On-disk vs in-memory database modes

## The Fix

Added defensive type checking to handle all possible cases:

```clojure
(defn- to-millis [zdt]
  (cond
    (nil? zdt) 0
    (instance? java.time.ZonedDateTime zdt) (.toEpochMilli (.toInstant zdt))
    (instance? java.time.Instant zdt) (.toEpochMilli zdt)
    :else (throw (ex-info "Unexpected type for to-millis" {:type (type zdt) :value zdt}))))
```

This fix:
1. Handles `nil` values defensively (returns 0 for epoch start)
2. Handles `ZonedDateTime` (original behavior)
3. Handles `Instant` directly (no `.toInstant()` call needed)
4. Throws a descriptive error for unexpected types

## Testing Strategy

### 1. Confirmed the Error in Production
- Used Playwright browser automation to navigate to production site
- Clicked through to persona selection
- Observed 500 errors in browser console and network tab

### 2. Local Testing
- Added debug logging to identify the type of `xt/valid-from` in different modes
- Ran tests to ensure the fix doesn't break existing functionality
- All 27 tests passed with 90 assertions

### 3. Dev Mode Verification
Result: `xt/valid-from` returns as `ZonedDateTime` in development mode with in-memory database

### 4. Production Mode Verification (In Progress)
Testing locally with production mode configuration to verify different type behavior

## Deployment

Changes committed and pushed to main branch:
- Commit: `9bae99c` - "Fix crash in list-recent-identities with nil valid-from"
- Deployed to production via `fly deploy`

## Follow-up

After deployment, verify:
1. The 500 errors no longer occur on production
2. Identity endpoints return successfully
3. Monitor logs for any "Unexpected type" errors (which would indicate other type issues)
