# Vendored third-party source — Opus

__OPUS_CODEC_2026_09_23__ · Rule (owner, permanent): Opus is used **only from source vendored
here, at a pinned upstream release whose checksum/signature was verified, compiled by our own
build on every platform**. No prebuilt binary, no Apple/Android system Opus codec, no network
code. The codec only turns PCM into bytes and back; AES-GCM is applied to its output by the call
code exactly as for every other wire type. Run `./verify-vendored.sh` to re-check everything below.

## `opus/` — libopus 1.5.2 (C) — iOS, iPadOS, Mac Catalyst

| | |
|---|---|
| Upstream | https://opus-codec.org · tag `v1.5.2` of https://github.com/xiph/opus |
| Tarball | https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz |
| SHA-256 | `65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1` — identical to `https://downloads.xiph.org/releases/opus/SHA256SUMS.txt` **and** to the GitHub release asset `v1.5.2/opus-1.5.2.tar.gz` (checked 2026-09-23) |
| License | BSD-3-Clause (`opus/COPYING`) |
| Files | exactly the 137 `.c` of `CELT_SOURCES + SILK_SOURCES + SILK_SOURCES_FLOAT + OPUS_SOURCES + OPUS_SOURCES_FLOAT` (upstream `*_sources.mk`) + every `.h` of `include/ celt/ silk/ silk/float/ src/`, **unmodified**. Not vendored: `dnn/` (DRED/LACE, off by default), asm/intrinsics dirs, tests, demos. |

Built by Xcode through two unity files in `opus_oshi/` (`oshi_opus_a.c`, `oshi_opus_b.c`: two
file entries in `OSHI.xcodeproj` instead of 137, per-file `COMPILER_FLAGS` = the five `-I` + `-w`).
Configuration = upstream defaults: float, `VAR_ARRAYS`, no custom modes, no DNN, plain C (no
runtime CPU detection). **Measured**: this build encodes bit-identically to upstream's own CMake
build (speech + music, 24/28/32/40 kbit/s, complexity 5/8/10, FEC on/off). Compiles for
arm64-ios, arm64-ios-simulator, arm64/x86_64-ios-macabi (Catalyst) and macOS.
`opus_oshi/oshi_opus.h` is our six-function facade (Swift cannot call the variadic `opus_*_ctl`).

## `concentus/` — Concentus 1.0.2 (pure Java port of libopus) — Android, Desktop (Windows / Linux / macOS JVM)

| | |
|---|---|
| Upstream | https://github.com/lostromb/concentus (Java port), released to Maven Central as `io.github.jaredmdobson:concentus:1.0.2` (scm https://github.com/jaredmdobson/concentus) |
| Source archive | https://repo1.maven.org/maven2/io/github/jaredmdobson/concentus/1.0.2/concentus-1.0.2-sources.jar |
| SHA-256 | `bf3bf881f33b03091aedcfaf30e26eb38b750d012646e003a18a48298a71c189` (= Maven Central's `.sha256`) |
| Signature | `concentus-1.0.2-sources.jar.asc`: **Good signature**, RSA key `B743865AD256F987AA3ACAE436ABDB405BFE7A0A` "Jared Dobson" (key now expired; signed 2024-05-21) |
| License | BSD-3-Clause (`concentus/LICENSE`, from lostromb/concentus) |
| Files | the 124 `.java` of the sources jar, compiled by Gradle (`OSHI-Android/app/build.gradle.kts`, `OSHI-Desktop/build.gradle.kts`: `java.srcDir`). One JVM bytecode for every desktop OS — no per-OS native build. |

**One local patch** (`Inlines.java`, `OpusAssert`): the port keeps libopus's *debug* assertions
live and throws `AssertionError` from the encoder on ordinary speech (measured: `fr_flo`,
complexity ≥ 5, `silk_MLA` in `BurgModified`). libopus release builds compile those assertions
out (NDEBUG); the patch makes them no-ops the same way. Nothing else is changed.

## Interop (measured 2026-09-23, audio_lab)

Encode on one, decode on the other, all four ways (libopus⇄Concentus): every stream decodes;
PESQ-WB of a given stream is the same whichever decoder plays it.
