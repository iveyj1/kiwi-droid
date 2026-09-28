# Validation record

## Initial build — 2026-09-27

- `./tools/check.sh`: passed; 17 core tests and 3 Android settings unit tests.
- `:app:assembleDebugAndroidTest`: passed.
- `:app:connectedDebugAndroidTest`: one test passed on Pixel 3 XL, Android 12 / API 31.
- Debug APK reinstalled and launched after the instrumentation runner removed its installation; confirmed process running and inspected the screen.
- Android lint: zero errors, 11 warnings (dependency upgrades, missing application icon, and explicit Android 12 data-extraction rules still to add).
- No live receiver contacted as part of these checks. Audible playback is not yet claimed as validated.

## Covered automatically

- Mixed-endian PCM extremes, unsigned sequence/S-meter values, RSSI conversion, malformed/unsupported audio rejection.
- All six authentication/sample-rate/audio-rate arrival orderings; setup waits for successful authentication.
- Integer `/VER` timestamp precision above JavaScript's exact-integer range; chunked HTTP bootstrap.
- Mock HTTP/WebSocket session, binary/text MSG, initial commands, audio delivery, retuning, cancellation and sink closure.
- Receiver-busy errors, HTTP redirect refusal, cancellation during bootstrap.
- Sequence gaps, backwards/duplicate packets, 32-bit wraparound.
- Locale-independent integer-Hz tuning and CW offset, receiver URL restrictions.
- Streaming resampler packet continuity and bounded PCM queue behavior.
- Settings round trip and validation.
- Phone UI: frequency entry, steps, mode selection, activity recreation, invalid input, stopped RSSI.

## Background playback — 0.2.0

- User reported the initial build working with a live receiver.
- Added a non-exported `mediaPlayback` foreground service, active MediaSession, and notification/lock-screen Stop control. Playback no longer belongs to the Activity/ViewModel lifecycle.
- Holds CPU and Wi-Fi locks while active; releases them on stop/error/service destruction. Audio focus and route-unplug still stop listening. No sticky restart/autoplay.
- `./tools/test-phone.sh`: UI smoke test and screen-off service test both passed on Pixel 3 XL / Android 12. Installs with `adb install -r` rather than letting Gradle uninstall user settings.
- Screen-off test used a silent loopback Kiwi server and real AudioTrack. Streaming remained live for 33 seconds after Home + screen off; both initial and periodic keepalives arrived, RSSI was not stale, and there was only one SND connection.
- Notification Stop cleared metrics, removed the notification, and closed the socket. Post-test `dumpsys` confirmed no running app service and release of `KiwiDroid:Playback` wake lock.
- Both tests restore original controls. A PIN lock blocks subsequent UI automation; the script runs the UI test first and the screen-off test last. The phone must be unlocked before starting it.
- Still needs extended live Wi-Fi/cellular background testing and checks on Android 13+ (including MediaSession notification exemption and Android 15+ audio-focus/foreground-service policy).

## Audible RSSI — 0.3.0

- Added independent receiver-audio and RSSI-beep switches, musical pitch mapping, a configurable sound editor, and backward-compatible sound settings persistence.
- Default is chromatic notes, +10 dB/octave, C3 at −110 dBm through C7 at −70 dBm. Configurable mapping points/scales/root, sine/triangle waves, gains, beep length/period/fade, RSSI smoothing and note hysteresis.
- Receiver audio mute is local; RSSI still arrives on the same SND connection. One clocked AudioTrack mixes radio PCM and tones. Stale readings silence tones within two seconds plus the output buffer.
- `./tools/check.sh`: 29 core tests and 5 settings tests passed, APK built, lint no errors.
- `./tools/test-phone.sh`: both tests passed on Pixel 3 XL / Android 12. UI checks exercised mute/beep switches, invalid/valid mapping edits, Save, and activity recreation. Screen-off test enabled tones at zero gain with receiver audio muted, exercised sound toggles without reconnecting, confirmed fresh RSSI and keepalives after 33 seconds, then stopped via the notification.
- Post-test service absent and playback wake lock released. Tests restored original controls/sound settings.
- No real receiver contacted and no audible test tones played. Real cable-fault audibility/response, blended radio+tones, and long-run audio quality remain manual listening checks.

## Received RSSI dip/peak latch — 0.4.0

