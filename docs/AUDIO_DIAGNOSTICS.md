# Playback buffering and diagnostics

## Why zero missing frames can still sound broken

`missing frames` counts gaps in Kiwi SND sequence numbers. It does not measure when packets arrive, locally inserted silence, device output scheduling, or Bluetooth link problems. A perfectly ordered WebSocket stream can arrive in bursts and still exhaust an undersized playback queue.

The 0.4.0 clocked mixer primed receiver PCM with only 50 ms of audio and re-entered buffering/fade-in whenever the queue reached zero, including an exact, successful drain. That was too fragile for larger output bursts: it could repeatedly insert silence/fades without missing a network frame or underrunning AudioTrack itself.

A connected Pixel 8 Pro (Android 17 / API 37) showed an A2DP AudioTrack allocation of 15392 frames at 48 kHz, about 321 ms, during affected sessions. This exceeded both the old 50 ms radio prebuffer and its 200 ms queue capacity. Its hardware track underrun counts were zero; application-inserted silence was not reflected in those counters.

## Fix in 0.4.1

- Native AudioTrack requests at least 200 ms, or the platform minimum if larger. Its actual `bufferSizeInFrames`, not just the requested size, determines radio priming.
- Radio prebuffer target starts at `max(150 ms, actual device buffer + 100 ms)`. A 321 ms native ring therefore gets approximately 421 ms of queued radio audio before playback starts.
- Native output is initially primed with silence. RSSI tones remain independent of the radio queue and do not wait for radio priming.
- Only an actually short PCM read counts as local starvation. Exactly draining a successful read does not restart the fade or require refilling the whole prebuffer.
- A real underrun increases the radio target by 50 ms, up to one second (or the device-derived initial minimum if larger). Rebuffering fades in once, not every packet boundary.
- The PCM queue remains bounded. Excess backlog discards oldest audio and is counted. Mute/Stop clears old PCM without counting intentional silence as an underrun.

This trades some radio latency for resilience. Device/Bluetooth latency is additional; the displayed native ring size is not an end-to-end latency measurement. RSSI beeps are not delayed by the radio jitter queue, but still go through the native/Bluetooth output path.

## Background CPU starvation fix in 0.5.2

This was a separate failure from the 0.4.1 radio-buffer issue. On Pixel 8 Pro / Android 17, backgrounding the debuggable 0.5.1 build caused device underruns and eventually `Receive queue overflow; reconnect`. The service and CPU wake lock were present, and the writer already used audio thread priority. Sampled Java stacks showed the receiver and writer spending time in per-sample resampling/mixing/math, not simply waiting for packets or AudioTrack writes.

A stronger silent loopback test reproduced the failure with receiver audio **enabled**. The old background test mostly exercised muted radio and checked connection survival, not underrun growth. With the same audio code and buffers but **runtime debugging disabled**, the new test passed 60 seconds behind the Home screen plus 60 seconds screen-off: zero radio/device underruns and zero dropped PCM. The native ring in that run was 360 ms, with a 460 ms radio target.

Normal sideload builds now set `android:debuggable=false`, retaining the original debug signing key, package ID, and `app-debug.apk` path for in-place updates. For debugger development only, opt in with `-Pdebuggable=true`. Do not use that build to judge background audio performance. No buffer/latency increase, network-queue enlargement, or battery-policy bypass was needed for this fix. Longer runs and live listening still matter; the test does not prove every route or Android version is immune to underruns.

## Retune gaps in 0.7.1

The original handover implementation cleared the foreground PCM queue on every retune, before the session's next 200 ms command check. This could produce silence/refill, followed by more old-frequency audio, then new-frequency audio. Ordinary retunes now preserve the queue and wake the command sender immediately. Only mono/stereo layout changes still clear/reprime channel audio. All previously 50 ms playback fades/crossfades are now 20 ms; configurable beep envelopes are separate.

This eliminates the deliberate retune gap, not all end-to-end latency. Existing queued samples still drain normally; reducing the native/jitter buffers would trade away the resilience achieved by earlier fixes. The new frequency dot estimates settling from command identity, fresh audio, and buffer drain; it cannot prove the server has applied a frequency or measure downstream Bluetooth latency.

## Counters on the main screen

| Counter | Meaning |
|---|---|
| Missing frames | Missing Kiwi SND sequence numbers. |
| Local underruns | Radio PCM queue starvation episodes after priming. Initial startup and deliberate mute do not count. |
| Device underruns | AudioTrack output underrun count for this session. |
| Dropped audio (ms) | Received PCM discarded because the bounded radio queue overflowed. Not intentional mute/Stop flushes. |
| Radio buffer queued/target (ms) | Current queued PCM and adaptive priming/recovery threshold; `buffering` means radio output is waiting for that threshold. |
| Device buffer (ms) | Actual AudioTrack ring size at construction. |

Counters reset with a new receiver session. Changes in routing can change output latency; reconnecting playback recreates the sink for the new route.

If gaps persist, record which counters increase, phone/Android version, speaker vs wired vs Bluetooth route, whether the screen is off, and whether tones are enabled. If all counters stay flat, compare the speaker route with Bluetooth: the problem may be downstream of AudioTrack or already present in the received audio.

## Regression coverage

Pure Kotlin tests cover device-sized priming, exact-drain behavior, real-underflow recovery, bounded adaptation/backlog, mute/close semantics, and a minute of ordered 512-sample Kiwi packets with jitter and 80 ms output-write bursts. Session tests verify diagnostics reach the UI metrics independently of sequence-loss counters.

`AudioPlaybackTest` runs real AudioTrack on the phone's current route with silent PCM arriving at the receiver sample rate for 20 seconds after priming, switching from mono input to stereo input halfway through. Starting in 0.5.0, the output track is always stereo and all buffering thresholds/diagnostics use sample frames, not counts of individual channel samples. It reports the final buffer/counter snapshot and asserts no local starvation/backlog drops or growth in device underruns. It neither connects to a receiver nor records microphone audio. The phone test script runs it before the background test. `BackgroundPlaybackTest` now enables the full radio path as well as tone synthesis, runs for 60 seconds behind Home and 60 seconds screen-off, and asserts that device underruns, local underruns, and dropped PCM do not grow. Loopback pacing uses the reported source rate rather than a rounded 43 ms packet period.
