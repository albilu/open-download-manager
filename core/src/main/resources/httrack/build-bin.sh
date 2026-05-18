#!/bin/bash
set -e

# Build zlib with musl for performance
wget https://zlib.net/current/zlib.tar.gz
tar xzf zlib.tar.gz
cd zlib-1.3.1
mkdir -p ../zlib-build
CC=musl-gcc ./configure --static --prefix=$(pwd)/../zlib-build
make && make install
cd ..

# Build OpenSSL with musl for stability
wget https://www.openssl.org/source/openssl-3.4.0.tar.gz
tar xzf openssl-3.4.0.tar.gz
cd openssl-3.4.0
mkdir -p ../musl-openssl

CC=musl-gcc \
CPPFLAGS="-I/usr/include/x86_64-linux-musl" \
CFLAGS="-static -Os" \
./Configure linux-x86_64 \
    --prefix=$(pwd)/../musl-openssl \
    --libdir=lib \
    no-shared \
    no-dso \
    no-secure-memory \
    no-async \
    no-afalgeng \
    no-engine \
    no-hw \
    no-threads \
    -static

make -j$(nproc)
make install_sw
cd ..

# Build httrack with proper library linking for download manager
git clone https://github.com/xroche/httrack.git --recurse
cd httrack
mkdir -p ../httrack-build

# Generate configure if needed
if [ ! -f configure ]; then
    autoreconf -fiv
fi

# Configure with explicit library linking order for performance
CC=musl-gcc \
CFLAGS="-static -Os -I$(pwd)/../zlib-build/include -I$(pwd)/../musl-openssl/include" \
LDFLAGS="-static -L$(pwd)/../zlib-build/lib -L$(pwd)/../musl-openssl/lib" \
LIBS="-lssl -lcrypto -lz" \
./configure \
    --with-zlib=$(pwd)/../zlib-build \
    --with-ssl=$(pwd)/../musl-openssl \
    --prefix=$(pwd)/../httrack-build \
    --enable-static \
    --disable-shared

# Build with explicit library order for stability
make -j$(nproc) LIBS="-lssl -lcrypto -lz -ldl"
make install

echo "httrack static binary ready for modular download manager integration"

