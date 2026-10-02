# MW (AM broadcast) station list

North American MW station database: a bundled FCC table, the stations inside the current passband, search, tune-by-station (phase 1), receiver locations, likelihood ranking, and favorites (phase 2). Logging is a later phase.

## Using it

The MW section appears only while the tuned frequency is **520–1710 kHz**; other bands keep the plain tuning screen.

- Below **Tuned:**, each 10 kHz channel (530–1700 kHz) whose carrier lies inside the current RF passband is listed with its strongest licensed station, e.g. `1030 kHz: WBZ Boston, MA, US · 50 kW`. `(+n)` counts other stations on that channel. Wide AM tuned between channels shows both neighbours; narrow CW/CWN between channels shows none.
- Tap a channel line or **MW stations** to open the list. **In passband** limits it to those channels; **All** covers the band. Search matches a kHz prefix (`10` → 1000–1090), a call-sign prefix, or part of the city/state/country.
- Tapping a station tunes to its carrier. AM, SAM/SAU/SAL/SAS, USB, and LSB are kept; CW, CWN, and IQ switch to AM.
- Without a receiver location, stations on a channel are ordered by peak licensed power, then night power, then class (A first). With a location, they are ranked by the likelihood heuristic below.

## Receiver location

The ranking uses the **KiwiSDR antenna's** location, not the phone's. Set it under **Servers → Edit** as decimal degrees (west negative), or press **From receiver**. That makes one explicit `GET /status` request to the URL being edited and fills in its `gps=(lat, lon)` if the owner publishes one. Redirects are not followed. Nothing is saved until **Save**. An unset `(0, 0)` is treated as unknown. Blank fields clear the location.

Locations are stored separately from the receiver list, keyed by normalized URL. Editing only the location therefore never replaces an active session. The stored URL spelling is kept when an edit normalizes to the same address.

## Likelihood ranking

When the receiver has a location, the channel lines and the **In passband** list rank stations by a coarse estimate for *now*. The estimate is re-evaluated each minute.

- **Authorization period at the station:** night when the sun is below −0.833° at the transmitter, FCC critical hours within two hours after sunrise or before sunset, otherwise day. The matching power applies; critical hours fall back to day power when no critical-hours figure is listed. A station with no authorization for the period (such as a daytimer at night) is listed last as "not authorized".
- **Ground wave:** `10·log10(kW) − 20·log10(km) − 3 dB × (km/100) × (kHz/1000)`. This is inverse distance plus frequency-proportional excess loss over average ground.
- **Sky wave:** `10·log10(kW) − 20·log10(max(km, 200)) − 6 dB`, less 1.5 dB per 100 km beyond 3,000 km. It is scaled by darkness at the path midpoint, which runs from none at sunrise/sunset elevation to full at −12°.
- The two are summed in power. Rows show distance, compass bearing from the receiver, the current period and power, "sky wave" when it dominates, and dB relative to the top entry.

This is a "who to listen for" hint, not identification or a propagation prediction. Directional antenna patterns (flagged DA), ground conductivity, season, solar and geomagnetic conditions, and co-channel interference are ignored. Sun positions use the NOAA low-precision algorithm.

## Favorites

Tap ☆/★ on any station row. Favorites appear as chips on the main screen **on every band** once any exist; tapping one tunes to that channel with the same mode rules as the list. The picker's **Favorites** view lists them, including permits even when permits are hidden. Favorites are stored by facility ID and channel, so they follow call-sign changes and facility consolidation (merged IDs still match). At most 200 favorites.

## Station status and the permit filter

| Status | Meaning | Shown by default |
|---|---|---|
| LIC | US licensed station | Yes |
| CP | US construction permit with no licence on that channel (new station, or a move not yet licensed) | No: enable **Construction permits** |
| NOTIFIED | Canadian, Mexican, or Cuban station from the FCC's treaty notification records | Yes |

The FCC lists every non-US entry as `CP`. The importer relabels these `NOTIFIED`, so the permit filter hides only genuine US permits. Treaty notifications may be out of date. The **Construction permits** choice is remembered.

## Consolidation

The FCC AM Query returns about 24,600 records for 530–1700 kHz; the table keeps about 7,800 stations (US, CA, MX, CU):

1. Only licence (`LIC`) and permit/notification (`CP`) records are used; applications are dropped. US call signs beginning with `D` (deleted facilities) are dropped.
2. Per facility ID and channel, licence records win over permits. Within the chosen status, the newest record (highest application ID) for each hours category is used. Day, night, unlimited, and critical-hours records are merged into one station with day/night/critical power and directional (DA) flags. Specific day/night records take precedence over unlimited.
3. Facilities sharing a real call sign and channel within 10 km merge into one station, such as repeated notifications or a site split into day-only and night-only facilities. The newest facility ID becomes the primary ID, and each period's power comes from the newest facility listing it. Older IDs are retained in the `merged` column. Placeholder calls (`NEW`, `XE`, `XENVA…`, blank, numeric) never merge.

## Updating the table

```sh
./tools/update-mw-db.sh    # downloads ~10 MB from the FCC, rewrites app/src/main/assets/mw_stations.tsv
```

The app never downloads station data. The generated table is committed. Its header records the source URL, fetch time, SHA-256 of the raw download, and station/merge counts. Review the diff before committing. The converter refuses to write fewer than 1,000 stations. FCC data is a US government work in the public domain.

Format: `#` metadata lines, a header, then tab-separated `facility call khz country state city class day_kw night_kw critical_kw da lat lon status merged`. Empty power means no authorization for that period; `da` holds `D` and/or `N`.
