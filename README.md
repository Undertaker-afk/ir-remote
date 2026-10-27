# IR Remote

An open-source Android universal IR remote in the style of Mi Remote, driven by the phone's built-in IR blaster
(`ConsumerIrManager`). English and German UI.

## Flow (mirrors Mi Remote)

1. **Add device**: category grid (TV, set-top box, AC, fan & heater, streaming box, A/V receiver & audio,
   DVD/Blu-ray/VCR, projector, camera, LED lighting, cleaning robot, console & computer).
2. **Brand list**: search, popular brands, A-Z rail.
3. **Auto-match wizard**: "Is the TV on?" then test with *Mute* (on) or *Power* (off). Remotes that send the same
   test code are merged into one candidate, ranked by how many remotes share it ("1/15").
4. **Remote screen**: power/input/menu, home/number pad/back, D-pad with OK, volume and channel pills (hold to
   repeat), mute, and a "..." sheet with every remaining key. AC, fan, light and cleaner remotes open as an
   all-keys grid because their codes are per-state, not per-key.
5. **Import** any Flipper `.ir` file from storage.

Saved remotes live in the app's private storage and are self-contained.

## Code database

Two databases are merged into compact `app/src/main/assets/db/*.txt` files by `tools/build_db.py`:

- **[Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB)** (CC0).
- **[probonopd/irdb](https://github.com/probonopd/irdb)** (own licence, see below). Rows using NEC/NEC1/NEC2, NECx, Sony 12/15/20,
  RC5, Panasonic and JVC are converted; other irdb protocols (Denon, Mitsubishi, Aiwa, RC6, ...) are skipped for now.
  Remotes whose buttons duplicate a Flipper remote are dropped, brands are merged case-insensitively.

Together that is ~2,900 remotes. To refresh the data:

    git clone --depth 1 https://github.com/Lucaslhm/Flipper-IRDB
    git clone --depth 1 https://github.com/probonopd/irdb
    python3 tools/build_db.py Flipper-IRDB app/src/main/assets/db irdb

### irdb licence obligations

`app/src/main/assets/IRDB_LICENSE.md` is the upstream licence. Before distributing the app you must (1) open an issue at
https://github.com/probonopd/irdb/issues about your product, (2) keep the notice shown in the app's home screen footer, and
(3) provide up to three copies/units to the irdb team on request. Non-compliance revokes the permission.

Protocols are encoded from "parsed" entries (NEC, NECext, NEC42, Samsung32, RC5/RC5X, RC6, SIRC 12/15/20, Kaseikyo,
RCA, Pioneer) following the Flipper Zero firmware encoders; "raw" entries (mostly air conditioners) are replayed as-is.

### Other databases considered

| Source | Notes |
| --- | --- |
| [probonopd/irdb](https://github.com/probonopd/irdb) | Largest (~3,200 CSV files, 640+ manufacturers). Bundled, see above. |
| [Flipper-IRDB `_Converted_`](https://github.com/Lucaslhm/Flipper-IRDB) | Pronto/CSV/IRPlus conversions of other databases; not used (duplicates). |
| LIRC remotes DB | Many older devices; not yet imported (conf format needs a parser). |
| [iodn/android-ir-blaster](https://github.com/iodn/android-ir-blaster) | Related open-source app: also imports LIRC/IRPLUS and supports USB/audio IR dongles. |

## Build

    gradle :app:testDebugUnitTest :app:assembleDebug     # needs the Android SDK (compileSdk 34)

## Limitations

- Needs a phone with an IR emitter; without one the UI shows a warning and sends nothing.
- Not yet tested on real hardware; encoders are unit-tested for framing only.
- Flipper-IRDB has far fewer TV brands than Mi Remote's cloud database, and AC remotes are replayed raw (no temperature
  control logic).
- Next ideas: LIRC/irdb import, USB/audio IR dongles for phones without an emitter, per-brand AC protocol generation,
  IR learning.
