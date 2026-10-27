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

The bundled data is **[Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB)** (CC0): ~1,900 remotes for ~700 brand
entries across the categories above, about 25,000 buttons. `tools/build_db.py` converts a checkout into the compact
`app/src/main/assets/db/*.txt` files; re-run it to refresh the data:

    git clone --depth 1 https://github.com/Lucaslhm/Flipper-IRDB
    python3 tools/build_db.py Flipper-IRDB app/src/main/assets/db

Protocols are encoded from "parsed" entries (NEC, NECext, NEC42, Samsung32, RC5/RC5X, RC6, SIRC 12/15/20, Kaseikyo,
RCA, Pioneer) following the Flipper Zero firmware encoders; "raw" entries (mostly air conditioners) are replayed as-is.

### Other databases considered

| Source | Notes |
| --- | --- |
| [probonopd/irdb](https://github.com/probonopd/irdb) | Largest (~3,200 CSV files, 640+ manufacturers, protocol/device/subdevice/function). **Not bundled**: its licence requires notifying the project, a visible notice and supplying units on request. Easy to add as a second source if you comply. |
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
