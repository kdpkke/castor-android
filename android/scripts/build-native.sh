#!/usr/bin/env bash
set -euo pipefail

# Run on Linux or WSL; Android's executable files are packaged as extracted native libraries.
root="$(cd "$(dirname "$0")/../.." && pwd)"
cache="${CASTOR_NATIVE_CACHE:-/var/tmp/castor-android-native}"
jobs="${CASTOR_BUILD_JOBS:-4}"
checkout="$(printf '%s' "$root" | sha256sum | cut -c1-16)"
mkdir -p "$cache" "$root/android/build" "$root/android/app/src/main/jniLibs"
fetch() {
    local url="$1" archive="$2" destination="$3" checksum="$4"
    if [ ! -f "$cache/$archive" ]; then
        curl --fail --location --retry 3 "$url" -o "$cache/$archive"
    fi
    printf '%s  %s\n' "$checksum" "$cache/$archive" | sha256sum --check --status
    if [ ! -f "$destination/.castor-source-$checksum" ]; then
        mkdir -p "$destination"
        tar -xf "$cache/$archive" -C "$destination" --strip-components=1
        touch "$destination/.castor-source-$checksum"
    fi
}
fetch https://go.dev/dl/go1.27.2.linux-amd64.tar.gz go.tar.gz "$cache/go" ecbadb99091a3f46e31f5f934b068b1864eafa7995211b39eaddf76996045fe5
export PATH="$cache/go/bin:$PATH"
if [ ! -f "$cache/ndk.zip" ]; then
    curl --fail --location --retry 3 https://dl.google.com/android/repository/android-ndk-r30-linux.zip -o "$cache/ndk.zip"
fi
printf '%s  %s\n' 753611f410d002cfcd3f3dc2ef49aad532089d3180b436c060a90bf0fcb64df2 "$cache/ndk.zip" | sha256sum --check --status
if [ ! -f "$cache/android-ndk-r30/.castor-source-ready" ]; then
    unzip -q -o "$cache/ndk.zip" -d "$cache"
    touch "$cache/android-ndk-r30/.castor-source-ready"
fi
ndk="$cache/android-ndk-r30"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$toolchain/bin:$PATH"
fetch https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz ffmpeg.tar.xz "$cache/ffmpeg" 8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
fetch https://github.com/mirror/x264/archive/c24e06c2e184345ceb33eb20a15d1024d9fd3497.tar.gz x264.tar.gz "$cache/x264" 090d730e867fc63631782a1287974635d1237d0fa7c6fd1d09fd543620a56689
fetch https://github.com/openssl/openssl/releases/download/openssl-4.0.3/openssl-4.0.3.tar.gz openssl.tar.gz "$cache/openssl" 325b5c806167c13b40b1ffeadfe0248197c00eccc4cf123ec1e28d2d2fd216d9
fetch https://github.com/freetype/freetype/archive/refs/tags/VER-2-15-0.tar.gz freetype.tar.gz "$cache/freetype" 87c7f1f8699b91e89f773f7dcfa505b044e1dae67f62a55d4e29c3afdcac4fb8
fetch https://github.com/harfbuzz/harfbuzz/releases/download/14.6.0/harfbuzz-14.6.0.tar.xz harfbuzz.tar.xz "$cache/harfbuzz" d07a007327277708a2a73ae437887cdbaf282937f6d03ca5467723e9099af586
fetch https://github.com/sekrit-twc/zimg/archive/refs/tags/release-3.0.6.tar.gz zimg.tar.gz "$cache/zimg" be89390f13a5c9b2388ce0f44a5e89364a20c1c57ce46d382b1fcc3967057577

