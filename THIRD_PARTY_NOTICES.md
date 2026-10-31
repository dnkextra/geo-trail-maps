# Third-party notices

The [MIT License](LICENSE) applies to original Geo Trail Maps code, documentation,
and artwork. Third-party material retains its own copyright and license.
The MIT license does not relicense dependencies, imported maps, downloaded
tiles, or other people's trademarks.

## Libraries

The Android runtime dependencies and their transitive dependencies use the
[Apache License 2.0](licenses/Apache-2.0.txt). The dependency versions are
declared in `gradle/libs.versions.toml`; transitive versions are resolved by
Gradle. Attribution belongs to the respective upstream authors:

| Components | Copyright holders / upstream | License |
| --- | --- | --- |
| AndroidX, Jetpack Compose, Material components and icons | The Android Open Source Project and contributors; https://android.googlesource.com/platform/frameworks/support/ | Apache-2.0 |
| Kotlin standard library | JetBrains s.r.o. and Kotlin Programming Language contributors; https://github.com/JetBrains/kotlin | Apache-2.0 |
| kotlinx.coroutines | JetBrains s.r.o. and contributors; https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0 |
| kotlinx.serialization | JetBrains s.r.o. and contributors; https://github.com/Kotlin/kotlinx.serialization | Apache-2.0 |
| JetBrains annotations | JetBrains s.r.o. and contributors; https://github.com/JetBrains/java-annotations | Apache-2.0 |
| Guava ListenableFuture | The Guava Authors; https://github.com/google/guava | Apache-2.0 |
| JSpecify annotations | The JSpecify Authors; https://github.com/jspecify/jspecify | Apache-2.0 |

Retain upstream copyright, license, and applicable NOTICE files when
redistributing these components. This summary does not replace those notices.
Android's PDF renderer is provided by the operating system; this project does
not bundle GDAL, QGIS, or QField libraries.

## Build and test components

The checked-in Gradle wrapper scripts and `gradle/wrapper/gradle-wrapper.jar`
are from [Gradle](https://github.com/gradle/gradle), under Apache-2.0. The scripts
retain their original copyright notices (2015–2021, the original authors).
The Android Gradle Plugin and Kotlin build plugins are downloaded build tools
under their respective upstream terms.

JUnit 4.13.2 is a test dependency, under the
[Eclipse Public License 1.0](licenses/JUnit-EPL-1.0.txt), copyright its respective
contributors; https://github.com/junit-team/junit4. Its Hamcrest Core 1.3
dependency is under [BSD-3-Clause](licenses/Hamcrest-BSD-3-Clause.txt),
copyright 2000–2006 hamcrest.org; https://github.com/hamcrest/JavaHamcrest.
These test libraries are not packaged in the app. They do not change the
license of the project's own test code.

## Test maps

The four PDFs in `app/src/test/resources/geopdf/` are unmodified copies from
the [GDAL autotest suite](https://github.com/OSGeo/gdal/tree/master/autotest/gdrivers/data/pdf).
They retain GDAL's upstream licensing, separately from the project license.
The full upstream [GDAL licensing file](licenses/GDAL-LICENSE.txt) is preserved,
including its general MIT-style permission notice and file-specific notices.
See the [fixture README](app/src/test/resources/geopdf/README.md) for provenance
and checksums. The PDFs are used only in tests and are not packaged in the app.

## Online maps and imported data

Map data and online services have terms separate from the software license.
No background tiles are bundled in this repository or the APK.

- **OpenStreetMap:** © OpenStreetMap contributors. The underlying database is
  licensed under ODbL 1.0. Attribution and data-license information:
  https://www.openstreetmap.org/copyright. Use of the public tile server is
  subject to https://operations.osmfoundation.org/policies/tiles/; it does not
  permit bulk downloading or offline prefetch features. Keep the existing
  visible map attribution and its link.
- **Satellite imagery:** Sentinel-2 cloudless by EOX IT Services GmbH
  (contains modified Copernicus Sentinel data 2025). The app fetches
  `s2cloudless-2025` imagery from EOX. EOX's published terms provide
  noncommercial use under CC BY-NC-SA 4.0 and require a separate EOX license
  for commercial use. See https://cloudless.eox.at/documentation/license and
  https://maps.eox.at/. Keep the visible provider attribution. The project's
  MIT license does not grant commercial rights to this imagery or permission
  to redistribute it under MIT; commercial users must obtain the appropriate
  rights or use another provider.
- **User-imported maps and features:** remain under their owners' terms.
  Opening, recording, tracing, or exporting data does not by itself grant
  rights to redistribute someone else's map or data.

## Names and trademarks

This project is independent of Avenza Systems Inc. and is not endorsed by it.
Avenza's names and marks remain its property; see
https://www.avenza.com/legal/trademarks/. The project license does not grant
rights to third-party trademarks.

GeoPDF is a registered trademark of TerraGo Technologies, Inc. This project
is not affiliated with TerraGo. It reads geospatial PDFs in the ISO 32000 and
OGC encodings, and uses the term GeoPDF only to describe the files it opens.
