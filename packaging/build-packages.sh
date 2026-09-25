#!/bin/bash
# Package builder for Open Download Manager (GTK4).
# Builds the shaded jar + a self-contained jlink runtime, then assembles
# .deb / .rpm / .pkg.tar.zst installers. Run inside the odm-dev Docker
# image (or any Linux with JDK 25, maven, dpkg-deb, rpmbuild, makepkg).
set -euo pipefail

# Package metadata and the shaded native libraries target Linux amd64.
if [[ "$(uname -s):$(uname -m)" != "Linux:x86_64" ]]; then
    echo "Packaging requires Linux amd64 (x86_64); use an amd64 Docker environment." >&2
    exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PROJECT_VERSION="$(mvn -q -N help:evaluate -Dexpression=project.version -DforceStdout)"
VERSION="${1:-$PROJECT_VERSION}"
if [[ ! "$VERSION" =~ ^[0-9]+([.][0-9]+){1,3}$ ]]; then
    echo "Invalid package version: expected numeric dotted version" >&2
    exit 2
fi
if [[ "$VERSION" != "$PROJECT_VERSION" ]]; then
    echo "Package version $VERSION must match Maven version $PROJECT_VERSION" >&2
    exit 2
fi
STAGE="$ROOT/packaging/stage"
DIST="$ROOT/packaging/dist"
RUNTIME="$STAGE/opt/open-download-manager/runtime"
APP="$STAGE/opt/open-download-manager"
JAR="$ROOT/odm-gtk4/target/odm-gtk4-${PROJECT_VERSION}-jar-with-dependencies.jar"

log() { echo "[odm-package] $*"; }

# Modules from jdeps over the shaded jar (+ crypto/naming/management for
# TLS, WebSocket client usage, and runtime introspection). Playwright extracts
# its driver through zipfs and Gson uses Unsafe for browser protocol objects;
# these dynamically loaded modules are not reported by jdeps.
JDK_MODULES="java.base,java.desktop,java.sql,java.logging,java.net.http,jdk.httpserver,jdk.crypto.ec,jdk.zipfs,jdk.unsupported,jdk.localedata,java.naming,java.management"

log "Building shaded jar..."
# Packaging deliberately skips tests, so it must also skip JaCoCo's test
# coverage gate. Otherwise stale jacoco.exec data from an earlier test run can
# make a release build fail even though no tests execute here.
mvn -q -pl odm-gtk4 -am package -DskipTests -Djacoco.skip=true

log "Assembling application tree under $STAGE..."
rm -rf "$STAGE"
mkdir -p "$DIST" "$APP" "$RUNTIME" "$STAGE/usr/bin" \
    "$STAGE/usr/share/applications" \
    "$STAGE/usr/share/metainfo" \
    "$STAGE/usr/share/doc/open-download-manager" \
    "$STAGE/usr/share/licenses/open-download-manager" \
    "$STAGE/usr/share/icons/hicolor"

cp "$JAR" "$APP/odm.jar"
cp LICENSE "$STAGE/usr/share/licenses/open-download-manager/LICENSE"
cp LICENSE "$STAGE/usr/share/doc/open-download-manager/copyright"

log "Creating jlink runtime (modules: $JDK_MODULES)..."
rm -rf "$RUNTIME"
jlink --add-modules "$JDK_MODULES" \
    --strip-debug --no-header-files --no-man-pages --compress zip-6 \
    --output "$RUNTIME"
runtime_modules=$("$RUNTIME/bin/java" --list-modules | cut -d@ -f1)
grep -qx 'java.net.http' <<<"$runtime_modules"
required_modules=$(jdeps --ignore-missing-deps --multi-release 25 \
    --print-module-deps "$APP/odm.jar")
