# Receiver handover and retention (0.7.1)

## Controls

Selecting another receiver while playing keeps the old receiver audible until the new receiver has authenticated, delivered PCM, and filled its device-sized radio buffer. The output then crossfades for 20 ms through **one AudioTrack**, including receiver audio and RSSI tones. A slow or failed connection does not immediately silence the old source. New connections have a bounded setup/buffering timeout.

The receiver picker shows the requested destination. **Listening** shows the output source and its tuning; during setup the status says **Listening to A · Connecting to B**. RSSI, notification metadata, and new reception logs remain attached to A until the transfer to B. Hardware/Bluetooth buffering still delays when newly rendered samples reach the ear; the switch is not sample-aligned across different receivers and may repeat or skip program audio because receiver delays differ.

**Keep previous receiver** offers Off (default), 15, 30, 60, 120, 300, or 600 seconds:

- Off closes the previous connection just after the crossfade.
- Otherwise it stays connected and silent for the selected duration, measured from transfer. Its PCM is continually decoded and drained, not accumulated for later replay.
- Selecting it again reuses the existing stream if fresh and primed. The receiver just left becomes the retained source with a fresh timer.
- Selecting a third receiver closes the retained connection before opening another. At most two sessions are owned concurrently.
- Changing the retention setting restarts the retained source's timeout; Off closes it after a short fade allowance.
- Changing frequency, mode, or the **active** passband immediately closes the retained connection. Volume, RSSI sound controls, receiver location, and step-size changes do not invalidate it. Editing cuts for an inactive mode does not change the live tuning.
- During connection setup each session has an independent tuning snapshot. Changing the desired tuning cancels/replaces the candidate, leaving the old audible source unchanged until a replacement is ready. Returning to the audible receiver cancels the candidate.

## Retuning and quick controls

Ordinary frequency/passband changes and mode changes with the same mono/stereo layout now preserve the radio PCM queue. Commands are sent when tuning changes, not on the former 200 ms housekeeping timer. This removes the client-created silence/reprime cycle; already buffered old-frequency audio still plays before the new samples arrive. Mono↔stereo transitions still reset the channel processing and rebuffer. Startup, underrun recovery, and format-transition fade-ins are now 20 ms, as is receiver crossfade. RSSI beep attack/release settings are unchanged.

Near Tune, **lf** recalls the previous committed frequency and **lr** selects the previous receiver; repeated presses toggle back and forth. They do not start stopped playback. Receiver recall uses the current tuning and reuses a retained stream when available. History lasts for the app process (including activity recreation), not across process restart; unavailable history is disabled. A removed receiver cannot be recalled. Near Mode, **cm** swaps USB↔LSB, using each mode's saved passband; it is disabled in other modes.

The unlabelled dot beside the frequency is orange during connection, retuning, stale audio, or rebuffering; blue when the selected source/command has fresh, buffered audio and its estimated settling interval has elapsed; grey when stopped. Accessibility descriptions identify each state. Settling allows for queued radio/native audio and a short margin (or the native output delay plus crossfade for a receiver switch). It is an estimate, not a frequency acknowledgement from the receiver or an exact measurement of Bluetooth latency. New RSSI log-window samples are withheld while tuning is settling.

## Failure and resource handling

A failed candidate leaves the current source playing, reports the error, and restores its receiver/tuning controls when that receiver remains in the list. A failed retained stream is discarded without interrupting foreground listening. If the audible stream fails, a fresh retained stream can take over; otherwise playback stops (or an already requested candidate continues connecting). Failed sockets are not automatically reconnected.

Stop, permanent audio-focus loss, headphone disconnection, and service destruction cancel **both** connections and release the single output. Temporary focus interruptions silence that output and restore it normally; both network streams continue while interrupted and the retention timer continues to run.

Two streams consume two receiver slots and the sum of their network bandwidth: about 172 MB/hour for two ordinary mono streams, about 346 MB/hour for two SAS/IQ streams, before protocol overhead. The second resampler/decoder also uses CPU and battery. Keep retention Off unless quick comparison is useful. Retention adds no extra audio-buffer target beyond the existing device-sized buffering.

## Implementation and validation

- `ReceiverHandover` serializes desired selections, stream readiness/failure, transfer, and expiry. Each slot has its own tuning StateFlow. Cancellation is joined before allocating its replacement.
- `ReceiverMixer` owns up to two independently buffered/resampled inputs, drains both on one output clock, and crossfades their mixed audio. Rapid reversal continues the existing fade rather than jumping to the other source at full volume. Stale background tone latches are reset when that source becomes audible again.
- `PlaybackService` owns the coordinator, one focus request, one AudioTrack, and the wake/Wi-Fi locks. Explicit Stop/Play cycles are distinct from receiver selections, including quickly repeated Stop/Play.
- JVM tests cover delayed/failed/timed-out candidates, independent metrics, socket reuse, expiry, retuning, cancellation, third-receiver replacement, background failure, foreground fallback, bounded inputs, and crossfade reversal.
- Silent phone tests use two loopback receivers: delay B while A plays; verify log source attribution; transfer to B and retain A; spend 15 seconds behind Home with both streams; switch back without another connection; retune and observe B close; Stop and observe A close. The normal 60-second Home plus 60-second screen-off test also runs on the new mixer path.

These checks use the phone's current audio route and no live receiver slots. Subjective handover quality across real receivers, Bluetooth-specific two-stream endurance, and prolonged network loss still need listening checks.
