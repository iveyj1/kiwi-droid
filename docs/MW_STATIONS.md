# MW (AM broadcast) station list

North American MW station database: a bundled FCC table vetted against the US, Canadian, and Mexican regulators' lists, with Wikipedia programming formats; the stations inside the current passband, search, tune-by-station, receiver locations, likelihood ranking, favorites, and hiding bad entries. Reception logging is described in [LOGBOOK.md](LOGBOOK.md).

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

## Station status and filters

| Status | Meaning | Shown by default |
|---|---|---|
| LIC | Licensed/operational, confirmed by FCC LMS (US), ISED (Canada), or the CRT/IFT list (Mexico) | Yes |
| CP | US construction permit with no licence on that channel, or an ISED authorization not yet operating | No: **Permits** |
| SILENT | US station that FCC LMS lists as "licensed and silent" | No: **Silent** |
| NOTIFIED | Treaty record with no national list to check: Cuba (or, with only the 2014 Mexican list, a row a current Wikipedia article lists on that AM channel) | Yes |
| UNVERIFIED | Mexican treaty record that the national list does not confirm; most such stations have closed or moved to FM | No: **Unverified** |

Each row also shows which list confirmed it ("confirmed by FCC LMS", "ISED", "CRT", or "IFT 2014 list"). The **Also show** chips are remembered.

### Hiding bad entries

Tap ⊘ on a row to report it as a bad entry. It disappears from channel lines, search, and ranking. The **Hidden** view (shown once anything is hidden) lists them; tap ↺ to restore. Hidden entries are stored on the phone by facility ID and channel, so they survive database refreshes.

## Vetting against national lists

The FCC AM Query is the base: it has every station's technical record, including the treaty notifications for other countries. Each refresh checks it against the national regulators' lists and writes `build/mw-import-report.txt` with every decision and the differences from the previous table.

| Country | Official list | Rule |
|---|---|---|
| US | FCC LMS `facility.dat`, daily (public domain) | Drop facilities whose licence is cancelled, void, or permit cancelled. "Licensed and silent" → SILENT. Take the current call sign from LMS. When LMS lists a different frequency, keep the AM Query channel and report it for review (cases include genuine day/night frequency pairs, pending moves, and apparent LMS errors). |
| Canada | ISED broadcasting extract `amstatio.dbf` (`baserad.zip`) | Replace all FCC treaty rows. Keep operational (OP/TO) stations as LIC and authorized ones (AU/AP/CN) as CP; drop allotments, proposals, and unacceptable or deleted records. Day and night records for one call/channel are merged; power, class, and both sites come from ISED. Stations matching an FCC row keep its facility ID (so favorites keep working); others use 1,000,000,000 + the ISED file number. |
| Mexico | CRT "Infraestructura de Estaciones AM/FM" CSV (CC BY 4.0; committed as `tools/data/mx_crt_am_fm.csv`), else the IFT's 26 Nov 2014 list | Each official station is matched to at most one FCC treaty row, in three passes: call and kHz; call alone (the FCC frequency is outdated, so the row moves to the official channel); kHz and city (the FCC call is outdated or a placeholder, so the row takes the official call). Matched rows become LIC; unmatched FCC rows become UNVERIFIED. Coordinates and power still come from the FCC record. Official stations with no FCC record are reported, not added (no coordinates). The 2014 list has no cities, so only the first two passes apply. |
| Cuba | None available | Kept as NOTIFIED. |

The CRT file server rejects scripted downloads, so save the latest CSV from [datos.gob.mx](https://www.datos.gob.mx/dataset/infraestructura_estaciones_am_fm) in a browser into `tools/data/` (any `mx_crt*.csv` name; the newest is used). Columns are found by header name (`distintivo`, `banda`, `frecuencia`, `poblacion`). Without one, the committed `tools/data/mx_ift_am_2014.tsv` (406 stations, call and kHz only) is used; it predates most AM-to-FM migrations and later social/indigenous concessions.

With the March 2026 CRT list (356 AM stations), 328 Mexican rows were confirmed: 272 exactly, 16 moved to their current channel (e.g. XEDF 970 → 1500, XEAI 1500 → 1470), and 40 renamed to their current call (e.g. XESW → XERFR 970). 28 official stations, mostly low-power social and public concessions, have no FCC record. 2,355 Mexican FCC rows are unverified.

On 2026-10-02 this changed the table from 7,823 to 7,320 rows: 541 dead Canadian treaty rows were removed (only 131 of 674 were operational), 43 operational Canadian stations were added, and 141 US stations were marked silent.

## Programming formats

Formats come from the `format` field of English Wikipedia's radio station infobox, stored separately in `mw_formats.tsv` under **CC BY-SA 4.0** (text by Wikipedia contributors; each row names its article). US articles are found through Wikidata's FCC Facility ID (P1400). Canadian and Mexican articles are found by call sign (Wikidata P2317, or the usual titles `CALL (AM)`, `CALL-AM`, `CALL`) and accepted only when the infobox frequency matches the channel, which rejects FM/TV namesakes. Values with leftover wiki markup are discarded.

Coverage on 2026-10-03: 4,265 stations (US 3,915, Canada 123, Mexico 227). Formats appear in station rows and channel lines, and search matches them (e.g. `sports`, `regional mexican`). Formats change and articles can lag; treat them as a hint. Only when the 2014 IFT list is the Mexican fallback does a Wikipedia article listing a station on its AM channel confirm an otherwise unverified row (NOTIFIED, "listed on Wikipedia"), and never when the infobox format says silent or defunct. A current CRT list is authoritative: the rows it omits are mostly duplicate FCC notifications of confirmed stations or stations moved to FM (the list carries their XH… FM call).

## Updating the tables

```sh
./tools/update-mw-db.sh             # FCC AM Query, FCC LMS, ISED (about 35 MB); rewrites mw_stations.tsv
./tools/update-mw-db.sh --formats   # also refreshes mw_formats.tsv from Wikidata/Wikipedia
```

The app never downloads station data. Both generated tables are committed. Review `build/mw-import-report.txt` and the diff before committing. The station table's header records each source, its fetch time and SHA-256, and counts by country and status. The converter refuses to write a table from a truncated input.

Station table: `#` metadata lines, a header, then tab-separated `facility call khz country state city class day_kw night_kw critical_kw da lat lon status merged source`. Empty power means no authorization for that period; `da` holds `D` and/or `N` (FCC rows only). Format table: `facility khz format article`.
