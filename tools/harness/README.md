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
