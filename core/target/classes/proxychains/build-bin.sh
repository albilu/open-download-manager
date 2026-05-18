# needs musl-dev and musl-tools for static linking
git clone https://github.com/rofl0r/proxychains-ng.git --recurse
cd proxychains-ng
git checkout v4.4.0
mkdir -p ../new-build
CC=musl-gcc CFLAGS="-static" ./configure --prefix=$(pwd)/../new-build
make CC=musl-gcc CFLAGS="-static -Os"
make install