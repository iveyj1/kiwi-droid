# MW (AM broadcast) station list

Phase 1 of the North American MW station database: a bundled FCC table, the stations inside the current passband, search, and tune-by-station. Receiver locations, likelihood ranking, favorites, and logging are later phases.

## Using it

The MW section appears only while the tuned frequency is **520–1710 kHz**; other bands keep the plain tuning screen.

- Below **Tuned:**, each 10 kHz channel (530–1700 kHz) whose carrier lies inside the current RF passband is listed with its strongest licensed station, e.g. `1030 kHz: WBZ Boston, MA, US · 50 kW`. `(+n)` counts other stations on that channel. Wide AM tuned between channels shows both neighbours; narrow CW/CWN between channels shows none.
- Tap a channel line or **MW stations** to open the list. **In passband** limits it to those channels; **All** covers the band. Search matches a kHz prefix (`10` → 1000–1090), a call-sign prefix, or part of the city/state/country.
- Tapping a station tunes to its carrier. AM, SAM/SAU/SAL/SAS, USB, and LSB are kept; CW, CWN, and IQ switch to AM.
- On a channel, stations are ordered by licensed power. This is not a reception prediction yet: there is no receiver location, propagation model, or directional pattern.

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
