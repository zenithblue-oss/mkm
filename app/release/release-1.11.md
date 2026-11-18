# Release 1.11

<div align="center">
  <img src="https://raw.githubusercontent.com/abhay-byte/mkm/main/assets/logo.png" alt="MKM Logo" width="120"/>
</div>

## Version 1.11 (November 2026)

A small build and store-policy release. There are no feature changes.

### Fixed issues

* **#28** F-Droid reproducible build: the native library `libfpsbinder.so` is now linked without an NDK build-id (`-Wl,--build-id=none`) and compiled with `-ffile-prefix-map`, so it no longer depends on the build machine's paths. F-Droid's build of this tag should match the GitHub APK.

### Build

* The app now has two product flavors:
  * `fdroid`: the F-Droid build and the APK attached to this GitHub release. It keeps the "Download Shizuku" button on the permission screen.
  * `play`: the Google Play build. It no longer links to an external APK download (Google Play policy), so the "Download Shizuku" button is hidden.
* Both flavors use the same application ID (`com.ivarna.mkm`) and the same signing key.
* F-Droid recipes must now build the `fdroid` flavor (`gradle: [fdroid]`).

## Past Releases

* [v1.10](https://github.com/zenithblue-oss/mkm/releases/tag/v1.10) (November 2026) - Overlay FPS, temperature and interval fixes, lower self-drain, safety fixes, governor tunables, core control, zram/VM tunables, UFS health, swap manager, battery health and charge limit, per-app auto boost. Also contains 1.8 and 1.9.
* [v1.7](https://github.com/abhay-byte/mkm/releases/tag/v1.7) (June 2026) - Discord community card, Quick Access grid, overlay appearance options, CPU frequency display mode, overlay accent color.
* [v1.6](https://github.com/abhay-byte/mkm/releases/tag/v1.6) (June 2026) - Battery stats automation, navigation drawer fixes, shell timeout fixes, Settings crash fix, monochrome icon.
* [v1.5](https://github.com/abhay-byte/mkm/releases/tag/v1.5) (June 2026) - Battery monitoring fixes, duplicate notification cleanup, live capacity and ETA improvements.
* [v1.4](https://github.com/abhay-byte/mkm/releases/tag/v1.4) (June 2026) - Battery settings page, configurable update interval, massive battery self-drain reductions (<1%), and improved awake drain logic.
* [v1.3](https://github.com/abhay-byte/mkm/releases/tag/v1.3) (May 2026) - Battery monitor, Apply on Boot, Shizuku Hidden App Support.
* [v1.2](https://github.com/abhay-byte/mkm/releases/tag/v1.2) (February 2026) - Power monitoring, calibration, battery stats.
* [v1.1](https://github.com/abhay-byte/mkm/releases/tag/v1.1) (February 2026) - Non-root access via Shizuku, UI improvements.
* [v1.0](https://github.com/abhay-byte/mkm/releases/tag/v1.0) (January 2026) - Initial release with root-only access, persistent swap, and performance overlay.
