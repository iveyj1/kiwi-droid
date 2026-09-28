# Proposal: minimal KiwiSDR Android client

> Implementation amendment (0.2.0): after successful initial listening, the user requested background/screen-off playback. This now uses a media playback foreground service, MediaSession notification with Stop, and active-listening CPU/Wi-Fi locks. The foreground-only exclusions below describe the original MVP, not the current app. See `README.md` and `docs/VALIDATION.md` for current behavior and test evidence.

> Implementation amendment (0.3.0): the user requested audible RSSI for cable troubleshooting. Independent radio mute and RSSI beeps now share the background audio service, with a default 10 dB/octave musical mapping and configurable mapping points/scales/sound parameters. See `docs/RSSI_SOUND.md`.

> Implementation amendment (0.5.1): transient audio-focus interruptions now silence output and restore it on focus gain while retaining the live connection; permanent loss and route disconnection still stop. Edit fields select all on focus entry, and CWN follows CW.

> Implementation amendment (0.5.0): per-mode lower/upper passband editing and SAM/SAU/SAL/SAS/IQ/CWN are now implemented, including stereo SND decoding and playback. The original IQ/stereo exclusions below are superseded for playback only, not raw capture/export. See `docs/MODES_AND_FILTERS.md`.

## Objective

Build a small native Android app for listening to a selected KiwiSDR receiver: choose a server, enter a frequency, step up/down, select a demodulation mode, play audio, and see live received signal strength (RSSI).

Use the desktop/terminal project at `~/kiwi` as the protocol and behavior reference, not as an Android runtime dependency. Connect directly to KiwiSDR; no intermediary server, embedded browser, Python runtime, or local demodulation is required.

## MVP scope and behavior

| Function | Proposed behavior |
|---|---|
| Select server | Dropdown of named, configured receiver URLs.<br>Ship a small editable list;<br>a settings screen supports add/edit/remove.<br>Only one receiver connection at a time. |
| Tune | Numeric text entry explicitly labeled **kHz**,<br>with Tune/keyboard action.<br>Support 1 Hz resolution (three decimals in kHz).<br>Reject invalid or out-of-range values<br>without changing the current tune. |
| Frequency<br>up/down | Two large buttons subtract/add the selected step.<br>Offer 10, 100, 1,000, and 5,000 Hz initially,<br>plus a configurable positive whole-Hz step.<br>Preserve the selected step. |
| Mode | AM, USB, LSB, and CW.<br>Apply a fixed suitable passband per mode;<br>no filter editor in the MVP. |
| Audio | Play connects and starts mono audio.<br>Stop releases audio and the receiver connection.<br>Use Android media volume. |
| Live RSSI | Numeric dBm value and a horizontal meter,<br>refreshed about five times per second<br>from incoming audio frames.<br>Show unavailable when stopped;<br>mark stale after two seconds without frames. |
| Persistence | Store server list, selected server, last frequency,<br>mode, and step locally.<br>Restore controls at launch,<br>but require Play before connecting. |

Server changes during playback stop the old session before starting the new one. Frequency and mode changes update the existing connection. Controls remain editable while stopped and take effect on the next Play. On connection failure, show a specific error and offer Retry; do not loop through automatic reconnects.

### Single-screen layout

```text
Server     [ Receiver name                    v ] [Settings]
Status     Connected
Frequency  [ 5000.000                 ] kHz [Tune]
           [   −   ]  Step [100 Hz v]  [   +   ]
Mode       [ AM v ]
           [ Play / Stop ]
RSSI       −82.4 dBm  [========------------]
```

Use accessible labels and touch targets. Keep typed-but-unsubmitted frequency text separate from the active tuning value. Do not describe a sent tune command as independently acknowledged by the receiver unless the protocol supplies that evidence.

### Explicit exclusions

No waterfall/spectrum, scanning, public-directory discovery, recording, IQ/stereo, ADPCM, decoding, advanced AGC/filter controls, sub-Hz tuning, or receiver administration. Password-protected receivers and time-limit bypass credentials are deferred; the first release targets receivers allowing ordinary no-password access.

The MVP is foreground-only: stop and release the receiver when the app genuinely enters the background or the screen locks, but survive activity recreation such as rotation. Background/lock-screen listening would be a separate increment using a media playback foreground service and notification.

## Reference material in `~/kiwi`

Paths below are relative to `~/kiwi`.

