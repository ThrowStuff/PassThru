#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

die() { echo "Error: $*" >&2; exit 1; }
[[ -n ${PASSTHRU_SIGNING:-} ]] || die 'PASSTHRU_SIGNING must point to an external keystore.properties file.'

export JAVA_HOME=${JAVA_HOME:-/home/User/.local/share/passthru-build/toolchains/jdk-17.0.21+9}
export ANDROID_HOME=${ANDROID_HOME:-/home/User/.local/share/passthru-build/sdk}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-/home/User/.local/share/passthru-build/gradle-user-home}
export PATH="$JAVA_HOME/bin:$PATH"
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$root"
version=$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" cade/app/build.gradle)
win_version=$(sed -n 's/^AppVersion=\(.*\)\r\{0,1\}$/\1/p' cale/packaging/windows/installer.iss)
win_version=${win_version%$'\r'}
[[ -n $version && $version == "$win_version" ]] || die 'Android versionName and Windows AppVersion must match.'
[[ $version =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die 'Version contains unsafe filename characters.'
out="$root/dist/passthru-$version"
[[ ! -e $out ]] || die "Release folder already exists: $out"
apksigner="$ANDROID_HOME/build-tools/35.0.0/apksigner"
[[ -x $apksigner ]] || die 'Android build-tools 35.0.0 apksigner is required.'

(cd cade && ./gradlew --offline --no-daemon --max-workers=2 :app:testReleaseUnitTest :app:assembleRelease)
apk="$root/cade/app/build/outputs/apk/release/app-release.apk"
[[ -f $apk ]] || die 'Signed release APK was not produced.'
# Capture verification output so only the SHA-256 certificate digest is printed.
certs=$("$apksigner" verify --print-certs "$apk")
printf '%s\n' "$certs" | grep -E '^Signer #[0-9]+ certificate SHA-256 digest:'
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
# Materialize dex first: pipefail must not hide unzip errors or grep SIGPIPE.
unzip -p "$apk" 'classes*.dex' > "$work/classes.dex"
[[ -s $work/classes.dex ]] || die 'Release APK contains no dex.'
if LC_ALL=C grep -aE 'test_speaker|pair_confirm|autostart' "$work/classes.dex" > /dev/null; then
    die 'Release dex contains debug-only extra names.'
fi

mkdir "$work/release"
stage="$work/release"
cp -- "$apk" "$stage/PassThru-Cade-$version.apk"
# Preserve the repository layout expected by the Linux install script.
tar --exclude=.venv --exclude=__pycache__ --exclude=tests --exclude=build \
    -czf "$stage/PassThru-Cale-$version-linux.tar.gz" \
    cale/cale cale/packaging/install-linux.sh cale/packaging/uninstall-linux.sh \
    cale/packaging/requirements-linux.txt cale/packaging/passthru-cale.desktop LICENSE README.md
if [[ -f ${PASSTHRU_WIN_INSTALLER:-} ]]; then
    cp -- "$PASSTHRU_WIN_INSTALLER" "$stage/PassThru-Cale-$version-Setup.exe"
else
    echo 'Note: Windows installer skipped (PASSTHRU_WIN_INSTALLER does not point to a file).'
fi
cat > "$stage/INSTALL.txt" <<'EOF'
PassThru: Cale runs on your computer; Cade runs on your Android phone.
Use Android 10 or newer and ASHA or LE Audio hearing aids that work with it.
Keep the phone and computer on the same Wi-Fi.

Windows 11: open the supplied Cale Setup.exe installer. Keep "Allow Cale on
Private networks only" selected and approve the firewall administrator step.
Open PassThru Cale from the Start menu, choose System audio and press Start.

Linux: unpack the Cale linux.tar.gz archive and open a terminal in the
unpacked folder. You need Python 3.11+ with venv and PipeWire with pw-cat.
Run ./cale/packaging/install-linux.sh (no administrator access needed).
It downloads Python dependencies unless given an offline wheelhouse.
Open ~/.local/bin/passthru-cale --gui, choose System audio and press Start.

Phone: Cade is the Android app (the PassThru-Cade .apk file). Copy it to
the phone, open it and allow installing from that source when asked.
Connect your hearing aids as usual, open Cade and allow Nearby devices.
Check the hearing-aid name. With Cale Listening, tap Pair for your computer.
Compare the six-digit codes and confirm on both screens if they match.
For everyday use, press Start in Cale and Cade. See README.md for details.
EOF
(cd "$stage" && sha256sum -- * > SHA256SUMS)
mkdir -p -- "$root/dist"
mv -- "$stage" "$out"
echo "Release assembled: $out"