- Added raw-sample latch modes Off/Dips/Peaks/Both, configurable dB threshold and hold. Default Both, 1 dB, 1000 ms from the first sounded beep.
- Replaced latest-value RSSI handoff with synchronized per-receipt observation. Bursts cannot overwrite a transient before the audio thread sees it. Raw extrema bypass smoothing/hysteresis but retain the configured scale/range.
- Bounded minimum/maximum slots alternate at beep boundaries; unsounded extremes do not expire before their first beep. Staleness/config changes/disable/session teardown clear them.
- `./tools/check.sh`: 41 core tests, 6 settings tests, APK and lint passed. Added single-sample dip/peak-then-recovery tests before any intervening render, min/max selection, fairness, hold expiry, mode/threshold controls, stale gaps, settings resets/migration, receipt/render timestamp race coverage, and exact 0.1 dB threshold boundary rounding.
- `./tools/test-phone.sh`: both tests passed on Pixel 3 XL / Android 12. UI test selected Peaks only and saved/restored custom hold/threshold. Silent background receiver test injected one-frame RSSI dips/peaks, retained one SND connection with radio muted, and stopped cleanly from the notification.
- Auditory checks of real cable/contact transients remain user listening tests. A detected RSSI excursion can still map to the same note if it is below scale resolution or beyond the mapping endpoints.

## Bluetooth playback buffering — 0.4.1

- User reported repeated audio gaps on a Pixel 8 Pro / Android 17 (API 37), using Bluetooth earbuds, with no missing SND frames. AudioFlinger history showed the app's A2DP tracks used 15392 native frames (~321 ms) with zero hardware underruns. The old 50 ms priming/200 ms radio queue could not reliably cover that output scheduling.
- Added device-sized radio priming (native buffer +100 ms, minimum 150 ms), bounded +50 ms adaptive recovery after real starvation, and no fade/rebuffer merely because a successful read exactly emptied the queue. Native output is prefilled with silence; tones stay independent of radio priming.
- Added visible local radio starvation, AudioTrack underrun, dropped-PCM, and queue/device-size diagnostics. Missing SND frames remains a separate metric.
- `./tools/check.sh`: 47 core tests and 6 settings tests passed, APK built, lint no errors. New tests cover bursty jittered delivery, exact drains, actual underflow/adaptation, bounded backlog, mute/close behavior, and diagnostics propagation. Fixed missing close acknowledgement in a flaky mock-busy-server test.
- The first UI run exposed an Android 17 incompatibility in Compose's transitive Espresso 3.5.0 (`InputManager.getInstance` reflection). Explicit test-only Espresso 3.7.0 resolved it; no runtime app dependency change was needed.
- `./tools/test-phone.sh`: all three tests passed on Pixel 8 Pro / Android 17 with Bluetooth connected: controls, 20-second actual AudioTrack/receiver-PCM pacing, and 33-second screen-off loopback streaming with notification Stop.
- The silent AudioTrack test reported: local underruns **0**, device underruns **0**, discarded PCM **0 ms**, radio queue **519 ms**, target **420 ms**, native ring **320 ms** (integer-ms display). This verifies local buffering, not subjective Bluetooth sound quality.
- The user subsequently reported the Bluetooth fix working well. Longer runs remain; see `AUDIO_DIAGNOSTICS.md` for interpretation/trade-offs.

## Per-mode passbands and added modes — 0.5.0

- Added signed lower/upper passband editing (−6000…+6000 Hz, lower < upper), remembered independently for every mode and restored on switching/relaunch. Existing preferences migrate to mode defaults without losing tuning or sound settings.
- Added SAM, SAU, SAL, SAS, IQ, and CWN. CW/CWN wire tuning tracks the edited passband midpoint, including half-Hz midpoints. Existing CW defaults retain their 800 Hz offset; CWN defaults to 470–530 Hz (500 Hz offset).
- Added stereo/IQ SND decoding with the 10-byte GPS header, per-frame layout handling across mode transitions, and independent channel resampling. One stereo AudioTrack duplicates mono, preserves SAS L/R and IQ I/Q, and centers RSSI tones. Radio jitter capacity/gains/diagnostics remain frame-based, retaining the Bluetooth fix.
- `./tools/check.sh`: 60 core tests, 8 settings tests, APK build and lint passed. Coverage includes all mode commands, custom/invalid cuts, per-mode persistence/migration, GPS/PCM endianness, mono/stereo transitions on one socket, and channel isolation/alignment/mixing.
- `./tools/test-phone.sh`: all four tests passed on Pixel 8 Pro / Android 17 with Bluetooth: controls, per-mode passband edit/validation/recall/preferences, 20-second mono-to-stereo output, and 33-second background IQ-format stream after exercising the new modes.
- Audio test reported zero local underruns, zero device underruns, zero dropped audio, 342 ms queued radio, 420 ms target, and 320 ms native buffer (rounded down). Filter UI test restored the original per-mode cuts after testing; all test traffic was loopback/silent.
- Real receiver demodulation/sideband separation/IQ listening checks remain; IQ output is resampled playback, not raw capture. See `MODES_AND_FILTERS.md`.

