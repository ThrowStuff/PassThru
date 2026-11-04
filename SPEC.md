# PassThru — design spec (v2 restart)

SPDX-License-Identifier: GPL-3.0-only

## Problem

ASHA and LE Audio hearing aids talk to Android phones well, but most other
devices (Windows PCs, TVs, consoles, Macs) cannot drive them. Moving the aids
between devices means painful re-pairing. PassThru makes the phone the
adapter: the aids stay paired to the phone, and other devices send their audio
through the phone.

## Goals, in priority order

1. **Low latency:** at most 100 ms added by PassThru (desktop capture to Android
   playback), measured and shown to the user, not assumed.
2. **Low friction:** pair once, then it just reconnects. No re-entering
   addresses, tokens, or fingerprints.
3. **Broad aid compatibility:** any aid Android exposes as an ASHA
   (`TYPE_HEARING_AID`) or LE Audio (`TYPE_BLE_HEADSET`, API 37 `TYPE_BLE_HEARING_AID`)
   output. Classic-Bluetooth aids (e.g. Phonak) are out of scope: they already
   pair with PCs directly.
4. **Small, reviewable code.** The live audio path must stay readable in one sitting.

## Non-goals (for now)

- Microphone/uplink (aid mics back to the PC). Deferred.
- Raw-Bluetooth A2DP Sink mode (phone appears as a Bluetooth speaker). Needs a
  rooted phone or system app; later, optional, for devices that cannot run software.
- Android TV / generic Bluetooth-over-IP. Later subproject.
- Remote control of aid programs/volume.

## Components

| Name | Runs on | Role |
|---|---|---|
| **Cale** | Linux, then Windows 11 | Captures system audio output, sends it to the phone |
| **Cade** | Android phone (API 29+) | Receives audio, plays it to the user-selected hearing aid |

## Audio format and framing

