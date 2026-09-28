# Modes and per-mode passbands

Tap **Mode** to select AM, USB, LSB, CW, SAM, SAU, SAL, SAS, IQ, or CWN. Tap **Passband** below it to edit the lower and upper filter cuts for that mode.

Cuts are **signed whole Hz relative to the receiver's carrier/baseband zero**, not absolute RF frequencies or a pair of bandwidth magnitudes. For example, USB can use `+250 … +2700 Hz`; LSB can use `−2700 … −250 Hz`.

- Both cuts must lie within **−6000 … +6000 Hz**, and lower must be strictly less than upper. This is the supported ordinary 12 kHz receiver profile; it does not expose wider-band receiver filters.
- **Save** applies on the current connection; no reconnect is needed. Invalid input does not change tuning.
- Each mode has its own remembered pair. Switching modes or receivers, activity recreation, and app restart retain them. The preferences are global per mode, not separately per receiver.
- **Mode defaults** fills the editor with that mode's defaults; Save commits them. Cancel leaves the existing pair unchanged.
- Upgrades from earlier builds retain frequency/mode/step/sound settings and initialize the new passband map from defaults. Missing mode entries in saved maps also receive defaults.
- Receiver firmware may impose additional limits; the client sends the requested cuts but does not claim an independently acknowledged filter response.

## Defaults

| Mode | Meaning | Lower / upper Hz | SND channels |
|---|---|---:|---:|
| AM | Amplitude modulation | −5000 / +5000 | 1 |
| USB | Upper sideband | 0 / +3000 | 1 |
| LSB | Lower sideband | −3000 / 0 | 1 |
| CW | CW, existing 800 Hz-centered default | +600 / +1000 | 1 |
| SAM | Synchronous AM | −5000 / +5000 | 1 |
| SAU | Synchronous AM, upper sideband | 0 / +5000 | 1 |
| SAL | Synchronous AM, lower sideband | −5000 / 0 | 1 |
| SAS | Synchronous AM, stereo sideband output | −5000 / +5000 | 2 |
| IQ | Receiver I/Q baseband | −5000 / +5000 | 2 |
| CWN | Narrow CW, 60 Hz wide at 500 Hz | +470 / +530 | 1 |

The original app's AM/USB/LSB/CW defaults are unchanged. Synchronous AM defaults follow the same 5 kHz convention. CWN follows the reference client's 470–530 Hz default.

## CW and CWN frequency semantics

The displayed frequency remains the desired **RF passband center**. The wire frequency subtracts the midpoint of the active CW/CWN cuts:

```text
receiver frequency Hz = displayed frequency Hz − (lower Hz + upper Hz) / 2
```

Thus 335.000 kHz CW with cuts 600/1000 sends 334.200 kHz, as before. CWN with 470/530 sends 334.500 kHz. Editing CWN to 600/701 sends 334.3495 kHz; half-Hz midpoint offsets are preserved exactly even though the displayed tuning control steps in whole Hz. Invalid offset-adjusted receiver frequencies are rejected.

For other modes, displayed frequency is the receiver carrier frequency and changing the cuts does not retune it.

## Stereo and IQ playback

The transport decodes each SND frame's format flag, so in-flight mono/stereo packets during a mode change are handled in their actual format. Stereo/IQ frames include an additional 10-byte GPS header, followed by interleaved signed 16-bit big-endian samples. The GPS bytes are parsed as metadata, never played as audio.

One stereo AudioTrack is used throughout the session:

- Mono audio is duplicated to left and right.
- SAS preserves the server's stereo output.
- IQ sends I to left and Q to right. It is not additional client-side demodulation.
- RSSI beeps are mixed equally into both channels. Turn beeps off for clean I/Q playback, or mute receiver audio for RSSI-only listening.
- Both channels are resampled with independent, matched-phase resamplers. A mono/stereo layout change clears old queued PCM and resets both resamplers together without reconnecting the WebSocket.
- Buffer sizes/diagnostics count **sample frames**, not individual channel samples, preserving the Bluetooth buffering fix.

IQ here is **resampled audio playback, not raw-IQ export/recording**. The server's processing, local gains, clipping, and Bluetooth codecs can affect it. Use a purpose-built capture path for sample-accurate I/Q analysis. SAS and IQ use about **173 MB/hour** at 12 kHz before protocol overhead; mono remains approximately 86 MB/hour.

## Evidence and testing

- `~/kiwi/kiwiclient/kiwi/client.py`: mode names/default cuts, stereo classification (`sas`, `iq`), 10-byte GPS structure, and interleaved channel decoding.
- Kiwi server [`rx/rx_sound.cpp`](https://github.com/jks-prv/Beagle_SDR_GPS/blob/master/rx/rx_sound.cpp): SND flag `0x08` is set for IQ/stereo output; SAS uses the stereo packet path. Read for protocol confirmation, not copied into this app.
- Independently generated unit vectors verify GPS/PCM endianness and separation, invalid stereo packets, exact filter commands, per-mode persistence, CW/CWN offsets, channel-resampler isolation, stereo mixing, and frame-based buffering.
- Mock WebSocket tests exercise mono → SAS → IQ → CWN and all new mono modes on a single connection.
- Phone tests cover filter validation/recall/persistence, real AudioTrack mono-to-stereo transitions on Bluetooth, and screen-off IQ-format streaming against a silent loopback receiver.

Live SAM lock behavior, actual sideband separation, and subjective IQ/CWN listening on real receivers remain listening checks; the automated phone tests do not contact public or LAN receivers.
