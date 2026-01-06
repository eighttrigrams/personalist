# Railway Deployment Guide

**Status:** DEPLOYED & WORKING
**Date Completed:** 2026-01-06
**URL:** https://personalist-production.up.railway.app
**Issues Resolved:**
1. OOM errors - Fixed by adding JVM memory limits (commit 165848f)
2. JavaScript not loading - Fixed by adding ClojureScript compilation to Dockerfile (commit ac85b5a)
3. Backup restoration - Added `make fly-railway-replay` command

**Remaining:** Restore production data from Fly.io backup

---


## Why Railway?

XTDB2 uses Apache Arrow which requires memory-mapped file I/O. This doesn't work on Fly.io's Firecracker microVMs but works fine on Railway's Docker-based infrastructure.

## Setup Steps

### 1. Create Railway Account & Project

1. Go to https://railway.app
2. Sign up / Log in with GitHub
3. Click "New Project"
4. Select "Deploy from GitHub repo"
5. Choose the `personalist` repository
6. Railway will automatically detect the Dockerfile

### 2. Add Volume for Data Persistence

Railway uses volumes for persistent storage:

1. In your Railway project, go to the service settings
2. Click on "Variables" tab
3. Click "New Volume"
4. Mount path: `/app/data`
5. Size: 1GB (or more if needed)

### 3. Configure Environment Variables

Add these environment variables in Railway:

- `PORT`: `8080` (Railway will expose this)
- `HOST`: `0.0.0.0`
- `ADMIN_PASSWORD`: `<your-secure-password>`

Optional variables:
- `DEV`: Leave unset (defaults to production mode)
- `NREPL_PORT`: Only if you need REPL access (development only)

### 4. Deploy

Railway will automatically build and deploy when you push to GitHub. Initial deployment:

1. Railway detects Dockerfile
2. Builds the Docker image
3. Starts the container
4. Exposes port 8080
5. Provides a public URL

### 5. Restore Backup Data

After first deployment, restore your Fly.io backup:

```bash
# Get Railway CLI
npm i -g @railway/cli

# Login
railway login

# Link to your project
railway link

# Get shell access to running container
railway run bash

# From your local machine, in another terminal:
# Upload the backup (you'll need to use railway volumes or scp)
```

Alternatively, use Railway's volume mounting to upload data:

```bash
# Extract backup locally
tar -xzf volume-backup.2026-01-04.19-33.tar.gz

# The Railway CLI can help you upload files to the volume
# Or you can rebuild the app with data included temporarily
```

### 6. Verify Deployment

1. Railway provides a public URL like `https://personalist-production.up.railway.app`
2. Test the endpoints:
   - `GET /` - Should load the UI
   - `GET /api/personas` - Should return personas
3. Check logs in Railway dashboard for any errors

### 7. Custom Domain (Optional)

1. In Railway project settings, go to "Domains"
2. Add your custom domain (e.g., `personalist.org`)
3. Update DNS records as instructed by Railway
4. Railway automatically provisions SSL certificates

## Cost Estimate

Railway pricing (as of 2026):
- **Hobby plan**: $5/month + usage
- **Usage**: ~$10-15/month for a small app
- **Total**: ~$15-20/month

Compare to Fly.io: ~$5-10/month (but doesn't work with XTDB)

## Rollback Plan

If Railway doesn't work:
1. We have backups from Fly.io
2. Can try DigitalOcean or Hetzner instead
3. Data is portable via the backup/restore process

## Next Steps After Deployment

1. Update DNS to point to Railway URL
2. Test all functionality
3. Monitor logs for any issues
4. Set up automated backups (Railway has snapshot features)
5. Consider setting up monitoring (Railway has built-in metrics)
