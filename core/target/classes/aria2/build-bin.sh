#!/bin/bash
# Build aria2 with static linking - adapted from aria2-static-build
set -e

ARIA2_VERSION="1.36.0"
BUILD_DIR="$(pwd)/build"
PREFIX_DIR="$(pwd)/aria2-build"

# Create build directories
mkdir -p "$BUILD_DIR" "$PREFIX_DIR"
cd "$BUILD_DIR"

# Download and extract aria2
if [ ! -f "aria2-${ARIA2_VERSION}.tar.gz" ]; then
    wget "https://github.com/aria2/aria2/releases/download/release-${ARIA2_VERSION}/aria2-${ARIA2_VERSION}.tar.gz"
fi

if [ ! -d "aria2-${ARIA2_VERSION}" ]; then
    tar xzf "aria2-${ARIA2_VERSION}.tar.gz"
fi

cd "aria2-${ARIA2_VERSION}"

# Check if musl-gcc is available
if ! command -v musl-gcc &> /dev/null; then
    echo "musl-gcc not found. Installing musl-tools..."
    sudo apt-get update && sudo apt-get install -y musl-tools musl-dev
fi

# Configure with musl for static linking
export CC=musl-gcc
export CXX=musl-g++
export CFLAGS="-static -Os -ffunction-sections -fdata-sections"
export CXXFLAGS="-static -Os -ffunction-sections -fdata-sections"
export LDFLAGS="-static -Wl,--gc-sections"
export PKG_CONFIG="pkg-config --static"

./configure \
    --prefix="$PREFIX_DIR" \
    --enable-static \
    --disable-shared \
    --disable-nls \
    --without-gnutls \
    --without-openssl \
    --without-libxml2 \
    --without-libexpat \
    --without-libz \
    --without-libcares \
    --without-sqlite3 \
    --disable-websocket \
    --disable-ssl \
    ARIA2_STATIC=yes

# Build and install
make -j$(nproc)
make install

# Verify static linking
echo "Checking static linking:"
file "$PREFIX_DIR/bin/aria2c"
ldd "$PREFIX_DIR/bin/aria2c" || echo "Successfully statically linked!"

cd ../..