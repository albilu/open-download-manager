#!/bin/bash
# Validate the artifacts that will be attached to a release. Run in odm-dev.
set -euo pipefail
PACKAGE_ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION="${1:?Pass the package version}"
[[ "$VERSION" =~ ^[0-9]+([.][0-9]+){1,3}$ ]] || exit 2
CHECK_ROOT="$(mktemp -d)"
trap 'rm -rf "$CHECK_ROOT"' EXIT
DEB="$PACKAGE_ROOT/open-download-manager_${VERSION}_amd64.deb"
RPM="$PACKAGE_ROOT/open-download-manager-${VERSION}-1.x86_64.rpm"
ARCH="$PACKAGE_ROOT/open-download-manager-${VERSION}-1-x86_64.pkg.tar.zst"
for artifact in "$DEB" "$RPM" "$ARCH"; do test -s "$artifact"; done
[[ "$(dpkg-deb -f "$DEB" Version)" == "$VERSION" ]]
[[ "$(rpm --dbpath "$CHECK_ROOT/rpmdb" -qp --qf '%{VERSION}-%{RELEASE}' "$RPM")" == "$VERSION-1" ]]
rpm --dbpath "$CHECK_ROOT/rpmdb" -K --nosignature "$RPM"
rpm --dbpath "$CHECK_ROOT/rpmdb" -qp --qf '[%{FILEUSERNAME}:%{FILEGROUPNAME}\n]' "$RPM" > "$CHECK_ROOT/rpm-owners"
if grep -vqx 'root:root' "$CHECK_ROOT/rpm-owners"; then
    echo 'RPM contains non-root payload ownership' >&2; exit 1
fi
mkdir "$CHECK_ROOT/deb" "$CHECK_ROOT/rpm" "$CHECK_ROOT/arch"
dpkg-deb --fsys-tarfile "$DEB" > "$CHECK_ROOT/deb.tar"
zstd -dq "$ARCH" -o "$CHECK_ROOT/arch.tar"
python3 - "$CHECK_ROOT/deb.tar" "$CHECK_ROOT/arch.tar" <<'PY'
import sys, tarfile
for archive in sys.argv[1:]:
    with tarfile.open(archive) as package:
        assert all(m.uid == 0 and m.gid == 0 for m in package.getmembers()), archive
PY
tar -xf "$CHECK_ROOT/deb.tar" -C "$CHECK_ROOT/deb"
rpm2cpio "$RPM" | bsdtar -xf - -C "$CHECK_ROOT/rpm"
tar -xf "$CHECK_ROOT/arch.tar" -C "$CHECK_ROOT/arch"
grep -qx "pkgver = ${VERSION}-1" "$CHECK_ROOT/arch/.PKGINFO"
test -s "$CHECK_ROOT/arch/.MTREE"
test -s "$CHECK_ROOT/arch/.BUILDINFO"
python3 - "$CHECK_ROOT/arch" <<'PYMTREE'
import gzip, hashlib, pathlib, re, shlex, sys
root = pathlib.Path(sys.argv[1])
checked = 0
for line in gzip.open(root / '.MTREE', 'rt'):
    if not line.startswith('./'):
        continue
    fields = shlex.split(line)
    digest = next((v.split('=', 1)[1] for v in fields[1:] if v.startswith('sha256digest=')), None)
    if digest:
        path = root / fields[0]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, str(path)
        checked += 1
assert checked > 0, 'MTREE contains no payload hashes'
print(f'Validated {checked} Arch MTREE hashes')
PYMTREE
# Each format must carry precisely the same application, runtime and licensing files.
for format in deb rpm arch; do
    root="$CHECK_ROOT/$format"
    test -x "$root/usr/bin/open-download-manager"
    test -s "$root/usr/share/doc/open-download-manager/copyright"
    test -s "$root/usr/share/licenses/open-download-manager/LICENSE"
    test -s "$root/usr/share/applications/org.odm.desktop"
    (cd "$root" && find opt usr -type f -print0 | sort -z | xargs -0 sha256sum) > "$CHECK_ROOT/$format.sha256"
done
diff -u "$CHECK_ROOT/deb.sha256" "$CHECK_ROOT/rpm.sha256"
diff -u "$CHECK_ROOT/deb.sha256" "$CHECK_ROOT/arch.sha256"
APP_ROOT="$CHECK_ROOT/deb/opt/open-download-manager"
"$APP_ROOT/runtime/bin/java" --list-modules > "$CHECK_ROOT/modules"
grep -q '^java.net.http@' "$CHECK_ROOT/modules"
# Exercise native GTK resource loading, HTTP and SQLite with the actual bundled JVM/JAR.
cat > "$CHECK_ROOT/PackageRuntimeCheck.java" <<'JAVA'
import java.sql.DriverManager;
import java.net.http.HttpClient;
import org.gnome.gtk.Gtk;
import org.odm.gtk4.UiLoader;
public class PackageRuntimeCheck {
    public static void main(String[] args) throws Exception {
        try (var http = HttpClient.newHttpClient()) { }
        Class.forName("org.sqlite.JDBC");
        try (var database = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            if (!database.isValid(1)) throw new AssertionError("SQLite runtime unavailable");
        }
        Gtk.init();
        for (String ui : new String[]{"main-window", "new-download", "new-media", "new-website", "settings"}) {
            UiLoader.load("/ui/" + ui + ".ui");
        }
        System.out.println("Packaged runtime, GTK resources, HTTP and SQLite passed");
    }
}
JAVA
javac --release 25 -cp "$APP_ROOT/odm.jar" "$CHECK_ROOT/PackageRuntimeCheck.java"
xvfb-run -a "$APP_ROOT/runtime/bin/java" --enable-native-access=ALL-UNNAMED \
    -cp "$CHECK_ROOT:$APP_ROOT/odm.jar" PackageRuntimeCheck
# Bound the real application's startup; its GTK entry point has no dedicated smoke switch.
set +e
XDG_CONFIG_HOME="$CHECK_ROOT/config" XDG_DATA_HOME="$CHECK_ROOT/data" XDG_STATE_HOME="$CHECK_ROOT/state" \
    xvfb-run -a timeout -k 10s 25s "$APP_ROOT/runtime/bin/java" --enable-native-access=ALL-UNNAMED \
    -jar "$APP_ROOT/odm.jar" > "$CHECK_ROOT/launcher.log" 2>&1
launch_status=$?
set -e
cat "$CHECK_ROOT/launcher.log"
[[ "$launch_status" == 124 ]]
grep -q 'MainWindow constructed' "$CHECK_ROOT/launcher.log"
if grep -Eq 'Startup failed|NoClassDefFoundError|NoSuchMethodError' "$CHECK_ROOT/launcher.log"; then
    exit 1
fi
echo 'All three package formats and the bundled launcher passed'
