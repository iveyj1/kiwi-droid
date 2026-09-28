# Audible RSSI / cable troubleshooting

## Quick use

1. Select the receiver, frequency, and mode; press **Play**.
2. Enable **RSSI beeps**. Disable **Receiver audio** to hear only the meter.
3. Open **Sound settings** and tap **RSSI → C5** to put the current signal near the middle of the pitch range; **Save**.
4. Flex/swap the cable or connector. Higher notes mean stronger RSSI; lower notes mean weaker RSSI. Background/screen-off playback works as before.
5. Stop in the app or media notification when finished.

Start at low media volume. The beep gain is independent of the receiver gain, but both ultimately use Android media volume. RSSI is the receiver-reported SND signal strength, not audio loudness. Keep tuning, mode, and passband fixed when comparing cables; fading/interference/receiver calibration can also change the reading.

**Receiver audio off is a local mute, not a network bandwidth optimization.** Kiwi RSSI arrives in the SND stream. The connection and receiver slot remain active, and network use is unchanged. Both switches may be off for visual-only monitoring. Sound changes never reconnect the receiver.

## Mapping

The default mapping is:

```text
-110=C3
-100=C4
-90=C5
-80=C6
-70=C7
```

This is **10 dB per octave**: `semitones = referenceNote + 12 × (RSSI − referenceRSSI) / 10`. Note frequencies use equal temperament, `Hz = 440 × 2^((MIDI − 69) / 12)`. Each chromatic step represents about 0.833 dB with this mapping.

Enter 2–16 `dBm=note` lines in increasing RSSI and pitch order. Accepted notes include `C4`, `F#4`, `Bb3`, and numeric MIDI notes 36–108 (C2–C8). Mapping points interpolate linearly in semitones, not hertz. Custom point spacing overrides the default dB/octave sensitivity. Values outside the endpoints clamp to the first/last pitch; there is no wrapping. If the sound stops changing near an endpoint, recenter or widen the mapping.

**10 dB/octave** restores the default mapping. **RSSI → C5** shifts that preset so the current fresh reading maps to C5 (523.25 Hz), leaving ±20 dB of range. It is unavailable without a fresh reading or for readings outside the supported centering range. Changes in the editor apply only on Save; Cancel leaves settings unchanged.

Scale choices:

- **Chromatic** (default): nearest equal-tempered semitone.
- **Major**, **natural minor**, or **major pentatonic**, with a selectable root.
- **Custom**: unique semitone offsets 0–11 relative to the root, repeated in every octave. Example `0, 3, 7` is a minor triad; `0` gives octave-only steps.
- **Continuous pitch**: no note quantization, useful for changes smaller than a semitone.

Only scale notes inside the endpoint note range are used. Exact ties choose the lower note. Invalid/empty/non-increasing mappings and scale/range combinations without any notes are rejected before saving.

## Dip/peak latch

Enabled by default in 0.4.0: **Dips and peaks**, **1 dB threshold**, **1000 ms hold**. Configure it under **Sound settings → Dip/peak latch**; modes are Off, Dips only, Peaks only, and Dips and peaks.

Every received RSSI value is examined before smoothing, even when several SND frames arrive between audio writes. A dip/peak qualifies when the raw value is at least the threshold below/above the preceding smoothed RSSI. The first reading establishes a baseline and is not an event. This detects received signal excursions, not missing network packets.

- The lowest qualifying dip and highest qualifying peak are retained in two bounded slots. Less-extreme events merge into the held extreme rather than forming an unbounded queue.
- At a beep boundary, a held extreme replaces the normal smoothed pitch. If both directions are pending, successive beeps alternate dip/peak; neither can starve the other.
- An unsounded extreme cannot expire just because the hold time is shorter than the repeat interval. Hold starts at its first beep, and that beep completes normally. More-extreme replacements get their own first beep and hold; repeated equal observations do not extend an existing hold.
- When holds expire, the normal smoothed level returns. Recovery from a dip may qualify as a peak relative to the still-low smoothed baseline (and vice versa).
- Latched pitch bypasses smoothing and note hysteresis, but still uses the selected scale and mapping endpoints. A small excursion quantized to the same note, or a clamped out-of-range value, may not sound different. Use continuous pitch or recenter the mapping for finer discrimination.
- Disabling tones, changing tone settings, a stale RSSI gap (two seconds), disconnect, Stop, or receiver switching clears pending extremes. Receiver mute alone does not clear them. No old events replay after reconnection.