| Reference | What to carry forward |
|---|---|
| `docs/kiwi-protocol.md` | Browser-compatible `/VER` bootstrap,<br>SND framing, authentication, commands,<br>and known uncertainties. |
| `src/kiwi_client/`<br>`session_bootstrap.py` | Server-issued timestamp and<br>`/ws/kiwi/<ts>/SND` URL construction. |
| `src/kiwi_client/commands.py` | Authentication, rate acknowledgement,<br>tuning, AGC, compression,<br>and keepalive encoders. |
| `src/kiwi_client/protocol.py` | MSG and uncompressed mono SND parsing;<br>mixed-endian header and RSSI conversion. |
| `src/kiwi_client/live_play.py`<br>`src/kiwi_client/live_capture.py` | Setup sequencing, 30-second application<br>keepalive, failure handling,<br>and playback cleanup. |
| `docs/audio-pipeline.md`<br>`src/kiwi_client/audio.py` | PCM conversion, sequence gaps/wraparound,<br>and fixture-based verification. |
| `config.toml`<br>`docs/radio-parameters.md` | Receiver configuration, mode passbands,<br>step sizes, and CW frequency semantics. |
| `tests/fixtures/kiwi/` | Synthetic and captured messages<br>for deterministic Android-side tests. |

The configured receiver set includes `10.0.0.41:8073`, `10.0.0.42:8073`, `10.0.0.43:8073`, and `misdr.proxy.kiwisdr.com:8073`. These are candidate development entries, not verified available servers or universal production defaults. Private addresses require access to the corresponding LAN/VPN.

Port behavior and fixture expectations into Kotlin. Review source/fixture licensing before copying or distributing material, including anything from the vendored `kiwiclient/` reference. Some protocol notes retain historical open questions; executable code and fixture-backed observations should resolve ambiguities, followed by controlled live testing.

## Technical design

### Android stack

- Kotlin, Jetpack Compose, ViewModel/StateFlow, and coroutines.
- Proposed minimum Android version: Android 8.0 / API 26; target the current required SDK at implementation time.
- OkHttp for HTTP bootstrap and WebSocket transport.
- `AudioTrack` for streaming mono signed 16-bit PCM.
- DataStore for local configuration and last-used controls.
- `INTERNET` permission; no microphone or storage permission is needed.

Keep four small components: UI/state, session controller, pure protocol codec, and audio output. Own network/audio resources outside composables. Serialize session commands, and associate callbacks with a session generation so old receiver callbacks cannot update the new session.

### Session and protocol

1. Fetch `<receiver-base-url>/VER`, validate JSON, and preserve `ts` as an integer without floating-point conversion.
2. Open `/ws/kiwi/<ts>/SND` using `ws` or `wss` to match the configured HTTP/HTTPS endpoint. Do not open a waterfall connection: audio frames already carry RSSI.
3. Send `SET auth t=kiwi p=` and require successful authentication (`MSG badp=0`). Accept MSG in either text or binary WebSocket messages.
4. Collect `audio_rate` and `sample_rate` regardless of arrival order. Acknowledge the announced audio rate using `SET AR OK in=<audio_rate> out=<output-rate>`; validate the chosen Android output-rate strategy against the reference's 44100 Hz acknowledgement during the initial spike.
5. Apply the reference setup: squelch off, generator off, client identity, mode/frequency/passband, default AGC, and `SET compression=0`.
6. Send `SET keepalive` on a 30-second timer independent of UI rendering and audio writes. Do not rely on WebSocket pings as its replacement.
7. Decode incoming SND frames and send tuning changes over the same socket. On Stop, error, or server switch, cancel timers, flush/release audio, and close the socket.

Use explicit Disconnected, Connecting, Playing, and Error states, with bounded bootstrap/authentication/first-audio waits. Report busy (`too_busy`), authentication/policy rejection (`badp`), server down, timeout, malformed protocol, and network loss distinctly. Surface server redirects rather than automatically following them to another receiver.

### Tuning and mode mapping

Keep frequencies and steps as integer Hz internally; format protocol frequencies as locale-independent decimal kHz. Send:

```text
SET mod=<mode> low_cut=<Hz> high_cut=<Hz> freq=<kHz>
```

Proposed defaults match the current `~/kiwi/config.toml`:

| Mode | Low cut | High cut | Protocol frequency |
|---|---:|---:|---|
| AM | −5000 Hz | +5000 Hz | Displayed frequency |
| USB | 0 Hz | +3000 Hz | Displayed frequency |
| LSB | −3000 Hz | 0 Hz | Displayed frequency |
| CW | +600 Hz | +1000 Hz | Displayed frequency minus 800 Hz |

For CW, the displayed frequency denotes the wanted signal/passband center. For example, 335.000 kHz sends `freq=334.200` with the CW cuts above, centering the RF passband on 335.000 kHz. Avoid inheriting older example passbands whose midpoint differs from the offset.

