# S3 Path-Style Access Issue with XTDB 2.x and MinIO

## Problem

XTDB is failing to connect to MinIO (Railway bucket service) because the AWS SDK is using virtual-hosted-style addressing instead of path-style addressing.

### Error
```
java.net.UnknownHostException: xtdb.bucket.railway.internal
```

This shows the SDK is trying to access `xtdb.bucket.railway.internal` (virtual-hosted-style) instead of `bucket.railway.internal/xtdb` (path-style).

## Root Cause

1. [XTDB documentation](https://docs.xtdb.com/ops/aws.html) mentions using `AWS_S3_FORCE_PATH_STYLE=true` environment variable for S3-compatible storage
2. XTDB 2.x uses AWS SDK Java V2
3. [AWS SDK Java V2 deprecated the `AWS_S3_FORCE_PATH_STYLE` environment variable](https://github.com/aws/aws-sdk-java-v2/discussions/3611)
4. AWS SDK V2 requires path-style access to be configured programmatically via `.forcePathStyle(true)` on the S3 client builder

## Current Configuration

### Railway Environment Variables
```
S3_ENDPOINT=http://bucket.railway.internal:9000
S3_BUCKET=xtdb
S3_PREFIX=personalist/
S3_ACCESS_KEY=g5JYWzsyjWfMLroG1kS8F9OmmcFGStmX
S3_SECRET_KEY=qhDCJjrctbxwXozzcAmewGQaYu052Nd1Lv3tFfYOiTSvctjN
AWS_S3_FORCE_PATH_STYLE=true  # NOT WORKING
```

### XTDB Configuration (ds.clj:16-20)
```clojure
{:conn (xtn/start-node {:log [:local {:path "/tmp/xtdb/log"}]
                        :storage [:remote {:object-store [:s3 {:endpoint s3-endpoint
                                                               :bucket s3-bucket
                                                               :prefix s3-prefix}]}]
                        :disk-cache {:path "/tmp/xtdb/cache"}})}
```

## Attempted Solutions

1. ✗ Set `AWS_S3_FORCE_PATH_STYLE=true` environment variable - doesn't work with AWS SDK V2
2. ✗ Set `System.setProperty("AWS_S3_FORCE_PATH_STYLE", "true")` in code - same issue

## Possible Solutions

### Option 1: Custom S3 Configurator (Complex)
Implement a Java/Clojure S3Configurator that creates an S3AsyncClient with `.forcePathStyle(true)`:

```clojure
;; Requires implementing xtdb.s3.S3Configurator interface
;; and overriding makeClient() method
;; See: https://github.com/xtdb/xtdb/blob/1.x/modules/s3/src/xtdb/s3/S3Configurator.java
```

**Pros**: Proper solution that works with XTDB's architecture
**Cons**: Complex, requires deep XTDB knowledge, may require Java interop

### Option 2: Try Configuration Parameter (Unknown)
Try adding `:force-path-style?` or similar to the S3 config:

```clojure
:storage [:remote {:object-store [:s3 {:endpoint s3-endpoint
                                       :bucket s3-bucket
                                       :prefix s3-prefix
                                       :force-path-style? true}]}]  ; UNTESTED
```

**Pros**: Simple if it works
**Cons**: Unknown if XTDB supports this parameter

### Option 3: Custom Domain for Bucket (Workaround)
Configure Railway bucket service with a custom domain that doesn't use subdomain-based addressing.

**Pros**: Avoids the path-style issue entirely
**Cons**: Requires Railway configuration changes, may not be possible

### Option 4: Revert to Local Disk Storage (Not Ideal)
Accept the SIGSEGV crash risk and use local disk storage on the mounted volume.

**Pros**: Simple, no S3 configuration needed
**Cons**: SIGSEGV crashes remain a risk

## Next Steps

1. Try Option 2: add `:force-path-style? true` parameter to S3 config
2. If that fails, investigate Option 1: implement custom S3Configurator
3. If both fail, consider contacting XTDB maintainers or switching storage solutions

## Research Links

- [XTDB AWS Documentation](https://docs.xtdb.com/ops/aws.html)
- [AWS SDK Java V2 Path-Style Discussion](https://github.com/aws/aws-sdk-java-v2/discussions/3611)
- [XTDB S3 Module Source](https://github.com/xtdb/xtdb/tree/1.x/modules/s3)
- [AWS SDK S3Configuration](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/S3Configuration.html)

## Status

BLOCKED - Environment variable approach doesn't work with AWS SDK V2. Need to explore alternative configuration methods.
