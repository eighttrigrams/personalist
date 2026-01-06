---
name: railway
description: Railway commands applicable and useful for this project
---

Railway commands for deploying and managing the personalist application.

## Commonly Used Commands

### Deploy
```bash
railway up --service personalist
```
Deploy the current codebase to Railway's personalist service.

### View Logs
```bash
railway logs --service personalist
```
Stream logs from the personalist service. Use with grep/tail to filter:
```bash
railway logs --service personalist 2>&1 | grep "S3" | tail -20
railway logs --service personalist 2>&1 | tail -50
```

### Manage Environment Variables
```bash
railway variables --service personalist
```
View or manage environment variables for the personalist service.

### Service Management
```bash
railway service
```
Link to or select a service. Use when Railway commands show "No service could be found."

## Important Notes

- Always use `--service personalist` flag for service-specific commands
- The `railway status` command does NOT support `--service` flag
- Logs are streamed in real-time and can be piped to other commands
- When grepping logs, use `2>&1` to include stderr: `railway logs --service personalist 2>&1 | grep "pattern"`

### Restart vs Redeploy

**Important distinction:**
- **Restart**: Restarts the process in the same container. Container filesystem is preserved (including non-volume paths).
- **Redeploy**: Creates a new container from the image. Only mounted volumes persist; everything else is reset.

**Implication**: Any data stored outside the volume mount path (`/app/data`) will be lost on redeploy but retained on restart.

## Current Service Configuration

- **Service Name**: personalist
- **Region**: AWS eu-north-1 (Europe Stockholm)
- **Port**: 8080
- **Host**: 0.0.0.0
- **Volume**: `/data` (persistent storage for XTDB log)

## Environment Variables (Production)

- `AWS_ACCESS_KEY_ID`: AWS credentials for S3 access
- `AWS_SECRET_ACCESS_KEY`: AWS credentials for S3 access
- `AWS_REGION`: eu-north-1
- `S3_BUCKET`: personalist
- `S3_PREFIX`: personalist/
- `ADMIN_PASSWORD`: Required for production mode
- `HOST`: 0.0.0.0
- `PORT`: 8080
