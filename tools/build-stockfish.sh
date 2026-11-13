#!/bin/sh
# Сборка Stockfish 17.1 под Android arm64 (две версии: обычная и с dotprod).
# Нужен Android NDK; путь к нему — первый аргумент.
set -e
NDK="$1"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
TMP=$(mktemp -d)
git clone --depth 1 --branch sf_17.1 https://github.com/official-stockfish/Stockfish "$TMP/sf"
export PATH="$PATH:$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$TMP/sf/src"
for A in armv8 armv8-dotprod; do
	make clean
	make -j"$(nproc)" build ARCH=$A COMP=ndk EXTRACXXFLAGS=-DNNUE_EMBEDDING_OFF
	llvm-strip stockfish
	if [ "$A" = armv8 ]; then cp stockfish "$OUT/libstockfish.so"; else cp stockfish "$OUT/libstockfish_dp.so"; fi
done
rm -rf "$TMP"
