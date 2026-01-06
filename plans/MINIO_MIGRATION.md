# MinIO Migration Plan

**Status:** IN PROGRESS
**Date Started:** 2026-01-06
**Reason:** Resolve XTDB2 SIGSEGV crashes caused by memory-mapped files on Railway network storage

## Background

The XTDB2 SIGSEGV crashes are caused by Apache Arrow's use of memory-mapped files on Railway's network-attached storage. Any network I/O hiccup causes mmap() to fail, which the OS reports as SIGSEGV, crashing the JVM.

**Solution:** Use S3-compatible object storage (MinIO) which uses HTTP-based access instead of memory-mapping files.

## Architecture Changes

### Current (Problematic)
```
App → mmap() → Railway volume (network storage) → SIGSEGV on I/O issues
```

### Proposed (Stable)
```
App → HTTP → MinIO → Regular I/O (no mmap in app process)
```

## Implementation Plan

### Phase 1: Code Changes ✅

1. **Create production configuration file** (`config.prod.edn`)
   - S3 endpoint, bucket, credentials from environment variables
   - File-based transaction log (acceptable for single-node)

2. **Update config loading** (`server.clj`)
   - Use existing `prod-mode?` check (detects Railway/production environment)
   - Load `config.prod.edn` in production, `config.edn` in development

3. **Update XTDB initialization** (`ds.clj`)
   - Add `:xtdb2-s3` type support
   - Configure remote storage with S3 settings
   - Keep local transaction log at `/tmp/xtdb/log`

### Phase 2: Railway Setup (TODO)

1. **Add MinIO service to Railway project**
   - Use Railway template or Docker image
   - Configure with persistent volume
   - Set root credentials

2. **Configure environment variables in Railway app**
   ```
   S3_ENDPOINT=http://minio:9000
   S3_BUCKET=xtdb
   S3_PREFIX=personalist/
   S3_ACCESS_KEY=<minio-root-user>
   S3_SECRET_KEY=<minio-root-password>
   ```

   Note: Production mode is auto-detected (no USE_S3 flag needed)

3. **Create S3 bucket in MinIO**
   - Access MinIO console
   - Create `xtdb` bucket
   - Set appropriate permissions

4. **Deploy and test**
   - Deploy updated code
   - Verify XTDB connects to MinIO
   - Monitor for SIGSEGV crashes (should be eliminated)

### Phase 3: Data Migration (TODO)

1. **Export existing data** from local volume
   ```clojure
   ;; Script to export all docs from current XTDB
   ```

2. **Import into S3-backed XTDB**
   ```clojure
   ;; Script to import docs into new XTDB instance
   ```

3. **Verify data integrity**

4. **Remove old volume**

## Configuration Files

### config.prod.edn
```clojure
{:db {:type :xtdb2-s3
      :s3-endpoint (or (System/getenv "S3_ENDPOINT") "http://minio:9000")
      :s3-bucket (or (System/getenv "S3_BUCKET") "xtdb")
      :s3-prefix (or (System/getenv "S3_PREFIX") "personalist/")
      :access-key (System/getenv "S3_ACCESS_KEY")
      :secret-key (System/getenv "S3_SECRET_KEY")}
 :pre-seed? false}
```

### XTDB Node Configuration
```clojure
{:log [:local {:path "/tmp/xtdb/log"}]
 :storage [:remote {:object-store [:s3 {:endpoint s3-endpoint
                                        :bucket s3-bucket
                                        :prefix s3-prefix
                                        :credentials {:access-key-id access-key
                                                     :secret-access-key secret-key}}]}]}
```

## Development vs Production

- **Development**: Uses `config.edn` with local disk storage (as before)
- **Production**: Auto-detected by `prod-mode?` check; uses `config.prod.edn` with MinIO

No changes needed to local development workflow.

## Alternatives Considered

1. **Full Kafka + S3 setup** - Too complex for single-node deployment
2. **Downgrade to XTDB 1.x** - Loses v2 features (SQL, better performance)
3. **Switch databases** - Would require significant rewrite
4. **Different cloud provider** - No guarantee of fixing mmap issues

## Risks and Mitigations

**Risk:** File-based transaction log is "development only"
- **Mitigation:** Acceptable for single-node; XTDB team hasn't explained specific issues for this use case
- **Future option:** Add Redpanda if scaling to multiple nodes

**Risk:** MinIO might also have mmap issues internally
- **Mitigation:** MinIO is designed for object storage and handles I/O gracefully; crashes stay in MinIO process, not our app

**Risk:** Data migration complexity
- **Mitigation:** Small dataset; can re-seed if needed

## Success Criteria

1. ✅ Code changes complete
2. ⏳ MinIO deployed on Railway
3. ⏳ Environment variables configured
4. ⏳ App connects to MinIO successfully
5. ⏳ No SIGSEGV crashes after 24 hours of testing
6. ⏳ Data migrated successfully

## References

- [XTDB S3 Storage Documentation](https://docs.xtdb.com/ops/aws.html)
- [MinIO Docker Setup](https://min.io/docs/minio/container/index.html)
- [XTDB2 SIGSEGV Root Cause Analysis](./XTDB2_SIGSEGV.md)