Hold is configurable from 100–10000 ms; threshold from 0.1–60 dB. Lower the threshold to capture smaller received changes, at the cost of latching normal signal noise/fading. The upgrade preserves existing tone calibration/gains/enabled state and supplies these new latch defaults. Single-sample detection is verified in tests where both the excursion and recovery arrive before the next render.

## Sound parameters

| Parameter | Default | Purpose |
|---|---:|---|
| Receiver gain | 100% | Local received-audio level; independent of beep gain. |
| Beep gain | 12% | Peak tone amplitude relative to full-scale PCM. Start low. |
| Beep length | 100 ms | Audible portion of each cycle. |
| Repeat interval | 300 ms | Start-to-start period, including the silent gap. |
| Attack/release fade | 8 ms | Soft edges to avoid clicks. |
| RSSI smoothing | 100 ms | Exponential time constant; 0 disables smoothing. |
| Note hysteresis | 0.15 dB | Extra excursion past a note boundary needed before changing notes. |
| Waveform | Sine | Sine or brighter triangle wave. |

Pitch is selected at the start of each beep and held for its duration. With the latch enabled, qualifying raw extremes take precedence over the smoothed level. Decrease the repeat interval to hear pending events sooner. With the latch off, longer intervals/smoothing can hide brief transients. Hysteresis reduces chatter around a quantization boundary; it is ignored in continuous-pitch mode and for latched extremes. The latch captures qualifying received RSSI values, not RF events that occurred between server measurements.

Length is 20–2000 ms; interval is 50–5000 ms and must exceed length by at least 10 ms. Fade is 1–50 ms and at most half the beep length. Smoothing is 0–2000 ms; hysteresis is 0–3 dB. All settings persist. Upgrading from pre-tone versions preserves tuning/server settings and starts with tones disabled and receiver audio enabled.

## Timing, freshness, and mixing

RSSI is delivered directly from valid SND frames to the audio sink, independently of the 5 Hz UI refresh. Receipt updates the latch/smoother under a short lock shared with tone rendering, rather than overwriting a single latest-reading slot. The audio writer runs 10 ms blocks at 48 kHz. A single stereo AudioTrack mixes resampled receiver PCM and locally synthesized beeps centered in both channels; signed PCM saturates safely if their sum exceeds full scale. SAS left/right and IQ I/Q channels are preserved, not averaged. Disable beeps for clean IQ playback (see `MODES_AND_FILTERS.md`). Reduce one or both gains if simultaneous radio/beeps sound distorted.

Muting receiver audio clears its queued PCM, so unmuting does not replay a backlog. The already-submitted AudioTrack buffer adds device-dependent delay (at least 200 ms requested in 0.4.1; approximately 321 ms measured on Pixel 8 Pro Bluetooth). Radio PCM has an additional adaptive jitter buffer; tones do not wait for that radio buffer. See `AUDIO_DIAGNOSTICS.md`. The output clock is independent of network packets: stalls cannot leave a stuck beep playing indefinitely. Tones silence after two seconds without fresh RSSI, and Stop/error/receiver switching tears down the old generator with the old session. Existing audio-focus-loss and headphone-disconnect behavior applies to both sounds.

Unit tests verify frequency ratios, scale selection, custom interpolation, oscillator/envelope/cadence, smoothing, hysteresis, single-sample dip/peak capture, alternating/expiring holds, burst delivery and stale/reset behavior, gain/clipping, PCM queue behavior, and settings migration. The phone test uses muted receiver PCM and zero-volume enabled tones to validate routing/lifecycle without making test sounds; audible cable behavior still needs listening checks with real signals.
