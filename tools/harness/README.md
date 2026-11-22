# Engine tuning harness

Python port of the chord stage of `Analyzer.kt` (beat features -> chord segments).
Input: an export from the app ("ייצוא שירים", format 1 with `features`).

- `engine.py` - port; `DEFAULT` = current engine parameters (engine 8)
- `common.py` - loads the export (path in `load()`)
- `truth.py` - tab4u chord sets per song (any transposition)
- `evalh.py` - metrics: `tab` = share of beats whose chord family is in the song's tab4u set;
  `fx_*` = agreement with hand-fixed beats (root / family / exact)
- `grid.py` - single-parameter sweeps

The port reproduces the app's output on 99.5-100% of beats (engine 7 check, Nov 2026).

## tab4u sheets (export format 2)
Songs can carry the tab4u page text (`lyrics`), fetched in the app.
- `qualeval.py <export.json>` - on beats whose detected root appears in the sheet with one chord
  family only: family / exact-quality agreement with the sheet (transposition chosen by sound fit).
- `tabalign.py` / `tabeval.py` - experimental per-beat alignment of the sheet's chord order to
  the beats. Not reliable yet (fast chord changes, sheets that differ from the recording).

Engine 9 (Nov 22 2026, 7 songs with sheets): sus only when the 4th/2nd is >= 0.8 of the root
(was 0.4) and full complexity weight in refine (1.0, was 0.7). Sheet family 83.6% -> 92.2%,
exact quality 61.3% -> 69.5%; detected sus beats 239 -> 105 (sheets had sus on only 11 of them).
