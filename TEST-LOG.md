# PassThru v2 test log

SPDX-License-Identifier: GPL-3.0-only

## 2026-11-03 — first physical run (Pixel 7 → the owner's LE Audio hearing aids)

- Setup: Cale on lilred (a LAN address, Wi-Fi), quiet 440 Hz test tone
  (~−27 dBFS). Cade debug build on Pixel 7 (Android 16), installed alongside
  existing apps. Aids connected to the Pixel over **LE Audio** (HAP), output
  type `TYPE_BLE_HEADSET`.
- Pairing: numeric comparison; both sides derived 936886. Reconnect with the
  stored key after reinstall + Cale restart: OK, no user input.
- Bug found and fixed: fixed AudioTrack buffer (256 frames) underran on the
  LE Audio output (960-frame HAL period) → ~45 frames/s dropped. Added
  underrun-driven buffer tuning; settled at 736 frames (15 ms).
- Result over 47 s steady state: measured PC→aid latency **160 ms** (range
  160–170, no creep); PassThru share ≈ 50 ms (jitter 30 + track 15 + network),
  Android's BT path ≈ 110 ms. 0 underruns, 0 drops after startup.
- **Audible:** owner confirmed the tone was heard in the aids (not worn;
  checked by ear).
- Not yet tested: real PC audio via PipeWire capture, screen-off/Doze, Wi-Fi
  roaming, long runs (> 5 min), ASHA-mode aids, Windows.

## 2026-11-03 — real PC audio, reconnect, screen-off (same hardware)

- **Auto-reconnect:** Cade (left running) reconnected by itself after Cale
  restarted, using the stored pairing. OK.
- **Real system audio:** `pw-cat` capture of lilred's default sink monitor;
  system sounds played on lilred reached the phone (captured peak 27613).
- Bug found and fixed: 20 ms silence-fill threshold fired between PipeWire's
  ~21 ms quanta, adding ~20 surplus frames/s → ~20 overflow drops/s. Raised
  threshold to 100 ms; afterwards 0 new drops, latency steady ≈150 ms.
- **Screen off (Doze) 134 s:** relay kept running; stats every second;
  latency 140–171 ms; 0 overflow drops. **One glitch:** 6 underruns in a
  ~2 s burst at +62 s (likely a Wi-Fi delivery spike > 30 ms buffer).
  Open item: adaptive jitter target or longer screen-off runs to characterize.
- Cleanup: debug app uninstalled; Pixel screen timeout restored to 60000 ms
  (stay_on 15 unchanged); keep-awake state file marked inactive.

## 2026-11-03 — post-test fixes (no phone; emulator + simulation)

- Adaptive jitter target merged (learns gap size; headroom-gated decay).
  Simulation: stall scenarios 35 → 1–2 underruns; repeated 60 ms spikes →
  1 underrun total, target back to 30 ms.
- Startup no longer counts as an underrun (uncounted prefill + AudioTrack
  primed with silence). Emulator: 0 underruns, target stays 30 ms.
- Hearing-aid identity is now {type, address, name}; relay waits and resumes
  automatically when the same aid reconnects. **Needs physical verification**
  (aids in case → out) next time the Pixel is released.
- Cale desktop GUI (PySide6) merged; rendered offscreen only so far.

## 2026-11-03 — Windows 11 VM (red-win11)

- Driven through a loopback-only command relay (guest polls host
  127.0.0.1:48765 via slirp 10.0.2.2); owner started the agent manually.
- Found and fixed: no `openssl` on Windows (Cale could not start); WASAPI
  loopback stops when idle (silent keepalive stream added); Windows timer
  granularity slowed silence fill to ~64 frames/s (due-time pacing).
- Result: 66/66 unit tests pass on Windows. End-to-end WASAPI loopback →
  Sender → paired TLS client: 500/500 frames in 5 s, max arrival gap 16 ms,
  tone bit-exact, 0 sequence gaps, 0 glitches.
- Not yet: Windows → phone over the network (VM is NATed), Windows GUI on a
  real desktop, packaging.

## 2026-11-03 — physical round 2 (Pixel 7 + the owner's aids, LE Audio)

- **Linux real audio → aids:** OK. Wi-Fi delivery stalls (both on 5 GHz
  ch 36, good signal) caused one burst of 12 underruns; adaptive target rose
  to 100 ms, then decayed 100→90→80 on schedule.
