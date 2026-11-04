<!-- SPDX-License-Identifier: GPL-3.0-only -->
# PassThru v2

## What PassThru does

PassThru sends your computer's sound to your hearing aids through your Android
phone. Your aids stay paired to the phone. The computer sends sound over your
home Wi-Fi, and the phone plays it to your aids. You do not need to re-pair the
aids to each device.

## What you need

- An Android phone running Android 10 or newer.
- Hearing aids that work with that phone as ASHA or LE Audio hearing aids.
- A Windows 11 or Linux computer on the same Wi-Fi as the phone.
- Cale on the computer and Cade on the phone, as described below.

Classic-Bluetooth hearing aids, such as many Phonak models, already connect to
computers directly. They are not the target for PassThru.

## Install on the computer

### Windows 11

1. Open the Windows installer, `PassThru-Cale-Setup.exe`.
2. Keep **Allow Cale on Private networks only** selected. Check this even when
   reinstalling: the installer remembers your previous choice.
3. Approve the administrator request for that firewall step. It lets your
   phone reach Cale on a private network without opening access on public
   networks. The rest of the installation is for your Windows account.
4. Finish installation. Open **PassThru Cale** from the Start menu.
5. Choose **System audio**. Press **Start**. Cale should show **Listening**.

The Windows first-use firewall prompt can allow both public and private
networks together. The installer's private-only step avoids that prompt.
If you see it anyway, follow the firewall instructions under Troubleshooting.

