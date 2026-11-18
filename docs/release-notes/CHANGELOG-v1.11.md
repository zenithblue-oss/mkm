# Changelog - MKM v1.11

## Version 1.11 (November 2026)

### Fixes & Improvements
- F-Droid reproducible build: `libfpsbinder.so` is linked with `-Wl,--build-id=none` and compiled with `-ffile-prefix-map`, so the output no longer depends on the build path (#28).

### Build
- New product flavors `fdroid` and `play` (same application ID and signing key).
- `fdroid` keeps the "Download Shizuku" button; `play` hides it because Google Play policy forbids links to external APK downloads.
- F-Droid recipe now builds with `gradle: [fdroid]`.
