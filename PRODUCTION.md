# Production Deployment Guide

## Railway Environment Variables

The following environment variables must be configured in Railway for production deployment:

### Required for Authentication
- `ADMIN_PASSWORD` - Admin password for production access (set in Railway)

### Required for MinIO S3 Storage
MinIO provides S3-compatible object storage to avoid XTDB2 SIGSEGV crashes caused by memory-mapped files on network storage.

- `S3_ENDPOINT` - MinIO endpoint URL
  - Value: `http://bucket.railway.internal:9000`
  - Uses internal Railway DNS for service-to-service communication

- `S3_BUCKET` - MinIO bucket name
  - Value: `xtdb`
  - Created via MinIO Console

- `S3_PREFIX` - Object key prefix for namespace isolation
  - Value: `personalist/`
  - All XTDB objects will be stored under this prefix

- `S3_ACCESS_KEY` - MinIO access key
  - See `.minio-credentials` file for current value
  - Generated when MinIO service was deployed

- `S3_SECRET_KEY` - MinIO secret key
  - See `.minio-credentials` file for current value
  - Generated when MinIO service was deployed

### Auto-configured by Railway
- `HOST` - Bind host (typically `0.0.0.0`)
- `PORT` - Server port (assigned by Railway)

## Production Configuration

The application automatically detects production mode when running on Railway and loads `config.prod.edn` which configures XTDB to use S3 storage instead of local disk.

### Configuration File: config.prod.edn
```clojure
{:db {:type :xtdb2-s3
      :s3-endpoint (or (System/getenv "S3_ENDPOINT") "http://minio:9000")
      :s3-bucket (or (System/getenv "S3_BUCKET") "xtdb")
      :s3-prefix (or (System/getenv "S3_PREFIX") "personalist/")
      :access-key (System/getenv "S3_ACCESS_KEY")
      :secret-key (System/getenv "S3_SECRET_KEY")}
 :pre-seed? false}
```

## MinIO Services on Railway

The MinIO template deploys two services:

1. **Bucket** (`bucket.railway.internal`)
   - S3-compatible API endpoint (port 9000)
   - Used by the application for XTDB storage
   - Internal URL: `http://bucket.railway.internal:9000`
   - Public URL: `bucket-production-65f8.up.railway.app`

2. **Console**
   - Web UI for bucket management (port 9001)
   - URL: `console-production-bf57.up.railway.app`
   - Login with credentials from `.minio-credentials`

## Credentials

MinIO credentials are stored in `.minio-credentials` (gitignored).

To rotate credentials:
1. Update `MINIO_ROOT_USER` and `MINIO_ROOT_PASSWORD` in the Bucket service
2. Update `S3_ACCESS_KEY` and `S3_SECRET_KEY` in the personalist service
3. Redeploy both services

## Deployment Commands

```bash
# Deploy to Railway (requires railway CLI)
make railway-deploy

# Or manually
git push && railway up
```

## Troubleshooting

### Check Logs
```bash
railway logs --service personalist
```

### Verify MinIO Connection
Check startup logs for:
- "Loading configuration from config.prod.edn"
- No connection errors to MinIO endpoint

### Common Issues

1. **XTDB fails to start**: Verify S3_ACCESS_KEY and S3_SECRET_KEY match MinIO credentials
2. **Connection refused**: Ensure MinIO Bucket service is running and using internal DNS name
3. **Bucket not found**: Create "xtdb" bucket via MinIO Console

## Architecture

```
Application (personalist service)
    ↓ HTTP
MinIO Bucket Service (bucket.railway.internal:9000)
    ↓
MinIO Volume (persistent storage)
```

This architecture eliminates SIGSEGV crashes by:
- Using HTTP-based object storage instead of memory-mapped files
- Separating storage process from application process
- Avoiding direct mmap() calls on network-attached storage
