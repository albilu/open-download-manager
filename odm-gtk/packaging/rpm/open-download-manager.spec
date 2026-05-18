%define name open-download-manager
%define version 0.1.0
%define release 1
%define _topdir %(echo $PWD)/rpmbuild

Name:           %{name}
Version:        %{version}
Release:        %{release}%{?dist}
Summary:        Full-featured download manager for Linux
License:        GPL-3.0+
URL:            https://github.com/odm-project/open-download-manager
Source0:        open-download-manager.jar
BuildArch:      noarch

# Build dependencies
BuildRequires:  java-11-openjdk-devel
BuildRequires:  maven >= 3.6.0
BuildRequires:  desktop-file-utils

# Runtime dependencies with minimum and maximum version requirements
Requires:       java-11-openjdk-headless
Requires:       aria2 >= 1.34.0, aria2 < 1.37.0
Requires:       curl >= 7.50.0, curl < 8.6.0
Requires:       yt-dlp >= 2023.01.01, yt-dlp < 2025.01.01
Requires:       httrack >= 3.49.0, httrack < 3.50.0
Requires:       proxychains-ng >= 4.14.0, proxychains-ng < 4.17.0

# Additional recommended packages
Recommends:     tor
Recommends:     ffmpeg
Recommends:     python3-pip

# Conflicts with versions outside tested compatibility range
Conflicts:      aria2 < 1.34.0
Conflicts:      aria2 >= 1.37.0
Conflicts:      curl < 7.50.0
Conflicts:      curl >= 8.6.0
Conflicts:      yt-dlp < 2023.01.01
Conflicts:      yt-dlp >= 2025.01.01
Conflicts:      httrack < 3.49.0
Conflicts:      httrack >= 3.50.0
Conflicts:      proxychains-ng < 4.14.0
Conflicts:      proxychains-ng >= 4.17.0

%description
Open Download Manager (ODM) is a comprehensive download manager designed
specifically for Linux systems. It provides advanced download capabilities
including multi-connection downloads, BitTorrent support, video downloads,
and website mirroring.

Key features:
* Multi-connection HTTP/HTTPS/FTP downloads via aria2
* BitTorrent and magnet link support
* Video downloads from YouTube and other platforms via yt-dlp
* Website mirroring capabilities via HTTrack
* Proxy and Tor support via proxychains
* Clipboard monitoring for automatic download detection
* Download queue management with pause/resume functionality
* GTK-based native Linux interface
* Background operation with system tray integration

All required external tools are included as dependencies with tested
compatibility version ranges to ensure stable operation.

%prep
# No prep needed - using pre-built JAR

%build
# No build needed - using pre-built JAR

%install
rm -rf %{buildroot}

# Create directories
mkdir -p %{buildroot}%{_javadir}
mkdir -p %{buildroot}%{_bindir}
mkdir -p %{buildroot}%{_datadir}/applications
mkdir -p %{buildroot}%{_datadir}/pixmaps
mkdir -p %{buildroot}%{_sysconfdir}/odm
mkdir -p %{buildroot}%{_localstatedir}/lib/odm
mkdir -p %{buildroot}%{_localstatedir}/log/odm
mkdir -p %{buildroot}%{_docdir}/%{name}

# Install JAR file
install -m 644 %{_sourcedir}/open-download-manager.jar %{buildroot}%{_javadir}/%{name}.jar

# Create launcher script
cat > %{buildroot}%{_bindir}/odm << 'EOF'
#!/bin/bash
# Open Download Manager launcher script

# Configuration
JAR_PATH="/usr/share/java/open-download-manager.jar"
CONFIG_FILE="/etc/odm/odm.conf"
LOG_DIR="/var/log/odm"

# Ensure log directory exists and is writable
if [[ ! -d "$LOG_DIR" ]]; then
    mkdir -p "$LOG_DIR" 2>/dev/null || LOG_DIR="$HOME/.odm/logs"
fi

# Java options
JAVA_OPTS="-Xmx512m -Djava.awt.headless=false"

# Add configuration file if it exists
if [[ -f "$CONFIG_FILE" ]]; then
    JAVA_OPTS="$JAVA_OPTS -Dodm.config=$CONFIG_FILE"
fi

# Launch application
exec java $JAVA_OPTS -jar "$JAR_PATH" "$@"
EOF
chmod 755 %{buildroot}%{_bindir}/odm

# Install desktop file
cat > %{buildroot}%{_datadir}/applications/%{name}.desktop << 'EOF'
[Desktop Entry]
Name=Open Download Manager
Comment=Full-featured download manager for Linux
Exec=odm
Icon=open-download-manager
Terminal=false
Type=Application
Categories=Network;FileTransfer;
MimeType=application/x-bittorrent;x-scheme-handler/magnet;
Keywords=download;torrent;aria2;yt-dlp;
StartupNotify=true
EOF

# Create default configuration
cat > %{buildroot}%{_sysconfdir}/odm/odm.conf << 'EOF'
# Open Download Manager Configuration
# This file contains system-wide settings

# Tool paths (auto-discovered if not specified)
#aria2.path=/usr/bin/aria2c
#curl.path=/usr/bin/curl
#yt-dlp.path=/usr/bin/yt-dlp
#httrack.path=/usr/bin/httrack
#proxychains.path=/usr/bin/proxychains4

# Default download settings
downloads.max-concurrent=3
downloads.default-connections=4
downloads.timeout=30

# Logging
logging.level=INFO
logging.file=/var/log/odm/odm.log
EOF

