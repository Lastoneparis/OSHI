#!/bin/bash
# __OPUS_CODEC_2026_09_23__ Re-download the pinned upstream archives, check their SHA-256 and
# prove every vendored file is byte-identical to upstream (Concentus: except the documented
# Inlines.java patch). Needs curl, shasum, tar, unzip. Exit 0 = verified.
set -euo pipefail
V="$(cd "$(dirname "$0")" && pwd)"; T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $*"; exit 1; }

curl -sSfL -o "$T/opus.tgz" https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz
echo "65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1  $T/opus.tgz" | shasum -a 256 -c - >/dev/null || fail "libopus tarball hash"
tar xzf "$T/opus.tgz" -C "$T"
n=0; while IFS= read -r f; do rel="${f#$V/opus/}"; cmp -s "$f" "$T/opus-1.5.2/$rel" || fail "opus/$rel differs"; n=$((n+1)); done < <(find "$V/opus" -type f)
echo "libopus 1.5.2: $n files identical to upstream"

curl -sSfL -o "$T/c.jar" https://repo1.maven.org/maven2/io/github/jaredmdobson/concentus/1.0.2/concentus-1.0.2-sources.jar
echo "bf3bf881f33b03091aedcfaf30e26eb38b750d012646e003a18a48298a71c189  $T/c.jar" | shasum -a 256 -c - >/dev/null || fail "Concentus sources hash"
mkdir "$T/c" && unzip -q "$T/c.jar" -d "$T/c"
n=0; while IFS= read -r f; do rel="${f#$V/concentus/src/main/java/}"
  if [[ "$rel" == */Inlines.java ]]; then continue; fi
  cmp -s "$f" "$T/c/$rel" || fail "concentus/$rel differs"; n=$((n+1)); done < <(find "$V/concentus/src" -name '*.java')
echo "Concentus 1.0.2: $n files identical; Inlines.java patch:"
diff "$T/c/io/github/jaredmdobson/concentus/Inlines.java" "$V/concentus/src/main/java/io/github/jaredmdobson/concentus/Inlines.java" || true
