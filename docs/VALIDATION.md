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

## Next manual checks

Use an authorized receiver on the phone's network. Start with low media volume.

1. Select Local Kiwi 41 (or edit to a reachable receiver), 5000.000 kHz AM, then Play. Confirm audio and changing RSSI; Stop must silence output and release the slot.
2. Check known AM/USB/LSB/CW signals, frequency stepping and mode changes without reconnecting.
3. Switch between two receivers while playing. Check that old audio/RSSI never leaks into the new session.
4. Rotate while playing, then Home, screen lock, another audio app, and unplugged headphones. Rotation should preserve playback; other listed events should stop it.
5. Disconnect Wi-Fi: RSSI should go stale in two seconds, with a stream error after about ten seconds. Retry is manual.
6. Listen for 30 minutes, checking latency, dropouts, responsiveness, device heat, and receiver slot cleanup.
7. Repeat on an API-26-era and a current Android device. Verify a valid HTTPS/WSS receiver and rejection of an invalid certificate.

## Remaining implementation/validation risks

- Actual receivers must confirm the 48 kHz rate acknowledgement and server sample-rate behavior. PCM is resampled from the reported floating-point rate to 48 kHz; there is no drift-control PLL.
- Queue capacity is 200 ms plus at least 100 ms AudioTrack buffering. Backlog drops old queue chunks; audible behavior under real network jitter needs tuning.
- Startup/rebuffer audio fades in; Stop currently prioritizes immediate pause/flush over a cooperative fade-out.
- Receiver metadata does not yet adjust the fixed 0–30 MHz tuning bounds; transverter/extended-frequency receivers are unsupported.
- Passwords, ADPCM, background playback, public directory discovery, and waterfall are intentionally excluded.
- Source-rate changes after setup require reconnecting. Pre-setup SND packets are discarded; unsupported post-setup flags cause a visible error rather than noise.
- No store-ready branding, release signing, publishing configuration, or release-size optimization yet.