The supplied project files describe how to make the installer, but do not
give a download location. See [Cale's instructions](cale/README.md) for those
details if you need someone to prepare it.

### Linux

Linux setup uses a short command. You need the project folder on your
computer. The required Linux software is listed under Technical details;
ask someone to help prepare it if needed.

1. Open a terminal in the project's top-level folder.
2. Run this installation command:

   ```sh
   ./cale/packaging/install-linux.sh
   ```

3. Wait for installation to finish. It does not need administrator access.
4. Open Cale with:

   ```sh
   ~/.local/bin/passthru-cale --gui
   ```

5. Choose **System audio**, or the sound output you want to send.
   Press **Start**. Cale should show **Listening**.

## Install Cade on the phone

Cade is the Android app. The supplied instructions describe making the app
from the project, but give no download location or phone installation steps.
You will need a prepared Cade app to install. Someone helping with setup can
use [Cade's instructions](cade/README.md).

Once Cade is installed:

1. Connect your hearing aids to the phone as usual.
2. Connect the phone to the same Wi-Fi as the computer. Open Cade.
3. When asked, allow Nearby devices so Cade can recognise your aids.
   Allow notifications if you want its notices.
4. Check the hearing-aid name shown in Cade. If more than one hearing-aid
   choice is offered, select the aids you want before pressing **Start**.

## First pairing

This pairs Cade with Cale. It does not change your aids' Bluetooth pairing.

1. Leave Cale open and **Listening** on the computer.
2. On the phone, wait for the computer to appear. Cade should say that it
   is ready to pair. Tap **Pair**.
3. Both screens show a six-digit code. Cade asks you to check that the
   computer shows the same code.
4. Compare all six digits. If they match, tap **Codes match** on the phone
   and **Match** on the computer. Confirm on both screens within two minutes.
5. If the codes differ, choose **Don't match** on the phone or
   **Doesn't match** on the computer.

After both confirmations, the devices remember each other. Cade shows the
computer's name. You do not need to compare codes on each later connection.

## Everyday use

Open Cale on the computer, choose the sound output, and press **Start** if it
is stopped. In Cade, check the computer and hearing-aid names, then press
**Start**. Play sound on the computer. Cade shows a sentence such as
“Playing … on …” with a delay figure.

| Ring appearance | Meaning |
|---|---|
| Dim grey | Not connected. |
| Violet, rotating | Connecting. |
| Violet, steady | Playing. |
| Amber | Needs attention. Read the sentence below the ring. |

While started, Cade retries if the computer connection is lost. It can also
wait for the same aids to reconnect and resume. Press **Stop** to stop sound
and automatic retries. Stop is also available in Cade's ongoing notification.

After your aids reconnect, you may see **Your hearing aids are connected —
tap to use them**. Android knows they are connected but has not selected them
for sound. Tap **Choose output** and select your aids in Android's sound
chooser. Older Android versions open Bluetooth settings instead. Cade resumes
when the selected aids become available for sound.

Cade plays only to the hearing aids you chose. If that connection is lost,
it stops playback rather than switching to the phone speaker.

## Troubleshooting

### Computer not found

Check that Cale is open and says **Listening**. Check that both devices are
on the same Wi-Fi. In Cade, choose **Enter address instead** (or **Add manually**).
Enter the computer's address and the **Port** used by Cale. Manual entry still
requires the same six-digit pairing check.

### “Can't reach … Retrying…”

Check the Wi-Fi connection and that Cale is still listening. Cade retries
while started. If the computer address has changed and it is not found
automatically, use manual address entry. Check the Windows firewall step below.

### Aids connected but not playing

Read Cade's status sentence. If it says **tap to use them**, choose your aids
in Android's sound chooser. If they are missing there, use Cade's Bluetooth
settings button and check the aids' LE Audio or media controls. In a physical
test, re-selecting LE Audio made the aids available again.

If Cade says it is waiting for the aids, check their connection to the phone
and Cade's Nearby devices permission. If playback has stopped, press **Start**
again once the aids are available.

On Windows, restart Cale's sound capture after changing or unplugging the
computer's sound output.

### Windows firewall

Use the installer's **Private networks only** step. If you skipped it and
Windows shows its public-and-private access prompt, choose **Cancel**.
Cancel creates blocking rules, so a later allow rule alone will not fix it.

Open Defender Firewall's advanced settings and remove the **PassThru Cale**
block rules. Run the installer again and explicitly select **Allow Cale on
Private networks only**. Approve its administrator request. Uninstalling Cale
keeps the firewall rules and remembered pairings.

## What's tested and what isn't

These results are from the [test log](TEST-LOG.md), dated 3 November 2026.
They do not establish compatibility with every phone or hearing aid.

| Setup or behaviour | Evidence and limits |
|---|---|
| Pixel 7, Android 16, the owner's LE Audio hearing aids | Real Linux computer sound, tones and speech heard in the aids. Pairing and automatic reconnect tested. Wi-Fi stalls caused brief sound dropouts. |
| ASHA-only hearing aids | Not tested on physical hardware. Tests with the owner's aids used LE Audio. |
| Screen off | A 134-second run kept playing, with one burst of sound dropouts. Longer use and moving between Wi-Fi access points remain unverified. |
| Windows 11 | Tested in a virtual computer. Sound reached the Pixel and aids through an extra test connection. This does not prove an ordinary direct home-Wi-Fi setup. |
| Windows installer | Installation, window, Start, pairing and streaming to a simulated phone tested. Private-only firewall rule verified. |
| Linux installer | Install, launch and uninstall checked in a temporary user folder. |
| Reconnect notices and sideways screen layout | Checked on simulated phones. The latest reconnect notification still needs confirmation on the Pixel. |
| Rooted mode | A small optional add-on. Setup and undo commands checked on a simulated phone. Automatic aid selection remains unverified on a rooted physical phone. |

## Technical details

- **Components:** Cale is the desktop sender. Cade is the Android receiver
  (Android 10 / API 29 minimum).
- **Linux requirements:** Python 3.11+ with venv support, and PipeWire with
  `pw-cat` for capture. The install script downloads dependencies from PyPI
  unless given an offline wheelhouse. See [Cale](cale/README.md).
- **Security:** audio travels over TLS 1.3. First pairing requires matching
  numeric codes and confirmation at both ends. Later connections check the
  pinned certificate and a stored device key before audio is sent.
- **Measured delay:** the first physical run recorded PC-to-aid latency of
  160–170 ms, with about 50 ms attributed to PassThru. Later Linux speech
  checks recorded 195–235 ms. A Windows VM test with an extra connection hop
  recorded 110–216 ms. These are test results, not promised limits.
- **Displayed delay:** Cade estimates network, jitter-buffer and AudioTrack
  delay. It excludes device/codec latency beyond AudioTrack's timestamp, so
  it is not a complete measurement of sound arriving at the aids.
- **License:** GPL-3.0-only.

See [SPEC.md](SPEC.md) for the design and [TEST-LOG.md](TEST-LOG.md) for
measurements, fixes and remaining checks.

## Making a release

Developers: run `./tools/release.sh` with `PASSTHRU_SIGNING` pointing to a
`keystore.properties` file outside the repository, as described in
`cade/app/build.gradle`. The Android `versionName` and Windows installer's
`AppVersion` must match. Prepare the Android SDK, JDK and cached Gradle
dependencies first: the release script builds offline and uploads nothing.

The script defaults `JAVA_HOME`, `ANDROID_HOME` and `GRADLE_USER_HOME` to the
default toolchain location `/home/User/.local/share/passthru-build/`; existing environment
values override those defaults. Optionally set `PASSTHRU_WIN_INSTALLER` to an
already-built Windows installer. The script creates `dist/passthru-<version>/`
with the verified Cade APK, Linux Cale archive, optional Windows installer,
installation notes and SHA-256 checksums.