for abi in "${@:-arm64-v8a}"; do
    case "$abi" in
        arm64-v8a) triple=aarch64-linux-android; arch=aarch64; goarch=arm64; openssl=android-arm64 ;;
        x86_64) triple=x86_64-linux-android; arch=x86_64; goarch=amd64; openssl=android-x86_64 ;;
        *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
    esac
    build="$cache/$abi"
    prefix="$build/prefix"
    out="$root/android/app/src/main/jniLibs/$abi"
    mkdir -p "$build" "$prefix" "$out"
    export CC="$triple"28-clang CXX="$triple"28-clang++ AR=llvm-ar RANLIB=llvm-ranlib
    export ANDROID_NDK_ROOT="$ndk"
    if [ ! -f "$build/x264/.castor-asm" ]; then
        mkdir -p "$build/x264"
        (cd "$build/x264"; "$cache/x264/configure" --host="$triple" --sysroot="$toolchain/sysroot" --prefix="$prefix" --enable-static --enable-pic --disable-cli; make -j"$jobs"; make install; touch .castor-asm)
    fi
    if [ ! -f "$prefix/lib/libssl.a" ]; then
        mkdir -p "$build/openssl"
        (cd "$build/openssl"
         if [ ! -f Makefile ]; then "$cache/openssl/Configure" "$openssl" -D__ANDROID_API__=28 --prefix="$prefix" --libdir=lib no-shared no-tests; fi
         make -j"$jobs"; make install_sw)
    fi
    if [ ! -f "$prefix/lib/libfreetype.a" ]; then
        cmake -S "$cache/freetype" -B "$build/freetype" -G Ninja \
            -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$abi" -DANDROID_PLATFORM=28 \
            -DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
            -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON
        cmake --build "$build/freetype" -j "$jobs"
        cmake --install "$build/freetype"
    fi
    if [ ! -f "$prefix/include/harfbuzz/hb-ft.h" ]; then
        cmake -S "$cache/harfbuzz" -B "$build/harfbuzz" -G Ninja \
            -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$abi" -DANDROID_PLATFORM=28 -DANDROID_STL=c++_static \
            -DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
            -DHB_BUILD_UTILS=OFF -DHB_BUILD_TESTS=OFF -DHB_HAVE_FREETYPE=ON -DHB_HAVE_GLIB=OFF -DHB_HAVE_ICU=OFF \
            -DFREETYPE_LIBRARY_RELEASE="$prefix/lib/libfreetype.a" \
            -DFREETYPE_INCLUDE_DIR_freetype2="$prefix/include/freetype2" -DFREETYPE_INCLUDE_DIR_ft2build="$prefix/include/freetype2"
        cmake --build "$build/harfbuzz" -j "$jobs"
        cmake --install "$build/harfbuzz"
    fi
    if [ ! -f "$prefix/lib/libzimg.a" ]; then
        (cd "$cache/zimg"; ./autogen.sh)
        mkdir -p "$build/zimg"
        (cd "$build/zimg"; "$cache/zimg/configure" --host="$triple" --prefix="$prefix" --enable-static --disable-shared --with-pic --disable-x86simd; make -j"$jobs"; make install)
    fi
    if [ ! -f "$build/ffmpeg/.castor-filters-asm" ]; then
        mkdir -p "$build/ffmpeg"
        (cd "$build/ffmpeg"
         export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
         "$cache/ffmpeg/configure" --target-os=android --arch="$arch" --enable-cross-compile \
            --cc="$CC" --cxx="$CXX" --ar=llvm-ar --ranlib=llvm-ranlib --strip=llvm-strip \
            --sysroot="$toolchain/sysroot" --prefix="$prefix" --enable-static --disable-shared \
            --enable-pic --enable-gpl --enable-version3 --enable-libx264 --enable-openssl \
            --enable-libfreetype --enable-libharfbuzz --enable-libzimg \
            --disable-doc --disable-debug --disable-ffplay --disable-autodetect \
            --extra-cflags="-I$prefix/include" --extra-ldflags="-L$prefix/lib -Wl,-z,max-page-size=16384 -static-libstdc++" \
            --pkg-config-flags=--static --extra-libs=-lm
         make -j"$jobs"
         touch .castor-filters-asm)
    fi
    cp "$build/ffmpeg/ffmpeg" "$out/libffmpeg.so"
    cp "$build/ffmpeg/ffprobe" "$out/libffprobe.so"
    whisper="$build/whisper-$checkout"
    cmake -S "$root/third_party/whisper.cpp" -B "$whisper" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=28 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF \
        -DGGML_OPENMP=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF
    cmake --build "$whisper" --target whisper -j "$jobs"
    export CGO_ENABLED=1 GOOS=android GOARCH="$goarch"
    export CGO_CFLAGS="-I$root/third_party/whisper.cpp/include -I$root/third_party/whisper.cpp/ggml/include"
    export CGO_LDFLAGS="-L$whisper/src -L$whisper/ggml/src -L$toolchain/sysroot/usr/lib/$triple/28 -lwhisper -lggml -lggml-cpu -lggml-base -l:libc++_static.a -l:libc++abi.a -lm -llog -static-libstdc++ -static-openmp -Wl,-z,max-page-size=16384"
    (cd "$root"; go build -buildmode=pie -trimpath -o "$out/libcastor.so" ./cmd/castor-android)
    cp "$toolchain/sysroot/usr/lib/$triple/libc++_shared.so" "$out/libc++_shared.so"
    llvm-strip "$out"/*.so
done

licenses="$root/android/app/src/main/assets/native-licenses"
mkdir -p "$licenses"
chmod -R u+w "$licenses"
cp "$cache/ffmpeg/COPYING.GPLv3" "$licenses/FFmpeg-GPL3.txt"
cp "$cache/x264/COPYING" "$licenses/x264.txt"
cp "$cache/openssl/LICENSE.txt" "$licenses/OpenSSL.txt"
cp "$cache/freetype/LICENSE.TXT" "$licenses/FreeType.txt"
cp "$cache/freetype/docs/FTL.TXT" "$licenses/FreeType-FTL.txt"
cp "$cache/harfbuzz/COPYING" "$licenses/HarfBuzz.txt"
cp "$cache/zimg/COPYING" "$licenses/zimg.txt"
cp "$root/third_party/whisper.cpp/LICENSE" "$licenses/Whisper.txt"
cp "$cache/go/LICENSE" "$licenses/Go.txt"
cp "$ndk/NOTICE.toolchain" "$licenses/Android-NDK-toolchain.txt"
cp "$root/LICENSE" "$licenses/Castor.txt"

license_hash="$(cat "$root/go.mod" "$root/go.sum" | sha256sum | cut -d' ' -f1)"
go_licenses="$root/android/app/src/main/assets/go-licenses"
if [ ! -f "$go_licenses/.castor-$license_hash" ]; then
    license_build="$(mktemp -d "$cache/licenses.XXXXXX")"
    (cd "$root"; env GOOS=linux GOARCH=amd64 CC=gcc CXX=g++ CGO_CFLAGS="$CGO_CFLAGS" CGO_LDFLAGS= \
        go run github.com/google/go-licenses/v2@v2.0.1 save ./cmd/castor-android --save_path="$license_build/licenses")
    mkdir -p "$go_licenses"
    chmod -R u+w "$go_licenses"
    cp -R "$license_build/licenses/." "$go_licenses/"
    chmod -R u+w "$go_licenses"
    touch "$go_licenses/.castor-$license_hash"
fi

tar -czf "$root/android/build/native-corresponding-source.tar.gz" \
    -C "$cache" ffmpeg.tar.xz x264.tar.gz openssl.tar.gz freetype.tar.gz harfbuzz.tar.xz zimg.tar.gz \
    -C "$root" android/scripts/build-native.sh android/README.md
