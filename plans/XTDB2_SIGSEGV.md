**Status:** IN PROGRESS
**Date Started:** 2026-01-06
**Current Attempt:** Added Arrow memory safety flags to Dockerfile
**Related commits:** 4d6cfbe (Add Arrow memory safety flags)

---

# XTDB2 SIGSEGV Crashes (Fly.io & Railway)

## Problem Summary

XTDB2 crashes with SIGSEGV (segmentation fault) when running on **both Fly.io and Railway**, even though the same code and data work correctly on local machines. This indicates the issue is not specific to Firecracker VMs but rather a broader problem with Apache Arrow in containerized/cloud environments.

### Error Signature

**On Fly.io:**
```
# Problematic frame:
# J 7312 c1 org.apache.arrow.memory.ArrowBuf.getByte(J)B
```

**On Railway:**
```
# Problematic frame:
# J 7666 c2 jdk.internal.misc.Unsafe.getLongUnaligned
# J 8380 c2 org.apache.arrow.memory.ArrowBuf.getInt(J)I
```

The crash occurs in Apache Arrow's low-level memory access operations, typically during database queries that read Arrow-formatted data.

## Root Cause Hypothesis

**Apache Arrow's unsafe memory operations (unaligned memory access) are causing segmentation faults in containerized environments.**

### Evidence

1. **Same data works locally** - Identical Arrow files work on macOS/Linux with normal filesystem
2. **Crashes on both Fly.io AND Railway** - Initially thought Fly.io's Firecracker was the issue, but Railway (using standard Docker) has the same crashes
3. **Alpine vs Debian doesn't matter** - Tried both `eclipse-temurin:21-jre-alpine` and `eclipse-temurin:21-jre`, same crash
4. **Memory increase doesn't help** - Increased from 512MB to 1GB, still crashes (not an OOM issue)
5. **Crashes are intermittent** - App works for a while, then crashes on certain queries
6. **Crashes in JIT-compiled code** - The problematic frames are in C2 (JIT) compiled Arrow code doing unaligned memory access

### Technical Background