- PCM signed 16-bit little-endian, **48 kHz, stereo** (aids are binaural; Android
  handles conversion to the aid's codec). 10 ms per frame = 480 samples ×
  2 channels × 2 bytes = **1920 bytes**.
- Transport: TCP over TLS 1.3, `TCP_NODELAY`, small socket buffers
  (`SO_SNDBUF` = 4096 on Cale, `SO_RCVBUF` = 8192 on Cade; Linux doubles the
  value, so ~8 KiB ≈ 43 ms on the send side), so backlog cannot hide in the kernel.
- Timeouts: TLS handshake 5 s. Socket writes 1 s (a stall longer than that
  ends the session and the receiver reconnects). Reads 2 s once a frame has
  started. Never use sub-100 ms socket timeouts: Wi-Fi hiccups are normal.
- **One thread owns all TLS I/O on a connection** (reads and writes). Python
  and OpenSSL do not allow concurrent recv/send on one TLS socket
  (TLS re-keying couples them). Cale's session loop: send next frame; then, if
  `conn.pending()` or `select(...,0)` says readable, read and handle one
  control frame.
- Frame (after TLS): fixed 20-byte header + payload.

```
offset size field
0      2    magic 0x5054 ("PT")
2      1    version = 2
3      1    type: 1=AUDIO, 2=PING, 3=PONG, 4=STATS, 5=BYE
4      4    sequence (u32, increments per AUDIO frame)
8      8    sender monotonic time, microseconds (u64)
16     4    payload length in bytes (u32, ≤ 65536)
```
All integers big-endian. Unknown types are ignored; bad magic/version/length
closes the connection.

- PING (Cade → Cale): sequence = ping id, timestamp = Cade send time t0,
  empty payload. PONG (Cale → Cade): sequence = same ping id, timestamp =
  Cale time t1 when the PING was read, payload = 8-byte big-endian t0 echoed.
  Cade records arrival t2: RTT = t2 − t0; offset = t1 − (t0 + t2)/2. Use the
  median of the last 8. One PING per second.
- STATS (Cade → Cale, once per second, JSON payload): buffer occupancy ms,
  underruns, frames dropped (late or overflow), estimated added latency ms.

## Latency design (the core)

**Cale (sender):**
- Capture in 10 ms blocks (Linux: `pw-cat --record --latency 10ms` on the
  default sink's monitor, or a user-selected sink).
- Bounded queue of **3 frames**; drop oldest on overflow. Never block capture.
- **Silence fill:** if capture delivers no frame for 100 ms (PipeWire quanta of ~21 ms are normal gaps) (e.g. PipeWire
  suspended the idle sink), send a silent frame so the stream never stalls;
  sequence and timestamps stay continuous. Real frames resume immediately.
- Sender thread writes frames immediately; if a write would stall > 30 ms, drop
  queued frames rather than accumulate.

**Cade (receiver):**
- **Network reader thread** reads frames as fast as they arrive and pushes them
  into a **jitter buffer**. Reading never waits on playback.
- **Jitter buffer:** target 30 ms, hard max 80 ms. Above max → drop oldest
  frames down to target. Below 0 (underrun) → play silence and count it.
- **Underrun:** play silence, count one underrun, then *rebuffer*: keep
  playing silence until occupancy reaches target (30 ms) again.
- **Drift control:** keep a smoothed occupancy `ema` updated on every pop
  (`ema += 0.01 × (occupancy − ema)`, ≈1 s time constant). If `ema` > target
  + 10 ms and ≥ 1 s since the last correction, drop one frame; if `ema` <
  target − 10 ms and ≥ 1 s since the last correction, repeat one frame. Max
  correction rate 1 % (enough for ±0.5 % clock drift). Count overflow drops,
  drift drops and drift repeats separately. Instantaneous occupancy must never
  drive drift decisions (network jitter would reset them).
- **Radio/CPU:** while a session is active hold a `WIFI_MODE_FULL_LOW_LATENCY`
  Wi-Fi lock (disables Wi-Fi power save batching) and a partial wake lock;
  release both on stop.
- **Route checks** must not run per frame: device presence comes from
  `AudioDeviceCallback`; `getRoutedDevice()` is polled at most every 100 ms.
- **Playback thread:** `AudioTrack` with `PERFORMANCE_MODE_LOW_LATENCY`,
  buffer = smallest the device allows (start at `getMinBufferSize`, reduce with
  `setBufferSizeInFrames` toward 2× burst), `setPreferredDevice(selected aid)`.
- **Added latency estimate** = jitter-buffer occupancy + AudioTrack buffered
  frames (from `getTimestamp`) + one-way network estimate. Shown in the UI.

## Safety rules carried over from v1 (these were good)

- Play only to the hearing-aid output the user explicitly chose. Never fall
  back to the phone speaker or another device.
- Start muted; unmute only after `getRoutedDevice()` confirms the chosen aid.
  Stop if the route is lost.
- TLS with the desktop certificate pinned by fingerprint; no plaintext audio.
- Never touch hearing-aid bonds or Bluetooth pairing.

## Pairing and reconnect (friction)

**Cale streams audio only to paired, authenticated phones.** An unauthenticated
connection gets no AUDIO frames.

Discovery: Cale advertises `_passthru._tcp` on its port via python-zeroconf
on all non-loopback IPv4 interfaces (Linux, Windows, macOS). If zeroconf is
not importable, Linux alone falls back to `avahi-publish-service`. TXT record
`fp=<first 16 hex chars of cert SHA-256>`, `name=<hostname>`. Cade browses
with `NsdManager`.

Additional frame types (Cade→Cale unless noted):

| type | name | payload |
|---|---|---|
| 6 | PAIR_COMMIT | 32 bytes: SHA-256(nc), nc = 32 random bytes from Cade |
| 7 | PAIR_NONCE (Cale→Cade) | 32 bytes: ns, random from Cale |
| 8 | PAIR_REVEAL | 32 bytes: nc |
| 9 | PAIR_CONFIRM | 1 byte: 1 = user said codes match, 0 = mismatch |
| 10 | PAIR_KEY (Cale→Cade) | 32-byte device key (random) + UTF-8 hostname (≤ 64 bytes, truncated on a UTF-8 boundary), sent only after BOTH sides confirmed; Cade also accepts legacy key-only 32-byte payloads |
| 11 | AUTH | 32 bytes: device key |
| 12 | AUTH_OK (Cale→Cade) | empty |
| 13 | AUTH_FAIL / PAIR_FAIL (Cale→Cade) | UTF-8 reason; Cale closes after sending |

First pairing (numeric comparison, as in Bluetooth SSP / ZRTP SAS):
1. Cade connects with TLS, *without* a pin, and records the server cert's
   SHA-256 `fp`. Sends PAIR_COMMIT.
2. Cale replies PAIR_NONCE (only after receiving the commit).
3. Cade sends PAIR_REVEAL; Cale checks SHA-256(nc) == commit, else PAIR_FAIL.
4. Both compute `code = int.from_bytes(SHA-256("PTPAIR" || fp || nc || ns)[:4], "big") % 1_000_000`,
   shown as 6 digits. Cale's fp is its own cert's fingerprint.
5. Both screens show the code; the user confirms on each device. Cade sends
   PAIR_CONFIRM; Cale waits for its own local confirmation too (timeout 120 s).
6. If both confirmed, Cale generates and stores a device key, sends PAIR_KEY.
   Cade stores {fp (pin), key, name, last host/port}. Then the session
   continues as a normal authenticated stream.

Later connections: Cade pins `fp`, sends AUTH first; Cale checks the key
(constant-time compare) against its store, replies AUTH_OK and starts AUDIO,
or AUTH_FAIL and closes. 3 failed AUTH/PAIR attempts from one IP within 60 s →
reject that IP for 60 s. Only one pairing attempt at a time.

Stores: Cale `~/.config/passthru/` (`cert.pem`, `key.pem` mode 0600,
`devices.json` mode 0600: list of {id, name, key_sha256, paired_at}); store a
SHA-256 of each device key, not the key. Cade: app-private SharedPreferences.

Reconnect: once paired and the user has pressed Start, Cade keeps the relay
"enabled": it finds the computer by mDNS (matching TXT fp to the pin), falls
back to the last host/port, and reconnects with backoff 0.5 s → 5 s. Survives
Cale restarts with no user input. Manual host:port entry stays available.
Cade lists paired computers and can forget one; Cale CLI can list/revoke devices.

## Testing strategy

- **Unit tests** for framing, jitter buffer, drift control — with injected
  clocks, no sleeps.
- **Synthetic drift simulation (host only, simulated time):** 10 minutes per
  scenario, receiver clock 0.5 % slow and 0.5 % fast, seeded network jitter
  0–15 ms. Pass criteria, all required:
  - no-stall scenarios: max added latency ≤ 80 ms; **zero underruns and zero
    overflow drops after the first 5 s**; drift corrections between 0.5× and
    2× the theoretical count (0.5 % of 60 000 frames = 300).
  - stall scenario (extra 50 ms delay every 17 s): max ≤ 100 ms; underruns
    ≤ 2 per injected stall.
  Underruns are audible dropouts: a run that bounds latency by underrunning fails.
- **Emulator:** Cade on AVD `red-android16` (`emulator-5580`, headless) for
  install, UI, pairing, receive and stats against a real Cale on the host.
  Does not prove hearing-aid behavior.
- **Physical:** the owner's ASHA-capable hearing aids on the Pixel 7, tested over
  LE Audio; ASHA-mode is untested. Only with the owner's explicit go-ahead.
  Measures real end-to-end delay.

## Project layout

```
cale/            Python package (desktop)
  cale/protocol.py   framing
  cale/capture.py    audio capture backends
  cale/sender.py     queue + TLS server + stats
  cale/pairing.py    mDNS + pairing code + key store
  cale/__main__.py   CLI (GUI later)
  tests/
cade/            Android app (Java, Gradle)
  app/src/main/java/org/passthru/cade/
    Protocol.java, JitterBuffer.java, Receiver.java, Player.java,
    Pairing.java, MainActivity.java
  app/src/test/...
tools/sim/       synthetic end-to-end harness
```

## Phases

1. Protocol, Cale sender (Linux capture), jitter buffer reference, synthetic
   drift test. **Exit:** synthetic test passes the latency bound.
2. Cade receiver app (no pairing yet: manual host + fingerprint), stats screen.
   **Exit:** emulator plays a tone from Cale; stats show ≤ 100 ms.
3. Pairing + mDNS + auto-reconnect. **Exit:** emulator pairs once, reconnects
   after Cale restart with no user input.
4. Physical test on the owner's aids with the Pixel (owner present).
5. Windows Cale (WASAPI loopback) and packaging.
Later: microphone uplink; rooted A2DP Sink mode; Android TV.

## Design language (reuse from v1)

The UI look, icons and theme from v1 are kept; only the code is new. Sources:
- the owner's local design archive (not in this repo) (Cade/Cale appearance and controls
  mockups, `theme-direction.json`, `cale-theme-direction.json`, final candidates)
- the owner's local design archive (not in this repo) (app icons)
- the owner's local design archive (not in this repo) (notification mockups and UI review specs)
- v1 Android resources: the archived v1 repo (not published)
Copy assets/resources in when building UI (Phase 2+); do not regenerate art.
Any new art is generated by Codex, not hand-drawn in code.

## Open research

- **Android 17 (API 37) Bluetooth audio peripheral mode.** `AudioDeviceInfo.TYPE_BLE_CENTRAL`
  (34) / `TYPE_BLE_CENTRAL_BROADCAST` (35) describe "an Android implementation
  operating in a Bluetooth audio peripheral mode (LE Audio, A2DP or HFP
  profiles)". Find out whether a normal app can enable/use this role (no root)
  and which devices ship it. If yes, raw-Bluetooth mode may become rootless on
  API 37 devices and move up in priority.
- Confirmed constants (developer.android.com, 2026-11-03): TYPE_HEARING_AID=23
  (API 28), TYPE_BLE_HEADSET=26 (API 31), TYPE_BLE_HEARING_AID=33 (API 37).

### Connected hearing aids without an audio output (2026-11-03)

Research after physical round 2; no device commands or playback used:

- **High confidence: connection and activation are separate.** AOSP
  `ActiveDeviceManager.handleHapConnected` queues an LE hearing aid when LE
  Audio is not yet connected. `handleLeAudioConnected` checks stream readiness
  before activation; `setLeHearingAidActiveDevice` removes the pending entry
  after successful activation. The separate `mPendingActiveDevice` handles
  classic A2DP/HFP connection synchronization, so it must not be confused with
  the LE hearing-aid pending list. See
  [AOSP ActiveDeviceManager](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/btservice/ActiveDeviceManager.java).
  **Medium confidence for the Pixel diagnosis:** this is consistent with a
  connected aid without an exposed output, but the exact reported
  `BluetoothActiveDeviceManager` / “Pending active device” log was not located
  in the retrieved source. The Pixel's precise Bluetooth module revision was
  not supplied; do not infer the exact blocker from that log alone.
- **No affirmative evidence found that starting a USAGE_MEDIA AudioTrack
  activates this pending device.** The inspected activation path uses profile
  events, not a media-playback callback. This is not proof about every Pixel
  module revision. LE group selection and exposing its output also involve
  `LeAudioService.setActiveDevice`, `setActiveGroupWithDevice`, and
  `handleAudioDeviceAdded`; merely being connected is insufficient. See
  [AOSP LeAudioService](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/le_audio/LeAudioService.java).
  Playback triggering remains unproven; no probe track or silence is created
  while the selected output is absent.
- **High confidence: public detection.** Obtain profile proxies with
  [BluetoothAdapter.getProfileProxy](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getProfileProxy(android.content.Context,%20android.bluetooth.BluetoothProfile.ServiceListener,%20int)),
  use `BluetoothProfile.HEARING_AID` / `BluetoothHearingAid` (API 29) and
  `BluetoothProfile.LE_AUDIO` / `BluetoothLeAudio` (API 31), then call
  `getConnectedDevices`. Each LE pair member can appear separately. Compare
  address/name with the saved identity and compare against AudioManager
  outputs separately. Bonded alone is not connected; HAP alone does not prove
  media readiness. Android 12+ requires runtime `BLUETOOTH_CONNECT`; API 29–30
  uses manifest `BLUETOOTH`. See
  [LE Audio API](https://developer.android.com/reference/android/bluetooth/BluetoothLeAudio) and
  [Hearing Aid API](https://developer.android.com/reference/android/bluetooth/BluetoothHearingAid).
- **High confidence: no public Bluetooth activation API for ordinary apps.**
  `BluetoothAdapter.setActiveDevice` is hidden `@SystemApi`, requiring
  `BLUETOOTH_CONNECT`, `BLUETOOTH_PRIVILEGED`, and `MODIFY_PHONE_STATE` in
  [AOSP BluetoothAdapter](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/57b823f4f7/framework/java/android/bluetooth/BluetoothAdapter.java).
  Connection permission does not grant activation privileges.
- **High confidence: public user destinations.** On API 34+,
  [MediaRouter2.showSystemOutputSwitcher](https://developer.android.com/reference/android/media/MediaRouter2#showSystemOutputSwitcher())
  opens the system picker from a visible foreground Activity, returns whether
  it opened, and needs no new dependency. This delegates choice to the user;
  it does not promise repair of a missing LE output. On older versions, failure,
  or refusal, use
  [Settings.ACTION_BLUETOOTH_SETTINGS](https://developer.android.com/reference/android/provider/Settings#ACTION_BLUETOOTH_SETTINGS).
  A separate Bluetooth settings button remains available even when the picker
  opens, so the owner can reach the device's LE Audio/media controls.
  [Settings.Panel.ACTION_VOLUME](https://developer.android.com/reference/android/provider/Settings.Panel#ACTION_VOLUME)
  promises volume streams, not an output picker. There is no documented public
  per-device Bluetooth details destination in the Settings API; OEM component
  names, fragment extras, and private media-output intents are not a portable
  contract. **Medium confidence** that user selection fixes this particular
  reconnect: only physical verification can establish it.

Implementation policy: `AidAvailability` takes plain type/address/name values.
An exact saved-type match with either non-empty address or exact product name
in outputs means AVAILABLE (route confirmation still belongs to Player).
Only a matching bonded device connected on the corresponding profile, without
that output, means CONNECTED_NOT_ACTIVE. Otherwise NOT_CONNECTED means “no
positive connection observation,” including denied permission, unavailable
proxy, unsupported profile, or Bluetooth off; it is not proof of radio absence.
Name fallback retains the existing pair-member behavior and can be ambiguous
for identically named devices; it never grants permission to play without the
existing route confirmation.

While waiting, the service reads profile snapshots every 2 seconds on the main
thread, outside the audio path, and stops polling when the wait ends. Proxy
creation is asynchronous, so the initial positive report can be delayed. The
UI and ongoing notification show “<name> connected but not set for media — tap
to choose output”. Output-add callbacks alone retain the existing resume gate;
profile presence never starts playback. No bonds, Bluetooth settings, or routes
are changed by this feature and no alternative output is selected.

## Adaptive jitter target (added after the 2026-11-03 Pixel screen-off run)

A single Wi-Fi delivery spike longer than the buffer causes audible underruns.
The jitter target therefore adapts, learning from the size of each gap:
- Start at 30 ms. When an underrun begins, remember the target `T0` and count
  the silent pops `n` of that underrun episode (the first silent pop counts).
  When rebuffering ends, set target = min(100, T0 + 10·n + 10) — i.e. enough
  to have covered that gap plus a 10 ms margin. One spike teaches the buffer.
- Every 30 s window with no underrun, lower the target by 10 ms (min 30 ms)
  **only if occupancy never fell below 30 ms during that window** (10 ms step
  + 20 ms jitter margin). Near-misses block the decay; otherwise the target
  shrinks under spikes that it is currently absorbing and they underrun again.
- Overflow cap = target + 50 ms (trim down to target). Drift thresholds stay
  ±10 ms around the current target. Rebuffer refills to the current target.
- The initial fill is not an underrun (no count, no learning). For 2 s after
  it, underruns are counted but do not raise the target: they come from
  session setup (track priming, buffer tuning), not the network.
- Frames that pile up in the socket while the phone builds its AudioTrack
  arrive as a burst and are trimmed by the overflow cap (stale audio is
  dropped, not played late) — expected startup overflow drops.
- STATS adds `target_ms`.
- Python reference and Java port must stay rule-for-rule identical.
- Simulation scenario "adaptive": 60 ms extra delivery delay every 10 s for 3
  minutes, then 3 minutes clean (receiver clock 0.5 % slow). Pass: at most 2
  underrun events during the spike phase and **none after its first 60 s**;
  target back to 30 ms by the end; max added latency ≤ 150 ms. All existing
  scenarios must still pass with their existing criteria.

## Cale desktop app (GUI)

PySide6 (Qt 6, LGPL — GPL-3.0 compatible), installed offline from
`/var/home/User/.local/share/passthru-build/cale-declared-wheelhouse` into
`cale/.venv`. Launch: `python -m cale --gui` (CLI stays).
- One small window: status line (Stopped / Listening / Streaming to <phone> ·
  latency N ms · buffer N ms, from STATS), Start/Stop, audio source (default
  output = "System audio", or a specific output sink listed via
  `pactl -f json list sinks`, falling back to plain `pactl list short sinks`).
- **Pairing dialog:** shows the 6-digit code large, "Match" / "Doesn't
  match"; the sender's `confirm(code)` callback blocks its worker thread and is
  answered from the Qt thread via a signal + Event (never touch widgets off the
  Qt thread). Times out with the sender's 120 s limit.
- Paired phones list with Revoke.
- Closing the window keeps streaming only if a tray icon is available
  (QSystemTrayIcon.isSystemTrayAvailable()); otherwise closing stops Cale.
- Theme: dark "Quiet Amber", light white (#FFFFFF, #F6F7F9, border #D9DDE3)
  with amber accents; follow the system color scheme. See
  `cale-appearance-0{1,2}.png` and `cale-controls-candidate-01.png` in
  the owner's local design archive (not in this repo). Design docs are guidance, not binding.
- Tests run offscreen (`QT_QPA_PLATFORM=offscreen`), never on the desktop.
- `cale/packaging/passthru-cale.desktop` (not installed automatically).

## Hearing-aid identity and auto-resume (review finding, 2026-11-03)

- `AudioDeviceInfo.getId()` changes whenever the aid reconnects. Persist the
  selected output as {type, address (`getAddress()`), product name}; match on
  the same type AND (same non-empty address OR same non-empty product name).
  An LE Audio pair can report either aid's address depending on which one
  connected first, so address alone is not stable.
- Route loss still stops playback immediately (mute, no fallback). If the
  relay is enabled and the *same* aid reappears (AudioDeviceCallback added),
  resume automatically; show "Waiting for <aid name>" meanwhile. Any other
  output never resumes playback.
- If connecting to the mDNS-resolved address fails, try the last known
  host/port in the same attempt before backing off.

## Windows capture (verified in the red-win11 VM, 2026-11-03)

- WASAPI loopback of the default render endpoint via **PyAudioWPatch**
  (Apache-2.0, GPL-3.0 compatible); float32 → s16, channel fold, streaming
  linear resampler when the mix rate is not 48 kHz.
- WASAPI loopback delivers **nothing** while no app plays, which the phone
  would see as network gaps (and grow its jitter target). Cale keeps a silent
  render stream open on the same endpoint so loopback runs continuously on
  the device clock.
- Status flags / queue overflow are counted glitches, never a stop.
- Silence fill is paced by due time (Windows timers wake ~15.6 ms late).
- Certificates are generated with `cryptography` (ECDSA P-256, 20-year
  validity; the phone pins the fingerprint). `openssl` CLI is a fallback.
- Store path on Windows: `%APPDATA%\PassThru`.
- mDNS uses python-zeroconf; manual host entry remains available.
- Windows 11's first-listen firewall prompt has no private-only choice
  (Allow = public + private; Cancel = block rules). The installer's
  private-only rule is therefore **on by default** so the prompt never appears.
  The per-user installer optionally elevates netsh for an executable-scoped,
  Private-profile inbound rule; it never opens the Public profile.

## Aids connected but not the media output (owner decision 2026-11-03)

When the saved aid is connected over Bluetooth (LE Audio / hearing-aid
profile) but Android has not made it an audio output ("pending active
device"), Cade shows "Your hearing aids are connected — tap to use them".
The tap opens Android's own output picker via
`MediaRouter2.showSystemOutputSwitcher()` (API 34+; fallback: Bluetooth
settings on older releases). The user picks the aids there; Cade then
resumes automatically. Cade never changes the active device or routes by
itself and never plays to another output. Owner: "that's close enough".

## Root support policy (owner, 2026-11-03)

Unrooted phones are the primary target. Root is an optional extra: if `su`
is already available and the user grants it, Cade may use simple one-shot
root shell commands for quality-of-life controls (e.g. skipping the output
picker). No Magisk modules, no privileged-app installs, no allowlist XML, no
system-partition changes — the v1 Magisk/priv-app approach was over-built.
If a QoL feature needs more than a plain `su -c <command>`, don't do it; the
unrooted path (one-tap system picker) stays the fallback everywhere.

### Rooted skip-the-picker: findings (2026-11-03, Codex research, reviewed)

- `BluetoothAdapter.setActiveDevice` needs BLUETOOTH_PRIVILEGED +
  MODIFY_PHONE_STATE *and* hidden-API exemption (platform signature or
  system app with usesNonSdkApi) → requires Magisk priv-app work → **dropped**
  per root policy.
- No AOSP shell command (`cmd bluetooth_manager`, `cmd audio`, media router)
  sets the active Bluetooth device; raw `service call` recipes are fragile.
- **Only candidate within policy (untested):** `su -c 'cmd appops set
  org.passthru.cade MEDIA_ROUTING_CONTROL allow'` (signature|appop), then the
  public proxy `MediaRouter2.getInstance(context, <pkg>, …)` / `transferTo(route)`
  to move playback to the aids' system route without the picker. Works only
  if the pending aid is exposed as a transferable route and the build honors
  the app-op. Needs a rooted physical phone with LE Audio aids to test.
  Everyone else keeps the one-tap picker.

## Cade UX (v2.1, from the 2026-11-03 emulator walkthrough)

Problems seen: permission dialog before any explanation; jargon ("Cale host",
"relay", "underruns"); Start enabled with nothing paired; two Start buttons
after pairing; manual form stays open; small pairing code with no
instruction; computer shown as "MANUAL COMPUTER"; design language only partly
applied (all-grey buttons).

One screen, built around a **status ring** (design reference: "plasma circle"
in the owner's local design archive (not in this repo):
grey dim = not connected, violet rotating = connecting, violet steady =
playing; plus amber = needs attention). Under the ring, one sentence and at
most one primary action:

| State | Sentence | Primary action |
|---|---|---|
| No computer paired, none found | "Open Cale on your computer — it will appear here." (spinner while searching) | — ; small link "Enter address instead" |
| Computer found, not paired | "<Computer name> is ready to pair." | **Pair** |
| Paired, stopped | "Ready to play <Computer name> on <aid name>." | **Start** |
| Connecting / reconnecting | "Connecting to <Computer name>…" | Stop |
| Playing | "Playing <Computer name> on <aid name> · <N> ms delay" | **Stop** |
| Aid connected, not media output | "Your hearing aids are connected — tap to use them." | **Choose output** (system picker) |
| Aid not connected | "Waiting for <aid name>…" | — |
| Computer unreachable | "Can't reach <Computer name>. Retrying…" | Stop |

- Primary buttons use the theme accent (violet, with icon); secondary actions
  are text buttons. Exactly one Start/Stop control on screen.
- Hearing-aid output picker only when more than one eligible output exists;
  otherwise show the aid's name.
- **Details** (collapsed by default): latency, buffer, target, underruns,
  drops, computer address. Technical words live only here.
- "Paired computers" in an overflow menu: rename? no — list with Forget.
- Manual address: separate small sheet/dialog ("Computer address", "Port"),
  closes after pairing starts.
- **Pairing dialog:** "Check that <Computer name> shows the same code", the
  code large (≥ 40 sp, spaced digits), buttons "Codes match" / "Don't match".
- **Permissions:** request Nearby devices / notifications only when first
  needed, after a one-line in-app explanation ("Cade needs Nearby devices to
  recognise your hearing aids").
- **Computer name:** PAIR_KEY payload becomes 32-byte key + UTF-8 computer
  name (≤ 64 bytes); Cade stores and shows it. Old 32-byte payloads accepted.
- Dark ("Quiet Violet") and light ("Paper & Ink") themes as now; accessible
  contrast; text sizes respect system font scale.

### Reconnect prompt as a notification (owner feedback, 2026-11-03 round 3)

On the Pixel, after the aids reconnected, Android left them "pending" and the
owner used Cade's "tap to use them" → system picker path (designed path, two
taps). Improvement: when the relay is enabled and the state enters
CONNECTED_NOT_ACTIVE, post a one-shot, high-visibility notification on a
separate "Needs attention" channel: "Hearing aids connected — tap to use
them". Tapping opens MainActivity with an extra that immediately opens the
system output picker (same `openOutputSettings()` path). Cancel the
notification when the aids become AVAILABLE or the relay stops. At most one
such notification per reconnect.

### Landscape layout (round 3)

In landscape the ring dominated and cut off Stop/Details. Make the main
screen scroll, and in landscape place the ring left and the sentence/actions
right (or shrink the ring to ≤ 40 % of the shorter screen side).

## Rooted mode (owner, 2026-11-03)

*Priority: small add-on on top of the main project. No rooted physical device is available; features stay marked unverified and no further root work is planned unless one becomes available.*

A single "Rooted mode" switch, shown only when `su` is available. It is the
home for every root quality-of-life feature, now and later ("anything we
find along the way falls into it").
- UI: switch label "Rooted mode" with the one-line description "Rooted mode
  offers a few small quality-of-life improvements." (owner's wording), then a
  short list of the active items, one line each (e.g. "Reconnects your hearing
  aids automatically — no need to pick them again").
- Turning it on: request root once, run the one-time setup commands (plain
  `su -c …` only), and record which features are active; turning it off
  reverts the setup where possible.
- Each feature inside must have a non-root fallback; failure of a root step
  falls back silently and is shown under Details.
- Features (initial):
  1. **Automatic aid reconnect** — setup: `cmd appops set org.passthru.cade
     MEDIA_ROUTING_CONTROL allow`; on CONNECTED_NOT_ACTIVE, transfer media
     to the aids' system route via the MediaRouter2 proxy API without the
     picker. Fallback: the reconnect notification. Unverified until tested
     on a rooted phone with LE Audio aids.
- New root shortcuts are added as entries here, never as separate switches.