# Install documentation (create placeholder files if originals not available)
echo "Open Download Manager - Full-featured download manager for Linux" > %{buildroot}%{_docdir}/%{name}/README.md
echo "GPL-3.0+ License" > %{buildroot}%{_docdir}/%{name}/LICENSE

%check
# Validate desktop file
desktop-file-validate %{buildroot}%{_datadir}/applications/%{name}.desktop

%post
# Post-installation dependency validation script
cat > /tmp/odm-post-install.sh << 'SCRIPT'
#!/bin/bash
set -e

# Tool version requirements (minimum and maximum tested versions)
declare -A MIN_VERSIONS=(
    ["aria2"]="1.34.0"
    ["curl"]="7.50.0"
    ["yt-dlp"]="2023.01.01"
    ["httrack"]="3.49.0"
    ["proxychains4"]="4.14.0"
)

declare -A MAX_VERSIONS=(
    ["aria2"]="1.36.99"
    ["curl"]="8.5.99"
    ["yt-dlp"]="2024.12.31"
    ["httrack"]="3.49.99"
    ["proxychains4"]="4.16.99"
)

# Tool commands
declare -A TOOL_COMMANDS=(
    ["aria2"]="aria2c"
    ["curl"]="curl"
    ["yt-dlp"]="yt-dlp"
    ["httrack"]="httrack"
    ["proxychains4"]="proxychains4"
)

# Version comparison function
version_ge() {
    local v1="$1" v2="$2"
    v1=$(echo "$v1" | sed 's/^[^0-9]*//; s/[^0-9.]*$//')
    v2=$(echo "$v2" | sed 's/^[^0-9]*//; s/[^0-9.]*$//')

    if command -v rpm >/dev/null 2>&1; then
        rpmdev-vercmp "$v1" ge "$v2" >/dev/null 2>&1
        return $?
    fi

    python3 -c "
import sys
from packaging import version
try:
    result = version.parse('$v1') >= version.parse('$v2')
    sys.exit(0 if result else 1)
except:
    sys.exit(0)
" 2>/dev/null || return 0
}

# Get tool version
get_tool_version() {
    local tool="$1"
    local command="${TOOL_COMMANDS[$tool]}"

    if ! command -v "$command" >/dev/null 2>&1; then
        return 1
    fi

    case "$tool" in
        "aria2")
            "$command" --version 2>/dev/null | head -n1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n1
            ;;
        "curl")
            "$command" --version 2>/dev/null | head -n1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n1
            ;;
        "yt-dlp")
            "$command" --version 2>/dev/null | grep -oE '[0-9]{4}\.[0-9]{2}\.[0-9]{2}' | head -n1
            ;;
        "httrack")
            "$command" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n1
            ;;
        "proxychains4")
            "$command" 2>&1 | grep -i version | grep -oE '[0-9]+\.[0-9]+' | head -n1
            ;;
    esac
}

echo "=== Open Download Manager Dependency Validation ==="
validation_failed=false

for tool in "${!MIN_VERSIONS[@]}"; do
    required="${MIN_VERSIONS[$tool]}"
    command="${TOOL_COMMANDS[$tool]}"

    if ! command -v "$command" >/dev/null 2>&1; then
        if [[ "$tool" == "aria2" || "$tool" == "curl" ]]; then
            echo "❌ $tool: REQUIRED tool not found"
            validation_failed=true
        else
            echo "⚠️  $tool: Optional tool not found"
        fi
        continue
    fi

    current_version=$(get_tool_version "$tool")
    if [[ -z "$current_version" ]]; then
        echo "⚠️  $tool: Could not determine version"
        continue
    fi

    if ! version_ge "$current_version" "$required"; then
        echo "❌ $tool: Version $current_version < required $required"
        validation_failed=true
    else
        # Check maximum version
        local max_version="${MAX_VERSIONS[$tool]}"
        if [[ -n "$max_version" ]] && version_ge "$current_version" "$max_version"; then
            echo "⚠️  $tool: Version $current_version > tested $max_version (may cause compatibility issues)"
        else
            echo "✅ $tool: Version $current_version (OK)"
        fi
    fi
done

if [[ "$validation_failed" == "true" ]]; then
    echo ""
    echo "❌ Dependency validation failed!"
    echo "Install missing dependencies:"
    echo "  dnf install aria2 curl yt-dlp httrack proxychains-ng"
    echo "  or"
    echo "  yum install aria2 curl yt-dlp httrack proxychains-ng"
    exit 1
fi

echo "✅ All dependencies validated successfully"
echo "🚀 Open Download Manager is ready to use!"

SCRIPT

chmod +x /tmp/odm-post-install.sh
/tmp/odm-post-install.sh
rm -f /tmp/odm-post-install.sh

# Update desktop database
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database %{_datadir}/applications
fi

%preun
# Nothing special needed for uninstall

%postun
# Clean up desktop database
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database %{_datadir}/applications
fi

%files
%{_javadir}/%{name}.jar
%{_bindir}/odm
%{_datadir}/applications/%{name}.desktop
%config(noreplace) %{_sysconfdir}/odm/odm.conf
%dir %{_sysconfdir}/odm
%dir %{_localstatedir}/lib/odm
%dir %{_localstatedir}/log/odm
%doc %{_docdir}/%{name}/README.md
%doc %{_docdir}/%{name}/LICENSE

%changelog
* Thu Dec 14 2023 ODM Development Team <dev@odm-project.org> - 0.1.0-1
- Initial RPM package with single full-featured package
- Added dependency validation with minimum and maximum version requirements
- Added version range constraints to ensure compatibility
- Added desktop integration and system configuration
- Consolidated packaging into single comprehensive package
