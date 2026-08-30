%define name open-download-manager
%define version __VERSION__
%define release 1

Name:           %{name}
Version:        %{version}
Release:        %{release}%{?dist}
Summary:        Full-featured download manager for Linux
License:        GPL-3.0-or-later
URL:            https://github.com/albilu/odm
AutoReqProv:    no
Requires:       gtk4 >= 4.10
Requires:       aria2 >= 1.34.0
Requires:       curl >= 7.80.0
Requires:       yt-dlp >= 2024.01.01
Requires:       httrack >= 3.49.0
Recommends:     proxychains-ng
Recommends:     tor
Recommends:     ffmpeg

%description
Open Download Manager (ODM) is a comprehensive download manager for Linux
with multi-connection downloads, BitTorrent support, video downloads via
yt-dlp, website mirroring via HTTrack, proxy rotation, proxychains and Tor
support, clipboard monitoring, and a native GTK4 interface. Ships a bundled
Java 25 runtime.

%prep
# nothing to compile — staged tree provided via %{stage}

%build
# no build step

%install
mkdir -p %{buildroot}
cp -a %{stage}/. %{buildroot}/

%files
%defattr(-,root,root,-)
/opt/open-download-manager/odm.jar
/opt/open-download-manager/runtime/*
/usr/bin/open-download-manager
/usr/share/applications/open-download-manager.desktop
/usr/share/icons/hicolor/128x128/apps/open-download-manager.png
/usr/share/icons/hicolor/512x512/apps/open-download-manager.png
%doc /usr/share/doc/open-download-manager/copyright
%license /usr/share/licenses/open-download-manager/LICENSE

%post
sysctl -w fs.inotify.max_user_watches=524288 >/dev/null 2>&1 || :
update-desktop-database -q || :
gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || :

%postun
update-desktop-database -q || :
