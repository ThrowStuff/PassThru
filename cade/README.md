<!-- SPDX-License-Identifier: GPL-3.0-only -->
# Cade — PassThru v2 Android receiver

Phase 3 adds numeric-comparison pairing, a paired/discovered computer list,
framework NSD discovery and authenticated reconnect. Choose Pair, compare the
six-digit code on both devices, and confirm on each. Start a paired computer
to enable reconnect; Stop disables it. Add manually exposes host/port entry but
still requires pairing. Forget removes the stored pin and key.

From `cade/`, using the existing offline cache at the default toolchain location
(`/home/User/.local/share/passthru-build/`):

```sh
JAVA_HOME=/home/User/.local/share/passthru-build/toolchains/jdk-17.0.21+9 \
ANDROID_HOME=/home/User/.local/share/passthru-build/sdk \
GRADLE_USER_HOME=/home/User/.local/share/passthru-build/gradle-user-home \
./gradlew --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Gradle wrapper copied unchanged from v1 (8.11.1). AGP 8.9.2, SDK 35, minimum
29, Java 17. JUnit 4.13.2 is the only declared dependency; no AndroidX/Kotlin.
The wrapper retains its upstream license notices.

Select a connected hearing aid before Start. Bluetooth name permission and
Android 13 notification permission are requested when applicable; denied name
permission uses a device-ID label. Debug builds retain the test output and
`autostart` intent extra with host/port; stored pairing is required. Debug-only
`pair_confirm=true` also accepts the numeric code for headless tests. Release
builds ignore these extras. Cale still requires its own confirmation.

Reconnect matches the advertised 16-hex fingerprint prefix, then authenticates
the full certificate pin and device key. It falls back to the last successful
host/port and persists enabled state for sticky service recreation. Stop or
route loss disables it. A service recreation still requires the selected
Android output ID to be present.

Playback remains muted until Android confirms the selected route. Route change,
removal or failure to confirm within two seconds ends playback with `route lost`;
Start is required again. A network failure creates a fresh buffer/player and retries
at 500, 1000, 2000, 4000, then 5000 ms; ten seconds connected resets the backoff.
Stop is also available in the foreground notification.

## Protocol and implementation decisions

`SPEC.md` governs format; `cale/cale/protocol.py` and `jitter.py` govern exact
framing and jitter rules. The golden vector was computed with the committed Python:

```python
encode(Frame(1, 0x12345678, 0x123456789abcdef0, b'abc')).hex()
# 5054020112345678123456789abcdef000000003616263
```

Java's `long` stores the timestamp's unsigned 64-bit **bits**, so negative Java
values represent the upper half of u64; there is no Java long outside u64's bit
range. Sequence is a range-checked positive long. Unknown message types remain
valid. Clean EOF returns null; partial EOF rejects the frame. Malformed PCM/PONG
payload sizes terminate the session. The injected jitter clock returns nanoseconds.
The buffer starts consuming immediately, matching Python; only an underrun enters
rebuffering. All five jitter-specific cases from `test_phase1.py` are mirrored;
its sender queue, capture, TLS server and simulator tests belong to Cale.

One network thread owns all application TLS reads and writes. Pairing uses an
unpinned handshake only for numeric comparison; reconnect pins the full leaf
certificate SHA-256 and requires AUTH_OK before starting playback. The control
write watchdog closes the raw transport after one second.

The shared pairing golden vector uses raw fp/nc/ns bytes 00–1f, 20–3f and
40–5f respectively, producing `784550`. The protocol carries no phone name;
Cale stores the generic name "Phone". Successful pairing continues directly
following PAIR_KEY, without an extra AUTH_OK. Types 13 AUTH_FAIL/PAIR_FAIL are
aliases. Unexpected pairing messages fail closed.

Discovery holds a multicast lock while browsing, including in the foreground
service. For API 29–32 and Android 13 before T extensions 7, this is needed to
receive mDNS; newer foreground discovery gets automatic multicast reception
([NsdManager reference](https://developer.android.com/reference/android/net/nsd/NsdManager)).
The manifest declares INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE and
CHANGE_WIFI_MULTICAST_STATE. The last is a normal permission for enabling
multicast ([permission reference](https://developer.android.com/reference/android/Manifest.permission#CHANGE_WIFI_MULTICAST_STATE));
no location or nearby-device runtime permission is added for NSD on API 29–35.

RTT and offset are independent medians of the last eight matched PONG samples.
Latency is occupancy + unplayed AudioTrack frames from its timestamp + median
RTT/2. Before AudioTrack supplies a timestamp, UI shows an em dash and JSON sends
`latency_ms: null` rather than an invented measurement. No device/codec latency
beyond AudioTrack's timestamp is included.

## Appearance

System night mode selects Quiet Violet (candidate A) or Paper & Ink (candidate B).
Palette values were extracted from the approved candidate SVGs in
the owner's local design archive (not in this repo):
`candidate-a-dark.svg` and `candidate-b-white.svg`. Section cards, violet anchors,
neutral input controls and framework typography adapt that design to Phase 2.
The selected Cade icon assets are PNG illustrations, with no convertible SVG in
`icon-concepts`; the launcher uses a plain C placeholder adaptive vector. No new
art was generated.

## Verification and limits

The Phase 3 Gradle command was attempted, but its single-use daemon failed
before compilation: `java.net.SocketException: Operation not permitted` in
`TcpIncomingConnector.accept` while opening its server socket. This sandbox
cannot run the Gradle build; reviewer must run the command outside it.

Fallback checks use only the cached JDK, Android 35 SDK/build tools and JUnit jars:
`aapt2 compile` and `aapt2 link` validate resources/manifest; `javac` compiles all
main/test sources using the actual generated R class and android.jar; JUnitCore
runs the pure JVM tests (16 passed for Phase 3). No emulator, adb, Bluetooth or audio access was used.
Android routing, real TLS interoperability and the measured <=100 ms exit
criterion still require the authorized later runtime test. The manual resource
link uses a temporary manifest with a package attribute; Gradle obtains that from
its namespace normally. Temporary verification outputs are ignored under build/.

## Optional Rooted mode

The overflow entry appears only when an executable `su` is found. Detection
never runs it. The persisted switch requests root with a one-shot `su -c id`
only when enabled, sets MEDIA_ROUTING_CONTROL to allow, and verifies the app-op.
Disable resets it to default. Every command has a five-second timeout and
bounded captured output; no persistent root shell or system files are used.
`RootMode.FEATURES` owns feature names, descriptions and setup/teardown commands.
Setup failures leave the switch off, report one line, and attempt rollback.

Automatic aid reconnect attempts once on entering CONNECTED_NOT_ACTIVE while
the relay is enabled. API 35+ uses the package-specific proxy, obtains the system
controller's transferable routes, and accepts only a unique system route with
exact saved hearing-aid type and name. Missing/ambiguous routes, permission loss,
exceptions, transfer failures/timeouts, or a completed transfer without an aid
output all fall back to the existing one-shot reconnect notification. Details
records the result. Output-add recovery and Player's muted route confirmation
remain the only path to playback. There is no probe track or Bluetooth activation.

API references checked 2026-11-03 (Android 34–36):

- [MediaRouter2](https://developer.android.com/reference/android/media/MediaRouter2):
  `getInstance(Context, String, Executor, Runnable)` is public from API **35**;
  `getSystemController()` and `transferTo(MediaRoute2Info)` are API 30. The proxy
  requires MEDIA_CONTENT_CONTROL or MEDIA_ROUTING_CONTROL. API 34 falls back;
  no hidden two-argument proxy overload is used. API 36 uses the same signatures.
- [RoutingController.getTransferableRoutes()](https://developer.android.com/reference/android/media/MediaRouter2.RoutingController#getTransferableRoutes())
  is API **35**. Transfer completion/failure is asynchronous via TransferCallback.
- [MediaRoute2Info](https://developer.android.com/reference/android/media/MediaRoute2Info):
  TYPE_HEARING_AID (23) and TYPE_BLE_HEADSET (26) are API 34, matching the saved
  AudioDeviceInfo types. System routes alone are eligible; names are exact.
  Identically named aids remain an identity limitation of Android's public route
  metadata; multiple matching routes are rejected.

Rooted-mode verification: SDK 35 `javac` compilation of all sources, `aapt2`
resource/manifest linking, and all 56 JVM tests passed (including command escaping,
feature text, reconnect decisions/fallback, and route filtering). Gradle could
not start its daemon because this sandbox denies its server socket. No adb or
device commands were run. Superuser consent behavior, OEM app-op enforcement,
route exposure and real LE/ASHA activation remain unverified on rooted hardware.

## Release builds

Set `PASSTHRU_SIGNING` to a `keystore.properties` file kept outside the repo
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), then run
`./gradlew --offline :app:assembleRelease`. Without it the release APK is
unsigned. Debug-only test paths (test speaker output, autostart/pairing
extras) are compiled out of release builds. The project's key lives in
`~/.local/share/passthru-build/signing/` (0700/0600) and is never committed.