- **Auto-resume:** aids disconnected/reconnected by owner → session ended
  and a new one started automatically for the same aid. OK.
- Found: after the reconnect Android left the aids as *pending* active
  device; no BLE output existed until the owner re-selected LE Audio.
  Debug autostart then fell back to the debug speaker entry → fixed (debug
  autostart now refuses the speaker unless `test_speaker=true`).
  Open item: detect "aids connected but not the active media output" and
  tell the user, instead of waiting silently.
- **Windows VM → phone → aids:** via a test-only TCP joiner on lilred (VM is
  NATed; TLS end-to-end). Owner heard the beep in the aids with the VM's
  lilred playback muted. Latency 110–216 ms (VM + joiner hop included);
  startup underrun can still raise the target (open item).
- Phone audit (read-only): no competing services, no BT overrides, audio
  mode normal, no adb redirects. Leftovers: 2 UI dumps + empty
  dalvik-cache in /data/local/tmp (v1, Oct 28); v1 apps installed, inert.
- Released: debug app uninstalled, timeout 60000 restored, stay_on 15.

## 2026-11-03 — connected-but-unavailable aid handling (host only)

- Read physical round 2 before implementation. Added plain-value
  `AidAvailability` policy and seven JVM cases covering output precedence,
  either pair member, ASHA/LE/API 37 types, unrelated outputs/devices, missing
  identity, absent profile observations, and reconnect/disconnect transitions.
- Added read-only bonded HEARING_AID / LE_AUDIO profile observations while
  waiting (2 s snapshots, permission/version guards, proxy cleanup). The
  service and notification distinguish connected-without-output; Activity
  offers the public API 34+ output picker and Bluetooth settings fallback,
  plus direct Bluetooth settings for LE Audio controls. Playback/resume still
  requires the saved output and Player route verification.
- Research URLs and confidence/limitations recorded under SPEC.md Open
  research. No evidence established that USAGE_MEDIA playback activates this
  pending state; no trigger playback added.
- Attempted offline `testDebugUnitTest assembleDebug`: blocked before
  compilation because Gradle's daemon cannot open its local socket in the
  sandbox (`java.net.SocketException: Operation not permitted`). No APK build
  result claimed.
- Alternative verification: JDK 17 `javac` compiled all Cade production and
  test Java sources against the installed Android API 35 public SDK, using
  temporary R constants and BuildConfig (DEBUG=true and DEBUG=false) under
  `/tmp/pt-aid-availability-check`. JUnit 4.13.2: **39/39 passed in each
  configuration**. This checks Java/API compatibility, not resource packaging
  or Android runtime behavior. `git diff --check`: passed.
- Not run: adb, emulator, installation, Bluetooth/device interactions, audio,
  or physical tests. Needed later: Pixel reconnect while enabled, permission
  denial/grant, Bluetooth off/on, stopping while waiting, and picker/settings
  behavior with the missing LE output. Worktree changes only; no commits.

## 2026-11-03 — Windows installer (red-win11 VM)

- `build.ps1` + PyInstaller (onedir) + Inno Setup 6 (per-user) produced
  `PassThru-Cale-Setup.exe` (31 MB). Fixed: build script required the `py`
  launcher, which a per-user Python install may not provide.
- Silent per-user install: exit 0, Start Menu shortcut, **no** firewall rule
  when the optional task is skipped (correct).
- Installed app launches and shows the "Cale · Desktop companion" window
  (screenshot reviewed). An earlier "no window" result was a test-harness
  artefact: `QT_QPA_PLATFORM=offscreen` from GUI test jobs persisted in the
  agent's PowerShell session and was inherited by launched apps.
- Not yet: interactive Start → pair → stream from the installed build, and the
  optional firewall task (needs an admin prompt in the VM).
- **Installed app, interactive (UI Automation clicks):** Start → "Listening";
  emulator Cade paired → Cale dialog showed code 799103 → Match → status
  "Streaming to 127.0.0.1 · latency 106.1 ms · buffer 30 ms" (via the
  loopback test connector; emulator debug speaker output).
- Found: Windows 11 first-listen firewall prompt (public+private or Cancel)
  appeared because the optional private rule had been skipped → installer task
  now on by default; README corrected. Rule creation itself not yet verified
  (needs the UAC prompt approved in the VM).
