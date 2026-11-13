# Release 1.10

<div align="center">
  <img src="https://raw.githubusercontent.com/abhay-byte/mkm/main/assets/logo.png" alt="MKM Logo" width="120"/>
</div>

## Version 1.10 (November 2026)

This release also contains everything from 1.8 and 1.9, which were not published as separate GitHub releases.

### Fixed issues

* **#15** Charging power now has the correct sign. The battery current sign convention is learned per device.
* **#16** Overlay FPS fixed: frames with a pending fence are no longer read as 0, Android 12+ BLAST layers are supported, an idle screen shows 0 instead of 60/120, and a badge shows the FPS source.
* **#17** The overlay update interval is honored, and the overlay pauses while the screen is off.
* **#18** The status bar icon can show up to 2 metrics, and network speed is available as a metric.
* **#19** Overlay battery temperature fixed (unit handling plus a BatteryManager fallback).
* **#20** Theme and language choices are saved.
* **#21** The Shizuku permission prompt is shown once and a denial is remembered. The permission screen refreshes and shows backend errors. Added `<queries>` for Shizuku.
* **#22** Quick Settings tile for the overlay.
* **#25** Swap creation now explains that it needs root (the ADB/Shizuku uid cannot run `swapon`). Swap create, resize and delete are safer.
* **#23 / #27** CPU min/max requests snap to frequencies the kernel supports, and a clear error is shown when a kernel or vendor limit keeps a different value.
* **#3** Much lower MKM self-drain: pages poll only while visible, the overlay stops when the screen is off, and fewer shell calls are made.

### Safety fixes

* Thermal "disable" no longer stops `logd`, and the thermal limit is written only to CPU zones.
* Swap paths are validated (prevents root command injection and arbitrary deletes).
* Fixed shell injection through FPS layer names.
* Fixed a boot-apply deadlock with Game Boost.
* Boot safe mode: a Cancel action, and apply-on-boot is disabled automatically after a failed boot.
* No more `chmod` on probe paths; root commands have timeouts; devfreq and UFS writes are validated.
* RAM frequency is no longer locked at boot.
* Battery stats reset on unplug/full now works; fixed an ANR in the battery tracker; fixed deep-sleep and drain math.
* Exynos/Tensor GPU support and UFS detection on Qualcomm.

### New features

* **CPU:** throttling indicator, governor tunables editor, CPU core online/offline.
* **Thermal:** restore button.
* **RAM/Storage:** zram and VM tunables panel, UFS health (wear/EOL), I/O scheduler, read-ahead and fstrim, swap file manager.
* **Battery:** health card (cycles, health trend), charge limit, top battery drainers.
* **FPS:** frame-time stats (1% / 0.1% lows, jank), session CSV/JSON export.
* **Game Boost:** per-app auto boost, Quick Settings tile.
* **Settings:** tuning profile export/import.

### Build

* The in-app build date now comes from the commit date (or `SOURCE_DATE_EPOCH`), so F-Droid reproducible builds of this tag match the GitHub APK.

## Past Releases

* v1.9 (September 2026) - Global Game Boost, AGP 9.3 / SDK 37 toolchain, FPS accuracy fixes. Included in this release.
* v1.8 (August 2026) - FPS Session Recording, root GPU collectors, overlay interval slider, power polarity fixes. Included in this release.
* [v1.7](https://github.com/abhay-byte/mkm/releases/tag/v1.7) (June 2026) - Discord community card, Quick Access grid, overlay appearance options, CPU frequency display mode, overlay accent color.
* [v1.6](https://github.com/abhay-byte/mkm/releases/tag/v1.6) (June 2026) - Battery stats automation, navigation drawer fixes, shell timeout fixes, Settings crash fix, monochrome icon.
* [v1.5](https://github.com/abhay-byte/mkm/releases/tag/v1.5) (June 2026) - Battery monitoring fixes, duplicate notification cleanup, live capacity and ETA improvements.
* [v1.4](https://github.com/abhay-byte/mkm/releases/tag/v1.4) (June 2026) - Battery settings page, configurable update interval, massive battery self-drain reductions (<1%), and improved awake drain logic.
* [v1.3](https://github.com/abhay-byte/mkm/releases/tag/v1.3) (May 2026) - Battery monitor, Apply on Boot, Shizuku Hidden App Support.
* [v1.2](https://github.com/abhay-byte/mkm/releases/tag/v1.2) (February 2026) - Power monitoring, calibration, battery stats.
* [v1.1](https://github.com/abhay-byte/mkm/releases/tag/v1.1) (February 2026) - Non-root access via Shizuku, UI improvements.
* [v1.0](https://github.com/abhay-byte/mkm/releases/tag/v1.0) (January 2026) - Initial release with root-only access, persistent swap, and performance overlay.
