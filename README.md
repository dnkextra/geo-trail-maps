# Geo Trail Maps

An offline Android map viewer for geospatial PDFs, with GPS, track recording and trail distances.

## License

Original project code, documentation, and artwork are available under the
[MIT License](LICENSE), including for commercial use. Third-party components,
test maps, and online map services retain their own terms; see
[Third-party notices](THIRD_PARTY_NOTICES.md).

The APK bundles `LICENSE`, `THIRD_PARTY_NOTICES.md`, and the Apache License text,
shown under Settings → Open-source licenses. When distributing an APK, also
include them and the `licenses/` directory in the accompanying distribution
materials. Keep the on-map provider credits visible. Review the notices when
changing dependencies or map providers; release builds fail if a runtime
dependency isn't covered (`./gradlew checkLegalNotices`).

## What it does

An offline Android map viewer for geospatial PDFs, often called GeoPDFs. It opens the PDF maps that Avenza Maps imports and exports.

- **My Maps**: import geospatial PDFs (several at once) with the file picker, or open one from another app with "Open with" / "Share". Each map shows a thumbnail and how far you are from it. Put maps in folders, and sort by name, distance or date added. *Archive* (in a map's menu) moves a map to the *Archived* section at the bottom of the list, and *Unarchive* brings it back. *Export PDF* (in a map's menu, or the map view's menu) saves the original PDF again, in case you've lost it.
- **Map view**: pan, pinch zoom, fling and double-tap zoom, with sharp re-rendering at any zoom level. Shows a GPS dot with an accuracy circle and compass heading (grey when the GPS fix is old), a coordinate readout, a scale bar, and an "off map" distance with direction. Tap the locate button once to follow your position, and again for compass mode, where the map turns with you. If you're standing on a different imported map, the app offers to open it.
- **Track recording**: record a track that keeps going with the screen off, pause and resume it, and see distance, time and ascent. Moving while paused, or continuing a track somewhere else, starts a new segment: the gap isn't counted or drawn, and GPX export writes each segment as its own `trkseg`. Tracks belong to no one map: they're saved to a *Recorded tracks* layer that shows on every map they cross, keep each point's time and elevation (exported to GPX), and can be continued later. Recording can be paused or stopped from any map, from *Tracks* in My Maps (which lists every track, including any no map shows), or from its notification. Resuming far from where a track was paused asks first. An unfinished track is recovered if the app is closed.
- **Long press** on the map marks the spot with a crosshair and offers *Copy* (its coordinates), *Find route* (along trails from where you are) and *Add point*.
- **Go to a placemark**: from a placemark's editor, *Navigate* gives the distance and bearing to it, with an arrow and a line on the map.
- **Search / go to**: type coordinates in any supported format, or part of a placemark's name or notes.
- **Adding points, lines and areas**: tap *+*. *Point* places a marker with the crosshair, which starts at your position; *Line* and *Area* are drawn by tapping points, with their length or area shown as you go. Each has a name, notes and a colour, and you can copy a point's coordinates. They're listed under *Layers*.
- **Navigate**: tap points to measure the way between them, along trails (the default) or straight (*Distance*), and save it as a line.
- **Trail distance**: *Navigate → Trails* measures the walk between the points along trails, taking the shortest way. The trails are the lines in the PDF's own layers named like trails, paths or roads, imported layers you turn on with the hiking button under *Layers* (choosing which KML folders count), single lines with *Use as trail* in their sheet, and trails you add. Lines are joined where they cross or end within 10 m of each other; parts with no trail are measured straight and shown dashed.
- **Adding trails**: *Layers → Add trails*. *Draw* a trail by tapping points (they snap onto trails nearby, so junctions connect), then *Next trail* for the next one. *Pick from map* takes lines printed in the PDF: lines that can be picked have a faint tint; tap them to select, or select every line drawn the same way at once. Undo steps back through points and selections. Nothing is added until you *Confirm*; closing with trails pending asks whether to add or discard them. Added trails go into the "My trails" and "From the map" layers, which aren't drawn on the map unless you tick them.
- **Import**: add GPX, KML or KMZ files: waypoints, tracks, routes, paths and polygons. Each file becomes a layer under *Layers* (a GPX track or KML placemark in several parts stays one track, with gaps between the parts), hidden at first so it doesn't cover the map, and you can show, rename or delete a whole layer or anything in it. *Show on all maps* makes a layer appear on every map it overlaps instead of just this one.
- **Export**: share features as KML (each imported layer becomes a folder) or GPX.
- **Background map**: OpenStreetMap or satellite tiles drawn under and around the PDF, so you can see what's beyond the map's edges. Tiles you've viewed are cached and may show offline; areas or zoom levels not viewed online won't. Each map remembers its own choice (or none) under *Layers → Background map*.
- **Settings**: choose the coordinate format (decimal, DMS, DDM, UTM) and metric or imperial units.

Requires Android 17 (API 37). Doesn't need Google Play Services. The only feature that uses the network is the optional background map.

## Build

```
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # parser / geo / export tests
adb install app/build/outputs/apk/debug/app-debug.apk
```

The release build (`assembleRelease`) is minified to about 2.4 MB and signed with the release key (see below).

## Releases and signing

Release APKs are attached to GitHub releases (`vX.Y.Z`). To cut one, bump `versionCode` and `versionName` in `app/build.gradle.kts`, then:

```sh
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk geo-trail-maps-<version>.apk
apksigner verify --print-certs geo-trail-maps-<version>.apk | grep SHA-256
# must be 857b0ecd5204161181ca9d74a70e19175dee31e0057dbab6614523ae3bd8f13a
zip -r geo-trail-maps-<version>-licenses.zip LICENSE THIRD_PARTY_NOTICES.md licenses/
gh release create v<version> geo-trail-maps-<version>.apk geo-trail-maps-<version>-licenses.zip --title v<version> --notes "..."
```

The signing material lives outside the repo:

```
~/sandbox/.signing/geo-trail-maps/
├── release.keystore    # RSA 4096, valid to 2056, alias "geotrailmaps"
├── credentials.env     # KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
└── certificate.txt     # fingerprints, for checking an APK against the key
```

The build looks first for a copy of `release.keystore` and `credentials.env` in the project root, then in that folder. Both are gitignored. To use another location, pass `-PgeoTrailMaps.signingDir=...` or set `GEO_TRAIL_MAPS_SIGNING_DIR`.

That folder is backed up in Bitwarden as the secure note `geo-trail-maps — release signing`, in the `Agent-managed` folder. The note holds a base64-encoded `.tar.gz` of the folder, and its hidden fields hold the keystore passwords and alias. To restore, decode the archive into `~/sandbox/.signing/geo-trail-maps/`.

**Update that backup whenever the key or credentials change.** Android identifies an app by its signing key. If you lose the key, no future build can install over an existing one, and the only fix is to uninstall and reinstall, which deletes all imported maps and placemarks.

If the build can't find the key, `assembleRelease` fails at `preReleaseBuild`. An APK signed with any other key can't update an existing install (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so check the certificate fingerprint above before publishing. Debug builds don't need the key.

## Code layout

```
app/src/main/java/org/geotrailmaps/
  pdf/PdfFile.kt            minimal PDF object reader (xref tables/streams, object streams, Flate)
  geo/GeoPdfParser.kt       reads georeferencing: ISO 32000 /VP+/Measure and OGC /LGIDict
  geo/GeoReference.kt       page <-> lat/lon transform, neatline, distance from map
  geo/GeoMath.kt            homography fit, local transverse Mercator, UTM, geodesy
  geo/TrailNetwork.kt       walkable lines joined into a network; shortest routes along it
  geo/PdfLayerLines.kt      stroked lines in the PDF, with how they're drawn, or of named layers (e.g. "Trails")
  ui/map/MapLines.kt        the PDF's lines that can be picked as trails
  render/PdfPageRenderer.kt Android PdfRenderer wrapper (renders page regions)
  render/BasemapTiles.kt    OpenStreetMap tile fetching and caching (memory + disk)
  data/                     map library storage, features + layers, settings, formatting
  data/FeatureImport.kt     GPX / KML / KMZ import;  data/FeatureExport.kt  KML / GPX export
  data/CoordinateParser.kt  typed coordinates (decimal, DMS, DDM, UTM)
  track/                    track recorder + foreground location service
  location/                 GPS + compass (platform LocationManager)
  ui/theme/Theme.kt         <- app colours (AppTheme) and map overlay styling (MapStyle)
  ui/MapListScreen.kt       "My Maps"
  ui/map/MapScreen.kt       map screen layout: top bar, status line, toolbar, measure panel
  ui/map/MapView.kt         pan/zoom canvas
  ui/map/MapOverlay.kt      drawing of location, pins, lines, measurement
  ui/map/FeatureSheets.kt   placemark editor and feature list
```

To restyle the app, start with `ui/theme/Theme.kt`. Screen layouts are plain Compose in `ui/`.

## How georeferencing works

The parser reads the control points from the PDF: GPTS lat/lon paired with LPTS page positions. It flattens lat/lon with a transverse Mercator projection centred on the map, then fits a projective transform to the page. This means no projection database (PROJ/GDAL) is needed. In a test, a 60 km UTM map georeferenced only by its corners came out within 5 m.

## Limitations

- `.avenzamap` files are Avenza's encrypted, proprietary format and can't be opened. Ask the sender for a geospatial PDF of the map.
- GeoTIFF, multi-page PDFs (only page 1 is used; import says so) and password-protected PDFs aren't supported.
- Datum shifts are ignored: NAD83 and WGS84 are treated as the same. Old NAD27 maps can be off by tens of metres; import and *Map info* warn when the coordinate system names such a datum.
- There's no backup and restore inside the app. Android's backup may include maps and tracks but is limited to 25 MB per app; export features (GPX/KML) and keep the original PDFs before switching phones.