- Firewall verification: pre-existing "passthru cale.exe / Allow / Public"
  rules were found in the VM (Windows prompt had been answered Allow) and
  removed. First silent install created **no** rule (Inno `skipifsilent`) →
  fixed. Re-test interrupted: **lilred ran out of memory** (Windows VM 6 GB +
  emulator + build + desktop apps) → OOM killer killed the VM; emulator
  crashed. Pending: restart VM, verify Private-only rule.
- **Firewall rule verified (VM, after restart):** with the task selected,
  the installer creates exactly one rule — "PassThru Cale (Private)", Allow,
  Inbound, profile=Private, program = installed `PassThru Cale.exe`; no
  Public rule. Note: Inno Setup reuses the previous install's task choices
  (UsePreviousTasks), so a reinstall after an install with the task off keeps
  it off; the test used `/MERGETASKS=privatefirewall`.
- The VM agent now starts from the VM's Startup folder at login.

## 2026-11-03 — review fixes, Linux installer, Cade UX v2.1 (no phone)

- Full Codex code review → 8 findings verified and fixed (store locking,
  revoke cut-off from any process, frame deadlines, route recovery, Windows
  CLI pairing, pairing lifecycle, true-latency simulation ≤ 102 ms, sim in
  test suite). 78 Python tests incl. loopback TLS; Java tests; builds pass.
- Linux installer: install/run/uninstall verified in a throwaway HOME.
- Cade UX v2.1 walked through on the emulator: no permission prompt at
  launch; status ring; pairing code large; computer name "lilred" shown;
  playing state "Playing lilred on … · 109 ms delay"; light + dark themes.
  Comparison: the owner's local design archive (not in this repo)
- Not yet on hardware: the new UI on the Pixel, aid-connected-not-active state.

## 2026-11-03 — physical round 3 (Pixel 7 + the owner's aids, LE Audio)

- **New UI on hardware:** "Playing lilred on the owner's hearing aids · 166 ms
  delay" (aid name from Android, computer name from pairing). Issue found:
  landscape layout cuts off Stop/Details (needs scrolling or side-by-side).
- **Speech ear check:** six espeak-ng sentences from lilred's speakers via
  real capture; owner heard them in the aids with "very small delay vs
  speaker audio". Measured 195–235 ms during the check (two Wi-Fi underruns
  raised the target to 70 ms).
- **Aid disconnect/reconnect with the new UI:** owner confirmed expected
  behavior; session resumed automatically after ~74 s; afterwards 127 ms,
  target 30 ms, 0 underruns.
- Released: debug app uninstalled, timeout 60000 restored, stay_on 15.

## 2026-11-03 — reconnect notification, landscape, Rooted mode (emulators)

- Landscape: ring shrinks, sentence/Stop/Details visible (emulator).
- Reconnect notification merged (pure-policy tests); real reconnect flow on
  the Pixel still to confirm.
- Rooted mode on the rooted AVD `passthru-magisk-dev` (API 36): setup command
  `cmd appops set org.passthru.cade MEDIA_ROUTING_CONTROL allow` → allow,
  teardown → default. The image's AOSP `su` (root:shell 4750) is not app-
  executable, so Cade correctly hides Rooted mode. Magisk app present but
  Magisk inactive on this boot (not revived, per root policy). Automatic aid
  transfer unverified (needs rooted phone + LE Audio aids).

## 2026-11-03 — physical round 4 (Pixel 7 + the owner's aids, LE Audio)

- **First-use bug found and fixed:** with no saved aid, aids connected but
  not the media output showed "Waiting for your hearing aids…". Fixed
  `AidAvailability.decide`: with nothing saved, any hearing output is
  AVAILABLE and any connected aid is CONNECTED_NOT_ACTIVE (test added).
- **Output picker flow on hardware:** "Your hearing aids are connected — tap
  to use them" → Choose output → aids → Start; the owner heard it at about
  180 ms.
- **Aid disconnect/reconnect while playing:** Android gave media back to the
  aids itself and Cade resumed automatically, so no notification was needed.
  The notification path (Android not giving media back) is still unconfirmed
  on hardware. After the reconnect: 211–231 ms, target 80 ms (4 underruns),
  decaying.
- Released: debug app uninstalled, timeout 60000 restored, stay_on 15.
