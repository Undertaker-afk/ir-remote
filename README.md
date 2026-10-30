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
6. **Model search** (magnifier on *Add device*): type a brand or model number (e.g. `UE40D5000`) and add that exact remote.
7. **Smart A/C** (Gree and rebadges, Coolix, Midea family, LG): temperature, mode, fan and swing are sent as complete
   state frames like a real A/C remote. The wizard tries the protocols that fit the brand first, then recorded keys.
   Frame layouts follow [IRremoteESP8266](https://github.com/crankyoldgit/IRremoteESP8266) and the unit tests check them
   against its test vectors. Not yet: Daikin, Mitsubishi, Samsung, Fujitsu, Panasonic A/C.
7a. **Find nearby device**: the Cast icon on *Add device* searches for TVs and casting targets the way the screen-mirroring
   list does: mDNS (Google Cast, AirPlay), SSDP/UPnP (Smart View, DLNA, DIAL, Roku) and Wi-Fi Direct (Miracast peers, asks for
   the nearby-devices permission). Pick a device and the app reads its brand/model, shows the best matching remotes and
   offers the brand wizard. Phone and TV must be on the same Wi-Fi; devices that don't announce a model fall back to the
   brand from their name.
7b. **Model matching engine** (`MatchEngine.kt`): model numbers are parsed into size, series, year and region suffix
    (Samsung, LG, Sony, Philips, Panasonic, TCL, Hisense...; `UE43CU7000AUXXH` and Samsung's AirPlay name `UCU7000` both
    become series `CU7000`), and remote part numbers are compared without their revision letter (`BN59-01315B` ~
    `BN59-01315J`). Results carry a tier: exact, same remote/other revision, same series, neighbouring series, same
    generation (shares the power code of models from nearby years) or brand only, and the reason is shown. Search, *Find my
    code*, *Find nearby device* and the wizard (candidates ordered by model fit) all use it. Accuracy on the bundled data
    (`MatchEngineTest`, leave-one-out: same model typed with another size/region): series 78/78, part-number revisions
    28/28. It can only point to remotes that exist in the database, and TV *serial* numbers carry no remote information.
    Verified on hardware: a Samsung Crystal UHD TV accepted the hand-built `.ir` remote, while the community database's own
    `BN59-01388` entry did not (OK was a copy of Right, volume and channel swapped). Curated remotes therefore replace any
    database entry with the same part number, and `RemoteLint` drops entries that are certainly corrupt (direction keys all
    identical, or channel/volume up == down; currently 2 of 587 TV remotes), while keeping legitimate dual-function keys.
    Curated additions also include `Panasonic N2QAYB000572` (2011-12 VIERA remote, compiled from its superseding remote
    N2QAYB000752 and sibling captures; 47 of 56 keys confirmed by two or more sources) and the Telekom MagentaTV remote
    (MR201/MR401/MR601, decoded from two Flipper-IRDB captures) plus a trimmed entry for the MagentaTV One (2nd gen) remote SRC-3512,
    which uses Bluetooth for most functions while the box's IR receiver accepts the Media Receiver codes; ready-made `.ir` files are in `remotes/`).
    A few hand-curated remotes live in `assets/curated/` (e.g. Samsung Crystal UHD 2019-2022 and BN59-01388 for the 2023-24 CU7000/DU7200 series) and are added to either data source.
7c. **Online lookup** (`web/`): when no series-level match exists, *Look up online* searches the web for the model's
    remote part number and feeds the result back into the matching engine (so `UCU7000` -> `BN59-01388A` -> the
    `Samsung BN59-01388` remote). It asks for consent first and sends only the brand and model text; answers are cached for
    30 days. Results are ranked by a vote: a part number counts only if the page ties it to the model (title, or the model within
    a few hundred characters), and pages listing dozens of parts are down-weighted. Several valid variants are reported with
    listing counts and a confidence level instead of a single guess. Providers (Home menu -> *Online lookup*):
    Exa's hosted MCP server (default, **no key**, free plan), Tavily (key), Brave Search (key), your own SearXNG (URL).
    Tested against a real Exa response (fixture in `src/test/resources`); `LIVE_EXA=1 gradle :app:testDebugUnitTest
    --tests '*LiveExaTest*'` runs the real endpoint.
7d. **Key doesn't work…** (remote menu): tap the broken key and the app proposes replacement codes that other remotes of the
    same device family (same protocol + address) agree on, including swapped pairs such as volume/channel. Each suggestion can
    be tested on the device and applied to your saved remote; *Share report* sends a plain-text report. On the real broken
    `BN59-01388` entry it proposes OK = `0x68` (35 remotes agree) and volume up = `0x07` swapped with channel (46 agree).
8. **Find my code**: photograph a model sticker (or pick a photo); text is read on-device with ML Kit and matched against
   the database, model codes outranking brand names. The recognised text is editable.
9. **Custom layout**: per remote, choose any buttons, rename and order them ("Edit my layout" in the remote menu).
10. **Volume keys**: optionally let the phone's volume buttons send the remote's volume up/down (or temperature up/down on
    smart A/C remotes) while that remote is open.
11. **Import anything**: *Import file…* / *Paste code…* on the home screen read Flipper `.ir`, LIRC `.conf` (raw codes and
    space-encoded NEC/Samsung-style codes), irdb CSV, Pronto hex (one code per line, optional `Name: code`) and raw timing
    dumps such as Arduino `rawData[] = {...}`. Biphase LIRC remotes (RC5/RC6 flags) are skipped.
12. **Find code automatically**: sends the power code of every known remote in a category, most common first, with a
    adjustable pause. Tap the big button when the device reacts, then confirm with *Send again* / previous / next (the device
    reacts a moment after the code). For A/C the smart protocols are tried first.
13. **Rooms**: give a remote a room (⋮ menu → *Room…*); the home screen groups devices by room and each room has
    *All off* (devices with a single power toggle are toggled, so the dialog warns about it).
14. **Database update**: ⋮ menu on the home screen → *Code database* downloads fresh Flipper-IRDB and irdb snapshots from
    GitHub (~20 MB), rebuilds the lists on the phone and swaps them in atomically; *Use bundled* goes back. The in-app builder
    (`DbBuilder.kt`) is a port of `tools/build_db.py`; `DbBuilderParityTest` checks that both give identical output
    (`IR_PARITY_DIR=<dir with flipper.zip, irdb.zip, expected/> gradle :app:testDebugUnitTest`). Saved remotes are
    self-contained and never touched by an update.
15. **Shortcuts and automation**
   - Long-press the app icon for "open" and "power" shortcuts of your most recent remotes (Google Assistant can launch
     shortcuts by name).
   - Headless intent, usable from Tasker, MacroDroid, routines or `adb`:

         adb shell am start -a app.irremote.SEND --es remote "Samsung TV" --es key VOL_UP
         adb shell am start -a android.intent.action.VIEW -d "ir-remote://send?remote=Living%20room%20AC&key=power_off"

     `remote` is the saved name (or part of it) or id. `key` is a slot (`power`, `vol_up`, `vol_down`, `ch_up`, `mute`,
     `ok`, `up`, `home`, `5`, ...) or an exact button name. Smart A/C remotes accept `power`, `power_on`, `power_off`,
     `temp_up`, `temp_down`.

Saved remotes live in the app's private storage and are self-contained.

## Code database

Two databases are merged into compact `app/src/main/assets/db/*.txt` files by `tools/build_db.py`:

- **[Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB)** (CC0).
- **[probonopd/irdb](https://github.com/probonopd/irdb)** (own licence, see below). Rows using NEC/NEC1/NEC2 (+f16), NECx, Sony 12/15/20,
  RC5, RC6, MCE, Panasonic (+old), JVC, Denon (+K), Sharp, Mitsubishi, Aiwa, Pioneer, Proton, Akai, Fujitsu and RCA-38 are
  converted; others (Dish, DirecTV, G.I. Cable, RECS80, Nokia32, XMP, Tivo) are skipped for now.
  Remotes whose buttons duplicate a Flipper remote are dropped, brands are merged case-insensitively.

Together that is ~3,300 remotes. To refresh the data:

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
- Flipper-IRDB has far fewer TV brands than Mi Remote's cloud database, and only four A/C families have temperature/mode control (others replay recorded keys).
- Next ideas: more A/C protocols (Daikin, Mitsubishi, Samsung), USB/audio/Wi-Fi IR bridges for phones without an
  emitter, IR learning, macros and scenes, widgets.
