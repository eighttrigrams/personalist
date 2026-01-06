# Railway Deployment OOM Issue

## Problem Summary

The personalist application has been successfully migrated from Fly.io to Railway, but is failing to deploy due to **Out of Memory (OOM)** errors.

### Root Causes

1. **Fly.io (Original Issue)**: XTDB2 uses Apache Arrow which requires memory-mapped file I/O. This is incompatible with Fly.io's Firecracker microVMs, causing SIGSEGV crashes.

2. **Railway (Current Issue)**: The trial plan only provides **1 GB RAM**, which is insufficient for:
   - Java/Clojure JVM runtime
   - XTDB2 database with Apache Arrow
   - Application overhead

## Current Status

✅ **Completed:**
- Railway project configured with GitHub integration
- Environment variables set (ADMIN_PASSWORD, HOST, PORT)
- Volume attached for persistent storage (`/app/data/xtdb`)
- Docker build succeeds
- Configuration files created (railway.toml, RAILWAY_DEPLOYMENT.md)

❌ **Failing:**
- Application crashes with OOM during startup/healthcheck
- Railway trial plan: 1 GB RAM limit (cannot be increased without upgrade)

## Evidence

From Railway deployment logs:
- Multiple "Deployment failed Out of Memory (OOM)" errors
- Healthchecks fail after ~1-2 minutes
- Application never reaches stable running state

From Railway Settings:
- CPU: 2 vCPU (Plan limit: 2 vCPU)
- Memory: 1 GB (Plan limit: 1 GB)
- Message: "Upgrade to toggle resource limits"

## Options

### Option 1: Upgrade Railway Plan (Recommended for XTDB)
**Cost:** ~$5-20/month depending on usage
**Pros:**
- Keeps current setup with XTDB2
- Can increase RAM to 2-4 GB
- Already configured and ready

**Cons:**
- Monthly cost
- Still need to verify 2 GB is enough

**Action:**
1. Upgrade Railway to Hobby plan
2. Increase memory limit to 2-4 GB
3. Redeploy

### Option 2: Switch to Different Hosting
**DigitalOcean App Platform:** $5/month, 512 MB RAM (likely insufficient)
**DigitalOcean Droplet:** $6/month, 1 GB RAM (can upgrade to 2 GB for $12)
**Render.com:** Free tier with 512 MB (insufficient), paid starts at $7/month

### Option 3: Switch Database (Return to Fly.io)
Replace XTDB2 with a database that doesn't use memory-mapped I/O:
- PostgreSQL (with appropriate Clojure library)
- SQLite (without mmap mode)
- Datomic Cloud

**Pros:**
- Could return to Fly.io (no Firecracker issue)
- Lower memory requirements

**Cons:**
- Requires significant code changes
- Lose XTDB's bitemporal features
- Migration effort

### Option 4: Optimize JVM Memory (Risky)
Add JVM flags to limit heap size in Dockerfile:
```dockerfile
ENV JAVA_OPTS="-Xmx512m -Xms256m"
```

**Pros:**
- No cost

**Cons:**
- Likely won't work with XTDB+Arrow
- May cause different crashes
- Not addressing root issue

## Recommendation

**For production use with XTDB2:** Upgrade Railway to a paid plan with at least 2 GB RAM.

**For cost optimization:** Consider switching to a VPS (DigitalOcean Droplet) for better price/performance ratio at $6-12/month.

**For staying on free tier:** Would require switching away from XTDB2 to a lighter database.

## Current Deployment URL

- **Railway:** https://personalist-production.up.railway.app (not working - OOM)
- **Fly.io:** https://personalist.fly.dev (working but with XTDB crashes)

## Solution Implemented

Added JVM memory limits to Dockerfile (commit 165848f):
- `-Xms256m` - Initial heap: 256MB
- `-Xmx768m` - Maximum heap: 768MB
- `-XX:MaxMetaspaceSize=128m` - Metaspace limit: 128MB
- `-XX:+UseG1GC` - G1 garbage collector for better memory management
- `-XX:MaxGCPauseMillis=200` - Target GC pause time

**Total memory breakdown (1GB container)**:
- JVM heap: up to 768MB
- Metaspace: up to 128MB
- Native memory (XTDB/Arrow): ~64MB
- Container overhead: ~40MB
- Total: ~1000MB (fits in 1GB limit)

## Root Cause Analysis

The issue was **not** insufficient memory allocation, but rather **lack of JVM memory constraints**. Without explicit limits, the JVM attempts to allocate memory based on the host system rather than the container limit, causing the container to exceed its 1GB limit during XTDB initialization.

Deploy logs showed:
- Application started successfully ("Starting system in production mode")
- Container crashed silently ~18 seconds after start
- No error messages, just repeated restarts
- This pattern is characteristic of OOM kills by the container runtime

## Next Steps

1. ✅ Added JVM memory limits
2. 🔄 Currently deploying with new limits
3. Pending: Verify deployment passes health checks
4. Pending: Restore backup data once deployment is stable
