#!/usr/bin/env bash
# Rewrites the Depends field of the .deb built by jpackage so that it installs on
# Debian as well as Ubuntu. jpackage copies the package names of the build host
# (Ubuntu), some of which do not exist on Debian:
#   libjpeg-turbo8  -> "libjpeg62-turbo | libjpeg-turbo8"  (Debian name first)
#   <name>t64       -> "<name>t64 | <name>"               (64-bit time_t renames,
#                                                          absent before Debian 13 / Ubuntu 24.04)
# Usage: fix-deb-depends.sh <package.deb>   (rewrites the file in place)
set -euo pipefail

deb=$1
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

dpkg-deb -R "$deb" "$work/pkg"
control="$work/pkg/DEBIAN/control"

old=$(sed -n 's/^Depends: //p' "$control")
new=$(echo "$old" | tr ',' '\n' | sed 's/^ *//; s/ *$//' | while read -r dep; do
    [ -z "$dep" ] && continue
    case "$dep" in
        *"|"*) echo "$dep" ;;                       # already has alternatives
        libjpeg-turbo8) echo "libjpeg62-turbo | libjpeg-turbo8" ;;
        *t64) echo "$dep | ${dep%t64}" ;;
        *) echo "$dep" ;;
    esac
done | paste -sd ',' | sed 's/,/, /g')

sed -i "s/^Depends: .*/Depends: $new/" "$control"
# xz: readable by every supported dpkg (zstd is not, before Debian 12).
dpkg-deb --root-owner-group -Zxz --build "$work/pkg" "$deb" > /dev/null
echo "Depends: $new"