## Editing and transient audio focus — 0.5.1

- All edit boxes select existing text on focus entry, including frequency, step, receiver details, passbands, and sound settings. Later taps/keystrokes retain normal cursor editing. Shared field handles externally updated values and IME selection/composition state.
- CWN immediately follows CW in the mode list; name-based persisted settings are unaffected.
- Transient focus loss, including notification duck requests, silences the entire AudioTrack and restores its volume on focus gain. The receiver connection and live PCM consumption continue, preventing a stale backlog. Both radio and RSSI tones are silenced; saved sound settings remain unchanged. UI indicates temporary silencing.
- Explicit Stop, permanent focus loss, headphone removal, process death, and reboot never trigger automatic playback restart.
- `./tools/check.sh`: 61 core tests, 8 settings tests, APK build and lint passed.
- All six phone tests passed on Pixel 8 Pro / Android 17. New tests verify actual tap/select/type behavior, cursor editing, refocus and external text updates, and transient-versus-permanent focus classification. The loopback service test requests competing Android transient/duck focus, verifies silencing and automatic restoration on the same connection, then verifies background playback and notification Stop.
- Silent Bluetooth output test: zero local/device underruns, zero dropped audio; 502 ms queued, 420 ms target, 320 ms native buffer. Tests preserve user settings and do not contact live receivers.

## Next manual checks

Use an authorized receiver on the phone's network. Start with low media volume.

1. Select Local Kiwi 41 (or edit to a reachable receiver), 5000.000 kHz AM, then Play. Confirm audio and changing RSSI; Stop must silence output and release the slot.
2. Check known AM/USB/LSB/CW signals, frequency stepping and mode changes without reconnecting.
3. Switch between two receivers while playing. Check that old audio/RSSI never leaks into the new session.
4. Rotate while playing, then Home and screen lock: playback should continue and reopening the app should show the same session. Stop from the notification/lock-screen control must release it. Transient notifications should silence output and restore it automatically; permanent audio-focus loss or unplugged headphones should stop it.
5. Disconnect Wi-Fi: RSSI should go stale in two seconds, with a stream error after about ten seconds. Retry is manual.
6. Listen for 30 minutes, checking latency, dropouts, responsiveness, device heat, and receiver slot cleanup.
7. Repeat on an API-26-era and a current Android device. Verify a valid HTTPS/WSS receiver and rejection of an invalid certificate.
8. Enable RSSI beeps at low volume, mute receiver audio, center the map with RSSI → C5, and compare known signal/cable changes. Confirm louder RSSI produces higher notes, custom scale/cadence controls work, stale tones cease, radio can be restored without reconnecting, and both sounds stop from the notification.

## Remaining implementation/validation risks

- Actual receivers must confirm the 48 kHz rate acknowledgement and server sample-rate behavior. PCM is resampled from the reported floating-point rate to 48 kHz; there is no drift-control PLL.
- As of 0.4.1, queue/priming sizes depend on the actual AudioTrack buffer and adapt on starvation. Backlog remains bounded and drops are counted. This adds radio latency; long-run clock drift, real network jitter, and Bluetooth link behavior still need listening tests.
- Startup/rebuffer audio fades in; Stop currently prioritizes immediate pause/flush over a cooperative fade-out.
- Receiver metadata does not yet adjust the fixed 0–30 MHz tuning bounds; transverter/extended-frequency receivers are unsupported.
- Passwords, ADPCM, public directory discovery, and waterfall are intentionally excluded. Background playback was added in 0.2.0 at the user's request.
- Source-rate changes after setup require reconnecting. Pre-setup SND packets are discarded; unsupported post-setup flags cause a visible error rather than noise.
- A basic radio icon is included, but no store-ready branding, release signing, publishing configuration, or release-size optimization yet.