XTDB2 is built on [Apache Arrow](https://arrow.apache.org/), which uses:

- **Off-heap (direct) memory allocation** via `arrow-memory-netty` or `arrow-memory-unsafe`
- **Memory-mapped files** for efficient data access
- Low-level memory operations like `ArrowBuf.getByte()` and `MemoryUtil.getLong()`

Fly.io runs applications in [Firecracker microVMs](https://github.com/firecracker-microvm/firecracker). Volumes are mounted as virtual block devices (`/dev/vdc`). There are [known issues](https://github.com/firecracker-microvm/firecracker/issues/1064) with how Firecracker handles SIGSEGV/SIGBUS signals and seccomp violations.

## Why S3 Might Not Be Better

Initially hypothesized that S3 storage would avoid the issue, but this is **not necessarily true**:

1. **S3 is a remote object store** - XTDB would still need to download Arrow files to local storage/memory for processing
2. **Arrow still uses mmap** - Once files are local (cached), Arrow still memory-maps them
3. **Different failure mode** - Might work better because files are fetched fresh each time, but could still crash on local cache access

The real difference with S3 is that XTDB's architecture is designed for "decoupled storage/compute" where:
- Hot data stays on compute nodes
- Historical data lives in S3
- Multiple instances can share the same storage

But the fundamental Arrow memory operations would still occur.

## Related Reports

No direct reports of XTDB2 + Fly.io + SIGSEGV found. Related issues:

- [Firecracker seccomp violation on SIGSEGV](https://github.com/firecracker-microvm/firecracker/issues/1064)
- [Firecracker memory corruption with initrd](https://github.com/firecracker-microvm/firecracker/issues/2325)
- [Litestream exhausts memory on Fly.io](https://github.com/benbjohnson/litestream/issues/403) - SQLite also uses mmap
- Various Fly.io community posts about [SIGSEGV crashes](https://community.fly.io/t/sigsegv-when-launching-custom-entrypoint-cmd/4721)

## Potential Solutions

### 1. In-Memory XTDB (No Persistence)

Run XTDB purely in-memory and seed data on startup. Suitable for small datasets that can be recreated.

### 2. Different Hosting Provider

Use a provider with traditional VMs (not Firecracker microVMs):
- AWS EC2
- DigitalOcean Droplets
- Hetzner Cloud
- Railway (uses Docker, not Firecracker)

### 3. Investigate Arrow Memory Configuration

Possibly configure Arrow to use different memory allocation strategies:
- Try `arrow-memory-unsafe` instead of `arrow-memory-netty`
- Investigate if there's a way to disable mmap (unlikely, as it's core to Arrow's design)

### 4. Report to XTDB Team

File an issue at https://github.com/xtdb/xtdb with:
- Full stack trace
- Environment details (Fly.io, Firecracker, JVM version)
- Steps to reproduce

## Environment Details

- **XTDB Version**: 2.1.0
- **JVM**: Eclipse Temurin 21
- **Platform**: Fly.io (Firecracker microVM)
- **Volume**: Mounted at `/app/data` (ext4 on virtual block device)
- **Memory**: 1GB

## Attempted Solutions (2026-01-06)

### Attempt 1: Arrow Memory Safety Flags (FAILED)

Added JVM flags to disable Arrow's unsafe memory operations:

```dockerfile
-Darrow.enable_unsafe_memory_access=false
-Darrow.enable_null_check_for_get=true
```

**Result:** Did NOT fix the problem. Crashes continued with same SIGSEGV in Arrow code.

**Commit:** 4d6cfbe

### Attempt 2: Disable C2 JIT Compiler (FAILED)

Added flag to disable the C2 optimizing compiler, keeping only C1:

```dockerfile
-XX:TieredStopAtLevel=1
```

All SIGSEGV crashes occur in C2-compiled code (frames marked "c2"). Disabling C2 should prevent these crashes, though performance will be reduced.

**Result:** Did NOT fix the problem. Crashes still occur even with C2 disabled, indicating the problem is deeper in Arrow's native code layer.

**Commit:** d559dd6

## Root Cause Analysis (2026-01-06)

After extensive research and testing, the actual root cause has been identified:

### The Real Problem: Memory-Mapped Files on Network Storage

**Key Finding:** Apache Arrow uses memory-mapped files (mmap) for data access. When these files are on network-attached storage (like Railway/Docker volumes), I/O interruptions cause SIGSEGV crashes.

From Wikipedia on memory-mapped files:
> "I/O errors on the underlying file (e.g., removable drive unplugged, disk full) are reported to applications as SIGSEGV/SIGBUS signals"

**Railway's Infrastructure:**
```
Container → Docker volume → Network storage → Physical disk
                ↓
         mmap() system call
                ↓
    Any network hiccup = SIGSEGV
```

### Apache Arrow Known Issues

Research found multiple Apache Arrow issues with production environments:

1. **Thread Safety Issues** ([apache/arrow#13018](https://github.com/apache/arrow/issues/13018))
   - "Static one-time initialization that is not thread safe"
   - Intermittent SIGSEGV during dataset operations

2. **JVM Scanner Crashes** ([apache/arrow-java#443](https://github.com/apache/arrow-java/issues/443))
   - Crashes in `FunctionRegistry::GetFunction()` during scanner creation
   - Arrow Java 18.0.0
   - "Reproducible in production but not in synthetic tests"
   - Linux container environments

3. **Docker Container Issues** ([apache/arrow-java#123](https://github.com/apache/arrow-java/issues/123))
   - Arrow tries to load Unsafe jars even when configured for Netty
   - Allocation failures in containerized environments

### XTDB v2 Architecture

XTDB v2 is designed for cloud-native deployments:

From [XTDB v2 launch blog](https://xtdb.com/blog/launching-xtdb-v2):
> "reads scale horizontally via object storage where a separation of storage and compute allows the LSM-tree primary temporal index structure to be cached efficiently"

**Recommended Architecture:**
- **Object Storage**: S3/Azure/GCP (or S3-compatible like MinIO)
- **Transaction Log**: Kafka or Kafka-compatible (Redpanda, Warpstream)

**Local Storage Support:**
- ✅ Supported via `!Local` storage module
- ✅ Production-capable for single-node deployments
- ⚠️ File-based transaction log is "development only" for multi-node
- ⚠️ Prone to issues with network-attached storage due to mmap

### Why Local Volumes Fail

Docker volumes on cloud providers (Railway, Fly.io) are typically:
- Network-attached storage (not local SSDs)
- Subject to network latency and interruptions
- Incompatible with memory-mapped file I/O patterns

When Arrow memory-maps files on these volumes:
1. Network hiccup occurs
2. mmap() system call fails
3. OS sends SIGSEGV to process
4. JVM crashes

## Solution Options

### 1. Use S3 + Kafka (Recommended by XTDB)
- Object storage avoids mmap issues (HTTP-based access)
- Kafka provides durable transaction log
- Designed for XTDB v2 architecture
- **Complexity**: Requires setting up MinIO/S3 + Kafka/Redpanda

### 2. Downgrade to XTDB 1.x
- Doesn't use Apache Arrow
- More mature for local storage
- Misses XTDB v2 features (SQL, better performance)

### 3. Switch Databases
- PostgreSQL: Mature, no Arrow/mmap issues
- SQLite: Simple, single-file
- Datomic: Similar temporal features, more mature

### 4. Try Different Cloud Provider with Local SSDs
- Providers with actual local SSDs (not network volumes)
- More expensive but might avoid mmap issues
- Still not guaranteed due to Arrow bugs

## References

- [Apache Arrow Memory Management](https://arrow.apache.org/docs/java/memory.html)
- [TechAscent - Memory Mapping, Clojure, and Apache Arrow](https://techascent.com/blog/memory-mapping-arrow.html)
- [Firecracker GitHub](https://github.com/firecracker-microvm/firecracker)
- [XTDB Storage Docs](https://docs.xtdb.com/storage/1.20.0/)
- [XTDB 2 Local Storage Discussion](https://discuss.xtdb.com/t/xtdb-2-local-storage/349)