Validate against receiver tuning limits when available; use a documented conventional 0–30 MHz fallback for the initial supported receivers. Validate the offset-adjusted command too. Coalesce rapid stepping so obsolete intermediate tuning commands do not accumulate.

### Audio and RSSI

A SND frame has a three-byte `SND` tag, followed by flags (one byte), sequence (unsigned 32-bit little-endian), S-meter (unsigned 16-bit big-endian), then PCM. Normal uncompressed, non-camping mono PCM is signed 16-bit big-endian. Decode to a `ShortArray` for AudioTrack, rather than writing network-order bytes directly.

Calculate `RSSI = 0.1 × smeter − 127`; display this as the receiver-reported dBm estimate, not a separately calibrated measurement. The synthetic reference fixture uses `smeter=850`, yielding `−42.0`.

Use the reported floating-point `sample_rate` as the audio timing source, not the handshake `audio_rate`: the captured fixture represents approximately 11999 Hz rather than exactly 12000 Hz. The initial spike must verify AudioTrack operation at the rounded source rate or provide resampling to a supported output rate, with matching rate acknowledgement. Watch queue occupancy during long runs for clock drift.

Use a bounded PCM queue and dedicated audio writer, with an initial target of 150–250 ms buffered audio. Rebuffer after underflow; discard obsolete queued audio after excessive backlog rather than allowing latency to grow indefinitely. Track sequence gaps and wraparound. Validate short headers/odd payload lengths and reject unsupported compression, stereo, or camping formats instead of emitting noise. Flush on session changes; use short startup/stop fades following the desktop client's approach.

At 12 kHz, 16-bit mono PCM costs about 192 kbit/s, or 86 MB/hour before protocol overhead. Display a mobile-data warning. ADPCM can reduce bandwidth later but adds codec/state complexity inappropriate for the MVP.

### Android lifecycle and transport security

Request audio focus before playback; stop on focus loss or headphone-disconnect events to avoid unexpected speaker output. Release resources on genuine backgrounding and process teardown where callbacks are available; persist controls independently of the connection.

Many Kiwi receivers expose plain HTTP/WebSocket. Android cleartext policy must explicitly permit the configured use case. With arbitrary user-added hosts, a broad application cleartext allowance may be necessary; constrain app-initiated connections to the selected configured endpoint and warn that traffic is unencrypted. Prefer HTTPS/WSS when available, retain normal certificate validation, and never silently downgrade TLS.

## Delivery plan

Indicative estimate: **10–15 engineering days for one Android developer**, assuming access to test receivers and devices. This includes foreground-only playback, not store publication or background playback.

1. **Protocol/audio spike — 2–3 days:** scaffold project, translate codec fixtures, bootstrap a receiver, verify audio rates and continuous PCM playback. Gate further work on demonstrated direct Android playback.
2. **Functional MVP — 4–5 days:** receiver list, tuning/stepping, four modes, Play/Stop, RSSI, persistence, and command serialization.
3. **Hardening and delivery — 4–7 days:** lifecycle/audio focus, errors, bounded buffering, device/network tests, documentation, and installable APK.

Deliver source, reproducible Gradle build instructions, debug APK, automated tests, a short user guide, and a record of tested receivers/devices and known limitations.

## Acceptance tests

- Fixture tests reproduce PCM extremes `[-32768, -1, 0, 1, 32767]` and RSSI `−42.0`; malformed, unsupported, sequence-gap, and wraparound cases are covered.
- Mock sessions exercise both MSG arrival orders, binary/text MSG, authentication failure, capacity limits, timeout, disconnect, and cancellation during connection setup.
- Frequency entry and repeated up/down operations are exact to 1 Hz; invalid input does not change tuning. Mode commands use the specified cuts and CW offset.
- Selecting another server never leaves two active receiver sessions or plays buffered audio from the old server.
- On a stable LAN, target first audio within five seconds of Play and audible tuning changes within 500 ms; measure and document exceptions rather than attributing all server/network delay to the app.
- RSSI follows received frames at approximately 5 Hz, becomes stale within two seconds of interruption, and clears on Stop.
- A 30-minute listening test has bounded queue size/latency, no app-caused sustained dropouts, and responsive controls.
- Rotation preserves the active session; backgrounding, screen lock, audio-focus loss, and headphone removal stop cleanly. Relaunch restores settings without autoplay.
- Exercise at least two physical Android devices, including one near the minimum supported OS, and LAN plus a permitted remote receiver. Confirm secure and intentionally cleartext endpoint behavior separately.

Live tests should be short and explicitly authorized, particularly on public receivers; use mock sessions and existing captures for routine development. This proposal does not claim that the listed receivers have been contacted or Android compatibility has already been validated.
