**Status:** RESOLVED
**Date Completed:** 2026-01-06
**Solution:** Migrated from Fly.io to Railway
**What fixed it:** Fly.io uses Firecracker microVMs which are incompatible with Apache Arrow's memory-mapped file I/O. Railway uses traditional Docker containers which work correctly with XTDB2/Arrow.
**Related commits:** ac85b5a (Add ClojureScript compilation), 165848f (Add JVM memory limits)

---

# XTDB2 SIGSEGV Crashes on Fly.io

## Problem Summary

XTDB2 crashes with SIGSEGV (segmentation fault) when running on Fly.io, even though the same code and data work correctly on local machines.

### Error Signature

```
# A fatal error has been detected by the Java Runtime Environment:
#
#  SIGSEGV (0xb) at pc=0x00007f7190239bf5, pid=650, tid=701
#
# Problematic frame:
# J 7312 c1 org.apache.arrow.memory.ArrowBuf.getByte(J)B
```

Or:

```
# Problematic frame:
# J 7389 c1 org.apache.arrow.memory.util.MemoryUtil.getLong(J)J
```

The crash occurs in Apache Arrow's low-level memory access operations during `xtdb.storage.LocalStorage/getRecordBatch`.

## Root Cause Hypothesis

**Apache Arrow's memory-mapped file I/O does not work reliably on Fly.io's Firecracker microVMs with mounted volumes.**

### Evidence

1. **Same data works locally** - Identical Arrow files work on macOS/Linux with normal filesystem
2. **Crashes on Fly.io** - SIGSEGV on first query attempt after startup
3. **Alpine vs Debian doesn't matter** - Tried both `eclipse-temurin:21-jre-alpine` and `eclipse-temurin:21-jre`, same crash
4. **Memory increase doesn't help** - Increased from 512MB to 1GB, still crashes (not an OOM issue)
5. **Clean database still has issues** - Even starting fresh, XTDB is slow to initialize

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

## References

- [Apache Arrow Memory Management](https://arrow.apache.org/docs/java/memory.html)
- [TechAscent - Memory Mapping, Clojure, and Apache Arrow](https://techascent.com/blog/memory-mapping-arrow.html)
- [Firecracker GitHub](https://github.com/firecracker-microvm/firecracker)
- [XTDB Storage Docs](https://docs.xtdb.com/storage/1.20.0/)
- [XTDB 2 Local Storage Discussion](https://discuss.xtdb.com/t/xtdb-2-local-storage/349)