for module in ${required_modules//,/ }; do
    if ! grep -qx "$module" <<<"$runtime_modules"; then
        echo "Bundled runtime is missing required module: $module" >&2
        exit 1
    fi
done

cat > "$STAGE/usr/bin/open-download-manager" <<'EOF'
#!/bin/sh
# oDM launcher: prefer the bundled runtime, fall back to system java
APP_HOME=/opt/open-download-manager
if [ -x "$APP_HOME/runtime/bin/java" ]; then
    exec "$APP_HOME/runtime/bin/java" --enable-native-access=ALL-UNNAMED -jar "$APP_HOME/odm.jar" "$@"
else
    exec java --enable-native-access=ALL-UNNAMED -jar "$APP_HOME/odm.jar" "$@"
fi
EOF
chmod 755 "$STAGE/usr/bin/open-download-manager"

cp packaging/resources/open-download-manager.desktop "$STAGE/usr/share/applications/org.odm.desktop"
sed -e "s/__VERSION__/${VERSION}/g" -e "s/__DATE__/$(date +%F)/g" \
    packaging/resources/org.odm.metainfo.xml \
    > "$STAGE/usr/share/metainfo/org.odm.metainfo.xml"
cp -a odm-gtk4/src/main/resources/icons/hicolor/. "$STAGE/usr/share/icons/hicolor/"

log "Stage complete:"
du -sh "$STAGE" "$RUNTIME"

# ---- .deb ----
build_deb() {
    log "Building .deb..."
    local debroot="$STAGE-deb"
    rm -rf "$debroot"
    cp -r "$STAGE" "$debroot"
    mkdir -p "$debroot/DEBIAN"
    cp packaging/debian/control "$debroot/DEBIAN/control"
    cp packaging/debian/postinst "$debroot/DEBIAN/postinst" 2>/dev/null || true
    cp packaging/debian/prerm "$debroot/DEBIAN/prerm" 2>/dev/null || true
    [ -f "$debroot/DEBIAN/postinst" ] && chmod 755 "$debroot/DEBIAN/postinst"
    [ -f "$debroot/DEBIAN/prerm" ] && chmod 755 "$debroot/DEBIAN/prerm"
    sed -i "s/__VERSION__/${VERSION}/g" "$debroot/DEBIAN/control"
    dpkg-deb --root-owner-group --build -Zxz "$debroot" \
        "$DIST/open-download-manager_${VERSION}_amd64.deb"
    log "Built dist/open-download-manager_${VERSION}_amd64.deb"
}

# ---- .rpm ----
build_rpm() {
    log "Building .rpm..."
    command -v rpmbuild >/dev/null || { log "rpmbuild not found, skipping rpm"; return 0; }
    local rpmtop="$ROOT/packaging/rpmbuild"
    rm -rf "$rpmtop"
    mkdir -p "$rpmtop"/{BUILD,RPMS,SOURCES,SPECS,SRPMS}
    sed -e "s/__VERSION__/${VERSION}/g" packaging/rpm/open-download-manager.spec \
        > "$rpmtop/SPECS/open-download-manager.spec"
    (cd "$rpmtop" && rpmbuild --define "_topdir $rpmtop" --define "stage $STAGE" \
        --nodeps --nocheck -bb "$rpmtop/SPECS/open-download-manager.spec")
    find "$rpmtop/RPMS" "$ROOT/rpmbuild/RPMS" -name "*.rpm" -exec mv {} "$DIST/" \; 2>/dev/null || true
    rm -rf "$rpmtop" "$ROOT/rpmbuild"
}

# ---- Arch .pkg.tar.zst ----
build_arch() {
    log "Building .pkg.tar.zst..."
    local archroot="$ROOT/packaging/archbuild"
    rm -rf "$archroot"
    mkdir -p "$archroot/pkg"
    cp -a "$STAGE/." "$archroot/pkg/"
    chmod 755 "$archroot/pkg/usr/bin/open-download-manager"

    # Minimal pacman package: .PKGINFO + payload, compressed with zstd
    local size
    size=$(du -sk "$archroot/pkg" | cut -f1)
    local builddate
    builddate=$(date +%s)
    cat > "$archroot/pkg/.PKGINFO" <<EOF
pkgname = open-download-manager
pkgbase = open-download-manager
pkgver = ${VERSION}-1
pkgdesc = Full-featured download manager for Linux based on aria2, yt-dlp and httrack (bundled Java runtime)
url = https://github.com/albilu/odm
arch = x86_64
license = GPL-3.0-or-later
depend = gtk4>=4.10
depend = aria2>=1.34.0
depend = curl>=7.80.0
depend = yt-dlp>=2024.01.01
depend = httrack>=3.49.0
optdepend = proxychains-ng: SOCKS proxy chains
optdepend = tor: anonymous downloads
optdepend = ffmpeg: video processing
optdepend = python-subliminal: generic subtitle downloads
optdepend = clamav: completion-time malware scanning
optdepend = chromium: headless media discovery when page extraction fails
packager = ODM Development Team <dev@odm-project.org>
size = $((size * 1024))
builddate = ${builddate}
EOF
    cat > "$archroot/pkg/.BUILDINFO" <<EOF
format = 2
pkgname = open-download-manager
pkgbase = open-download-manager
pkgver = ${VERSION}-1
pkgarch = x86_64
pkgbuild_sha256sum = $(sha256sum packaging/arch/PKGBUILD | cut -d' ' -f1)
packager = ODM Development Team <dev@odm-project.org>
builddate = ${builddate}
builddir = /app
startdir = /app
buildtool = odm-package-builder
buildtoolver = 1.0.0
buildenv = !distcc
buildenv = color
buildenv = !ccache
buildenv = check
buildenv = !sign
options = strip
options = docs
options = !libtool
options = !staticlibs
options = emptydirs
options = zipman
options = purge
options = !debug
options = lto
EOF
    (cd "$archroot/pkg" && LANG=C bsdtar -czf .MTREE --format=mtree \
        --uid 0 --gid 0 \
        --options='!all,use-set,type,uid,gid,mode,time,size,sha256,link' \
        .PKGINFO .BUILDINFO opt usr)
    (cd "$archroot/pkg" && tar -C "$archroot/pkg" \
        --owner=0 --group=0 --numeric-owner \
        --use-compress-program="zstd -19 -T0" \
        -cf "$DIST/open-download-manager-${VERSION}-1-x86_64.pkg.tar.zst" \
        .PKGINFO .BUILDINFO .MTREE opt usr)
    rm -rf "$archroot"
    log "Built dist/open-download-manager-${VERSION}-1-x86_64.pkg.tar.zst"
}

# ---- AppImage ----
# Core runtime libraries stay on the host; everything else travels inside the
# image. Bundling libc/libstdc++ would clash with host binaries the app spawns.
APPIMAGE_LIB_EXCLUDE='^(linux-vdso.*|ld-linux.*|libc\.so|libm\.so|libmvec.*|libdl\.so|librt\.so|libpthread\.so|libresolv\.so|libnsl\.so|libnss_.*|libutil\.so|libatomic.*|libstdc\+\+.*|libgcc_s.*|libselinux.*|libmount.*|libblkid.*)$'

# BFS over ldd output: copy the seed sonames ($1, resolved via ldconfig) and
# the library closure of binary $2 into $3, skipping core host libraries.
appimage_copy_closure() {
    local -n seeds_ref=$1
    local binary="$2"
    local libdir="$3"
    local queue=("${seeds_ref[@]}") item path base dep dbase
    if [[ -n "$binary" ]]; then
        while read -r dep; do
            queue+=("$dep")
        done < <(ldd "$binary" 2>/dev/null | awk '/=> \// {print $3}')
    fi
    while ((${#queue[@]})); do
        item="${queue[0]}"
        queue=("${queue[@]:1}")
        case "$item" in
            /*) path="$item" ;;
            *)
                [[ "$item" =~ $APPIMAGE_LIB_EXCLUDE ]] && continue
                path="$(ldconfig -p | awk -v l="$item" '$1==l {print $NF; exit}')"
                ;;
        esac
        [[ -n "$path" && -f "$path" ]] || continue
        base="$(basename "$path")"
        [[ "$base" =~ $APPIMAGE_LIB_EXCLUDE ]] && continue
        case "$path" in
            /usr/lib/*|/lib/*) ;;
            *) continue ;;
        esac
        [[ -e "$libdir/$base" ]] && continue
        cp -L "$path" "$libdir/$base"
        while read -r dep; do
            dbase="$(basename "$dep")"
            [[ "$dbase" =~ $APPIMAGE_LIB_EXCLUDE ]] && continue
            [[ -e "$libdir/$dbase" ]] || queue+=("$dep")
        done < <(ldd "$path" 2>/dev/null | awk '/=> \// {print $3}')
    done
}

# Copies the stage tree plus the host's GTK4 stack (with the transitive non-core
# library closure) into an AppDir so the image runs on systems without GTK4.
# aria2c is bundled because ODM requires it at startup; yt-dlp/HTTrack are
# resolved from the host PATH at runtime and degrade gracefully when absent.
build_appimage() {
    log "Building AppImage..."
    local appdir="$ROOT/packaging/appimage-build/AppDir"
    local libdir="$appdir/usr/lib/x86_64-linux-gnu"
    rm -rf "$ROOT/packaging/appimage-build"
    mkdir -p "$appdir"
    cp -a "$STAGE/." "$appdir/"
    rm -f "$appdir/usr/bin/open-download-manager"

    local tool="$ROOT/packaging/appimage/appimagetool-x86_64.AppImage"
    if [[ ! -x "$tool" ]]; then
        log "Downloading appimagetool (cached under packaging/appimage/)..."
        curl -fSL --retry 3 -o "$tool" \
            https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
        chmod 755 "$tool"
    fi

    mkdir -p "$libdir"
    local gtk_seeds=(
        libgtk-4.so.1 libglib-2.0.so.0 libgobject-2.0.so.0 libgio-2.0.so.0
        libgmodule-2.0.so.0 libgdk_pixbuf-2.0.so.0 libpango-1.0.so.0
        libpangocairo-1.0.so.0 libpangoft2-1.0.so.0 libcairo.so.2
        libcairo-gobject.so.2 libharfbuzz.so.0 libgraphene-1.0.so.0
        libepoxy.so.0 libfontconfig.so.1 libfreetype.so.6 libpng16.so.16
    )
    appimage_copy_closure gtk_seeds "" "$libdir"
    # aria2c is required for ODM startup; ship it with its library closure.
    local aria2c_path=""
    if command -v aria2c >/dev/null; then
        aria2c_path="$(command -v aria2c)"
        cp -L "$aria2c_path" "$appdir/usr/bin/aria2c"
        local no_seeds=()
        appimage_copy_closure no_seeds "$aria2c_path" "$libdir"
    else
        log "WARNING: aria2c not found on the build host; AppImage requires aria2 at runtime"
    fi
    cp -a /usr/lib/x86_64-linux-gnu/girepository-1.0 "$libdir/"
    cp -a /usr/lib/x86_64-linux-gnu/gdk-pixbuf-2.0 "$libdir/"
    # Pixbuf loaders are dlopened, so their deps are not in the seeds' closure.
    local loader no_deps=()
    for loader in "$libdir"/gdk-pixbuf-2.0/*/loaders/*.so; do
        [[ -e "$loader" ]] || continue
        appimage_copy_closure no_deps "$loader" "$libdir"
    done

    cp "$ROOT/packaging/appimage/AppRun" "$appdir/AppRun"
    chmod 755 "$appdir/AppRun"
    # AppImage convention: desktop file + icon at the AppDir root.
    sed -e 's|^Exec=.*|Exec=AppRun %U|' \
        "$STAGE/usr/share/applications/org.odm.desktop" \
        > "$appdir/open-download-manager.desktop"
    chmod 644 "$appdir/open-download-manager.desktop"
    cp "$STAGE/usr/share/icons/hicolor/256x256/apps/open-download-manager.png" \
        "$appdir/open-download-manager.png"
    ln -sf open-download-manager.png "$appdir/.DirIcon"

    local out="$DIST/Open_Download_Manager-${VERSION}-x86_64.AppImage"
    rm -f "$out"
    local extract=()
    if [[ ! -c /dev/fuse ]] || [[ ! -w /dev/fuse ]]; then
        extract=(--appimage-extract-and-run)
    fi
    (cd "$ROOT/packaging/appimage-build" && ARCH=x86_64 "$tool" \
        "${extract[@]}" -n "$appdir" "$out")
    rm -rf "$ROOT/packaging/appimage-build"
    log "Built dist/Open_Download_Manager-${VERSION}-x86_64.AppImage"
}

# ---- Flatpak bundle ----
# Builds packaging/flatpak/org.odm.yml against the GNOME 50 runtime and exports
# a distributable .flatpak bundle. Requires flatpak-builder and a user
# installation of the runtime/sdk (cached under the mounted flatpak dirs).
build_flatpak() {
    if ! command -v flatpak-builder >/dev/null; then
        log "flatpak-builder not found, skipping flatpak bundle"
        return 0
    fi
    log "Building Flatpak bundle..."
    # flatpak in bare containers has no system bus; alias it to a session bus.
    if [[ -z "${DBUS_SYSTEM_BUS_ADDRESS:-}" ]] && [[ ! -S /run/dbus/system_bus_socket ]]; then
        eval "$(dbus-launch --sh-syntax)"
        export DBUS_SYSTEM_BUS_ADDRESS="$DBUS_SESSION_BUS_ADDRESS"
    fi
    # Containers have no XDG_RUNTIME_DIR; flatpak allocates instance ids there.
    if [[ -z "${XDG_RUNTIME_DIR:-}" ]]; then
        export XDG_RUNTIME_DIR="/tmp/xdg-runtime-$(id -u)"
        mkdir -p "$XDG_RUNTIME_DIR"
        chmod 700 "$XDG_RUNTIME_DIR"
    fi
    flatpak --user remote-add --if-not-exists flathub \
        https://flathub.org/repo/flathub.flatpakrepo
    flatpak --user install -y --noninteractive flathub \
        org.gnome.Platform//50 org.gnome.Sdk//50
    # /app is reserved as the install prefix; build/state live outside it.
    local work=/tmp/odm-flatpak
    rm -rf "$work"
    flatpak-builder --user --disable-rofiles-fuse --force-clean \
        --state-dir="$work/state" --repo="$work/repo" \
        "$work/build" "$ROOT/packaging/flatpak/io.github.odm_linux.open-download-manager.yml"
    flatpak build-bundle "$work/repo" \
        "$DIST/open-download-manager-${VERSION}-x86_64.flatpak" \
        io.github.odm_linux.open-download-manager --runtime-repo=https://flathub.org/repo/flathub.flatpakrepo
    rm -rf "$work"
    log "Built dist/open-download-manager-${VERSION}-x86_64.flatpak"
}

build_deb
build_rpm
build_arch
build_appimage
build_flatpak

log "Artifacts:"
ls -la "$DIST/"*.deb "$DIST/"*.rpm "$DIST/"*.pkg.tar.zst \
    "$DIST/"*.AppImage "$DIST/"*.flatpak 2>/dev/null || true
log "Done."
