# Logbook

Record what you hear on any band, with the receiver, tuning, signal level, and (on MW) the station from the database.

## Logging

Tap **Log…** under the RSSI meter. The draft captures, at that moment:

- UTC time, receiver name and URL, and the receiver's antenna location if set;
- displayed frequency, mode, and passband;
- the latest RSSI and its minimum and maximum over the preceding 10 seconds, from fresh readings at the current tuning only (cleared on retune, receiver change, and Stop). "RSSI not recorded" means nothing was playing.

Choose the station:

- On MW, the stations inside the passband are listed, most likely first when the receiver location is set (by power otherwise), with format and distance/bearing. The top 20 are shown, and the first is preselected. The usual visibility filters apply.
- **Other** takes a free-text station or call sign (any band, or an MW station missing from the database).
- **Unidentified** records the reception without a station.

Then set **Heard ID**, **Tentative**, or **Unidentified**, add notes (up to 2,000 characters), and **Save**. A chosen MW station is stored as a snapshot (call, place, format, distance, bearing, facility ID), so later database refreshes do not rewrite history.

## Logging from the MW station dialog

Each station has a **+ log** or **✓ log** action. With no matching entry it opens a draft at that station's frequency with the station selected. Otherwise it edits the **latest reception timestamp**, regardless of confidence. Matching uses facility ID plus channel, including merged IDs, not call-sign text. A station-associated unidentified entry also counts as an existing log.

This action does not tune the receiver. A draft for a different frequency omits the current RSSI measurements. Drafts on the currently heard channel preserve its mode/passband; existing entries preserve their original receiver, time, tuning, RSSI, and station snapshot. The selected station stays in the editor even when hidden or outside the top 20 candidates. Saving/cancelling returns to the MW list and its search. The main **Log…** action remains available to create another reception instead of editing the latest one.

During receiver handover, drafts and RSSI belong to the audible source, not the receiver still connecting. The RSSI window resets at transfer. See `RECEIVER_SWITCHING.md`.

## Logbook

**Logbook (n)** lists entries newest first, with totals: entries, distinct stations identified (plus tentative), states/provinces, and countries. Search matches kHz, call, place, format, notes, receiver, or date. Each entry offers **Tune** (frequency and mode) and **Edit**; delete from the editor with a second tap to confirm.

In the MW station list, logged stations are marked "✓ logged" (Heard ID) or "? logged tentatively". Marks match through consolidated facility IDs.

## Export

**Export CSV** opens the system save dialog (no storage permission needed) and writes every entry, oldest first, as RFC 4180 CSV in UTF-8:

`utc, khz, mode, low_cut_hz, high_cut_hz, station, confidence, place, format, distance_km, bearing_deg, rssi_dbm, rssi_min_dbm, rssi_max_dbm, receiver, receiver_url, receiver_lat, receiver_lon, facility_id, notes, id`

Fields beginning with `=`, `+`, `@`, or a non-numeric `-` are prefixed with `'` so spreadsheets do not run them as formulas.

## Storage

Entries live in the app's private `files/logbook.jsonl`, one JSON object per line, separately from settings. New entries append one line; edits and deletions rewrite the file through a temporary file and an atomic rename. Lines that cannot be read are kept verbatim rather than dropped. Uninstalling the app deletes the logbook, so export it first.
