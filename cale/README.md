<!-- SPDX-License-Identifier: GPL-3.0-only -->
# Cale — PassThru v2

Linux capture and core use the Python 3 standard library. Windows system audio
requires PyAudioWPatch (tested API: 0.2.12.9; ship the matching CPython Windows
x64 wheel). No NumPy is used. From `cale/`:

```sh
python3 -m unittest discover -s tests -v
python3 -m cale --tone --host 0.0.0.0 --port 48200
python3 -m cale --list-devices
python3 -m cale --revoke DEVICE_ID
```

First run creates `~/.config/passthru/{cert.pem,key.pem,devices.json}` with
0600 file permissions, using the installed `openssl` CLI for the self-signed
certificate. Optional `--cert`/`--key` override the TLS identity. Devices are
stored as SHA-256 key hashes only. TLS 1.3 is required; no audio is sent until
AUTH succeeds or both pairing confirmations succeed. Compare the six-digit
code on both screens and answer `y` at Cale within 120 seconds. Stop with Ctrl-C.
mDNS uses optional, lazily imported `zeroconf` on all non-loopback IPv4
interfaces. Missing zeroconf logs a warning and falls back to
`avahi-publish-service` on Linux only; elsewhere use manual host entry.
The existing offline wheelhouse lacks zeroconf; stage it and its dependencies
before using offline installation. Discovery failures do not stop the sender.

For Linux capture use `--pipewire --target YOUR_MONITOR` instead of `--tone`.
Without `--target`, pw-cat uses its default recording source; select the sink's
monitor explicitly for system output capture. Tests never start PipeWire or
Avahi, access audio hardware, or use the real configuration directory. TLS
loopback tests skip when the sandbox blocks sockets or openssl is unavailable.

From the repository root, run deterministic simulations with
`python3 tools/sim/drift_sim.py`. Their latency is jitter-buffer occupancy,
not physical end-to-end latency.

On Windows, choose **System audio** in the GUI or run
`python -m cale --system-audio`. This captures the default WASAPI render device,
requests float32 at its default sample rate, and converts to 48 kHz stereo s16le
in exact 10 ms frames. Mono is duplicated; multichannel input is folded by
averaging even channels into left and odd channels into right (no speaker-mask
weighting). Rate conversion uses streaming linear interpolation. Empty capture
periods are left to the sender's existing silence fill. Restart capture after
changing the default output device or unplugging it; there is no automatic
endpoint migration. On Linux, `--system-audio` uses PipeWire.

On Windows, the installer's **"Allow Cale on Private networks only"** task is
on by default. It asks for administrator approval once (only for `netsh`) and
adds an inbound rule scoped to `PassThru Cale.exe` with `profile=private`, so
Windows never shows its first-listen firewall prompt. No Public rule is ever
created; the installer itself runs per-user.

Why it matters: Windows 11's first-listen prompt ("allow public and private
networks to access this app?", seen on 24H2 in testing) has no private-only
choice. **Allow** opens Cale on public networks too; **Cancel** creates block
rules that take precedence over a later allow rule. If you skipped the task
and see that prompt, choose Cancel, then remove the "PassThru Cale" block
rules in Defender Firewall's advanced settings and re-run the installer with
the task enabled. Uninstall keeps firewall rules and pairing data; remove the
rule there if no longer needed.

Linux installation (Python 3.11+ with venv support, PipeWire/pw-cat for capture):

```sh
./cale/packaging/install-linux.sh /absolute/path/to/wheelhouse
# Or omit the argument to install dependencies from PyPI.
~/.local/bin/passthru-cale --gui
./cale/packaging/uninstall-linux.sh
```

The installer is idempotent, needs no sudo, installs a venv and code under
`~/.local/share/passthru-cale`, a launcher under `~/.local/bin`, and the existing
desktop entry under `~/.local/share/applications`. An offline wheelhouse must
contain all pinned requirements and their transitive wheels for your platform.
Install/uninstall preserve `~/.config/passthru` certificates and pairings.

Build Windows on Windows x64 with CPython 3.12 and Inno Setup 6 installed:

```powershell
cd cale/packaging/windows
.\build.ps1
# Offline alternative: .\build.ps1 -Wheelhouse C:\path\to\windows-wheels
& "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe" .\installer.iss
```

`build.ps1` creates its own `.venv`, installs pinned dependencies, and creates
an onedir, windowed app in `dist\PassThru Cale`. The spec includes Qt Essentials,
PyAudioWPatch/PortAudio, cryptography, and zeroconf, including lazy imports.
Inno Setup produces `output\PassThru-Cale-Setup.exe`; it installs to
`%LOCALAPPDATA%\Programs\PassThru Cale` and adds a Start Menu shortcut.
No build or dependency installation is needed on the development Linux host.
Windows release validation still needs a clean-machine launch, audio capture,
pairing/discovery, and accepting/declining the optional firewall task.

Dependency pins were checked against PyPI: [Qt Essentials 6.10.2](https://pypi.org/project/PySide6-Essentials/6.10.2/),
[PyAudioWPatch 0.2.12.9](https://pypi.org/project/PyAudioWPatch/0.2.12.9/),
[cryptography 46.0.5](https://pypi.org/project/cryptography/46.0.5/),
[PyInstaller 6.16.0](https://pypi.org/project/pyinstaller/6.16.0/), and
[ifaddr 0.2.0](https://pypi.org/project/ifaddr/0.2.0/).
[zeroconf 0.153.0](https://pypi.org/project/zeroconf/) is current as of
2026-11-03 and uses **LGPL-2.1-or-later**; GNU confirms
[LGPL 2.1 compatibility with GPLv3](https://www.gnu.org/licenses/license-list.html#LGPLv2.1).
Requirements pin direct dependencies; pip resolves remaining transitive
requirements. Preserve dependency license notices when distributing bundles.
