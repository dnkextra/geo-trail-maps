# Untrusted map import security review

Reviewed 2026-10-30 at `576100c`. The small fixes are done and tested. One item remains.

## Open: native PDF rendering runs in the app process (P1, deferred)

`PdfRenderer` renders untrusted PDFs in the app's own process: `PdfPageRenderer.kt`, used for import thumbnails (`MapLibrary.kt`) and tiles (`MapSession.kt`).

- A PDFium exploit would have the app's access: the map library, tracks, network and location. Not tested; PDFium not audited.
- A native crash kills the app, including track recording.
- [Android recommends](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer#PdfRenderer(android.os.ParcelFileDescriptor)) an isolated process for untrusted PDFs.
- Fix: an unexported `android:isolatedProcess="true"` service, given a read-only descriptor for the one file. Pass bitmaps through shared memory, since Binder transactions are limited to about 1 MB. Report a worker death or timeout as an import or render failure. A separate process with the same UID isn't enough.
- Deferred: it's a big change and adds latency to every tile. Worth doing if the app is distributed widely.

## Already safe

- Storage paths are generated UUIDs plus `map.pdf`. Provider names and ZIP entry names are never used as paths.
- KMZ entries aren't extracted to disk. Their combined expansion is limited to 64 MiB.
- Out-of-range, NaN and infinite coordinates are skipped.
- Imported text appears only in Compose text. There's no WebView or HTML rendering, imported links are never opened, and KML URLs aren't fetched.
- Exports escape XML and sanitize filenames. The FileProvider only covers `cache/exports/`. The provider and the recording service aren't exported.
- Feature saves write a temp file and then rename it.
- Custom PDF parsing runs inside `runCatching`, so a malformed file only loses georeferencing or trail lines.
- Not fixable by checking input: a stranger's map can still have wrong georeferencing or false trails.
