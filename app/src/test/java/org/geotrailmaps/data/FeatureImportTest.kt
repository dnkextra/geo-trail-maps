package org.geotrailmaps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.geotrailmaps.geo.LatLon
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FeatureImportTest {

    private fun parse(name: String, text: String) = FeatureImport.parse(name, text.toByteArray(), 0xFF00FF00.toInt())

    @Test
    fun gpxWaypointsTracksAndRoutes() {
        val data = parse(
            "walk.gpx",
            """<?xml version="1.0"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>Sunday walk</name></metadata>
              <wpt lat="10.1" lon="-84.1"><name>Cabin</name><desc>Water here</desc></wpt>
              <wpt lat="999" lon="0"><name>Bad</name></wpt>
              <trk><name>Loop</name>
                <trkseg><trkpt lat="10.0" lon="-84.0"/><trkpt lat="10.01" lon="-84.01"/></trkseg>
                <trkseg><trkpt lat="10.02" lon="-84.02"/><trkpt lat="10.03" lon="-84.03"/><trkpt lat="10.04" lon="-84.04"/></trkseg>
                <trkseg><trkpt lat="10.05" lon="-84.05"/></trkseg>
              </trk>
              <rte><rtept lat="10.2" lon="-84.2"/><rtept lat="10.3" lon="-84.3"/></rte>
            </gpx>""",
        )
        val layer = data.layers.single()
        assertEquals("Sunday walk", layer.name)
        assertEquals(listOf("Cabin", "Loop", "Route 1"), data.features.map { it.name })
        assertEquals(listOf(FeatureType.POINT, FeatureType.LINE, FeatureType.LINE), data.features.map { it.type })
        assertEquals("Water here", data.features[0].description)
        // One <trk> is one track; its three <trkseg>s (even the 1-point one) are kept as segments.
        assertEquals(6, data.features[1].points.size)
        assertEquals(listOf(2, 5), data.features[1].track!!.segmentStarts)
        assertTrue(data.features.all { it.layerId == layer.id })
    }

    @Test
    fun gpxKeepsTimesAndElevations() {
        val data = parse(
            "t.gpx",
            """<gpx version="1.1"><trk><trkseg>
                <trkpt lat="10.0" lon="-84.0"><ele>120.5</ele><time>2024-02-01T10:00:00Z</time></trkpt>
                <trkpt lat="10.01" lon="-84.0"><time>2024-02-01T10:05:00+01:00</time></trkpt>
                <trkpt lat="10.02" lon="-84.0"><ele>130</ele></trkpt>
            </trkseg></trk></gpx>""",
        )
        val t = data.features.single().track!!
        assertEquals(listOf(120.5, null, 130.0), t.elevations)
        assertEquals(1706781600000L, t.times[0])
        assertEquals(1706781900000L - 3_600_000L, t.times[1]) // offset honoured
        assertEquals(null, t.times[2])
        // No times or elevations at all: no track data.
        assertEquals(null, parse("p.gpx", """<gpx><rte><rtept lat="1" lon="2"/><rtept lat="3" lon="4"/></rte></gpx>""").features.single().track)
    }

    @Test
    fun gpxExportWritesTimesAndElevationsAndReimports() {
        val f = Feature(
            type = FeatureType.LINE, name = "Walk", color = 0,
            points = listOf(LatLon(10.0, -84.0), LatLon(10.01, -84.0)),
            track = TrackData(listOf(1706781600000L, null), listOf(120.5, 121.0), durationMillis = 60_000, ascent = 0.5),
        )
        val gpx = FeatureExport.gpx("m", listOf(f))
        assertTrue(gpx.contains("<ele>120.5</ele><time>2024-02-01T10:00:00Z</time>"))
        val back = parse("back.gpx", gpx).features.single().track!!
        assertEquals(listOf(120.5, 121.0), back.elevations)
        assertEquals(listOf(1706781600000L, null), back.times)
    }

    @Test
    fun gpx10WithoutNamespaceUsesFileName() {
        val data = parse("tracks.gpx", """<gpx version="1.0"><trk><trkseg><trkpt lat="1" lon="2"/><trkpt lat="3" lon="4"/></trkseg></trk></gpx>""")
        assertEquals("tracks", data.layers.single().name)
        assertEquals(listOf(LatLon(1.0, 2.0), LatLon(3.0, 4.0)), data.features.single().points)
    }

    private val kml = """<?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
          <Document><name>Trails</name>
            <Folder><name>Nested</name>
              <Placemark><name>Gate</name><description><![CDATA[Open <b>daily</b><br/>dawn-dusk]]></description>
                <Point><coordinates>-84.0,10.0,120</coordinates></Point></Placemark>
            </Folder>
            <Placemark><name>Lake</name>
              <Polygon><outerBoundaryIs><LinearRing><coordinates>
                -84,10 -84,10.1 -84.1,10.1 -84,10
              </coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
            <Placemark><name>Combo</name><MultiGeometry>
              <LineString><coordinates>-84,10 -84.1,10.1</coordinates></LineString>
              <Point><coordinates>-84.2,10.2</coordinates></Point>
            </MultiGeometry></Placemark>
            <Placemark><name>Recorded</name><gx:Track>
              <when>2024-01-01T00:00:00Z</when><gx:coord>-84 10 5</gx:coord>
              <when>2024-01-01T00:01:00Z</when><gx:coord>-84.001 10.001 6</gx:coord>
            </gx:Track></Placemark>
          </Document>
        </kml>"""

    @Test
    fun kmlGeometriesFoldersAndDescriptions() {
        val data = parse("trails.kml", kml)
        assertEquals("Trails", data.layers.single().name)
        assertEquals(listOf("Gate", "Lake", "Combo (1)", "Combo (2)", "Recorded"), data.features.map { it.name })
        assertEquals(
            listOf(FeatureType.POINT, FeatureType.AREA, FeatureType.LINE, FeatureType.POINT, FeatureType.LINE),
            data.features.map { it.type },
        )
        assertEquals("Open daily\ndawn-dusk", data.features[0].description)
        assertEquals(LatLon(10.0, -84.0), data.features[0].points.single())
        assertEquals(3, data.features[1].points.size) // closing point dropped
        assertEquals(LatLon(10.001, -84.001), data.features[4].points[1])
        // gx:Track keeps its <when> times and altitudes.
        assertEquals(listOf(5.0, 6.0), data.features[4].track!!.elevations)
        assertEquals(1704067260000L, data.features[4].track!!.times[1])
    }

    @Test
    fun kmzUsesDocKml() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("files/readme.txt")); z.write("hi".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("doc.kml")); z.write(kml.toByteArray()); z.closeEntry()
        }
        val data = FeatureImport.parse("trails.kmz", zip.toByteArray(), 0)
        assertEquals(5, data.features.size)
    }

    @Test(expected = FeatureImportException::class)
    fun rejectsOtherXml() {
        parse("x.xml", "<html><body/></html>")
    }

    @Test(expected = FeatureImportException::class)
    fun rejectsGarbage() {
        parse("x.gpx", "not xml at all")
    }

    @Test(expected = FeatureImportException::class)
    fun rejectsDoctypeEntities() {
        parse("x.gpx", """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]><gpx><wpt lat="1" lon="1"><name>&x;</name></wpt></gpx>""")
    }

    @Test
    fun nonfiniteElevationsAreUnknown() {
        val data = parse(
            "bad.gpx",
            """<gpx><trk><trkseg>
              <trkpt lat="1" lon="2"><ele>NaN</ele></trkpt>
              <trkpt lat="1.1" lon="2.1"><ele>1e309</ele></trkpt>
              <trkpt lat="1.2" lon="2.2"><ele>-Infinity</ele></trkpt>
              <trkpt lat="1.3" lon="2.3"><ele>12.5</ele></trkpt>
            </trkseg></trk></gpx>""",
        )
        assertEquals(listOf(null, null, null, 12.5), data.features.single().track!!.elevations)
        val kml = parse("bad.kml", "<kml><Placemark><LineString><coordinates>2,1,NaN 2.1,1.1,1e309</coordinates></LineString></Placemark></kml>")
        assertEquals(2, kml.features.single().points.size)
        assertEquals(null, kml.features.single().track) // no usable elevations left
    }

    @Test(expected = FeatureImportException::class)
    fun rejectsDeeplyNestedKml() {
        val depth = 100_000
        parse(
            "deep.kml",
            "<kml>" + "<Folder>".repeat(depth) + "<Placemark><Point><coordinates>2,1</coordinates></Point></Placemark>" +
                "</Folder>".repeat(depth) + "</kml>",
        )
    }

    @Test
    fun exportPutsLayersInFoldersAndRoundTrips() {
        val imported = parse("trails.kml", kml)
        val mine = Feature(type = FeatureType.POINT, name = "Mine", points = listOf(LatLon(1.0, 2.0)), color = 0)
        val out = FeatureExport.kml("Map", imported.features + mine, imported.layers)
        assertTrue(out.contains("<Folder><name>Trails</name>"))
        assertTrue(out.indexOf("<name>Mine</name>") < out.indexOf("<Folder>"))
        val again = parse("again.kml", out)
        assertEquals(6, again.features.size)
    }

    @Test
    fun trackWithGapsRoundTripsAsOneTrack() {
        val walk = Feature(
            type = FeatureType.LINE, name = "Walk", color = 0,
            points = listOf(LatLon(49.0, -123.0), LatLon(49.001, -123.0), LatLon(49.1, -123.0), LatLon(49.101, -123.0)),
            track = TrackData(listOf(1L, 2L, 3L, 4L).map { it * 60_000 }, List(4) { null }, segmentStarts = listOf(2)),
        )
        for ((name, text) in listOf("walk.gpx" to FeatureExport.gpx("Walk", listOf(walk)), "walk.kml" to FeatureExport.kml("Walk", listOf(walk)))) {
            val f = parse(name, text).features.single()
            assertEquals(name, "Walk", f.name)
            assertEquals(name, walk.points, f.points)
            assertEquals(name, listOf(2), f.track!!.segmentStarts)
            assertEquals(name, walk.length, f.length, 0.01)
        }
        // Time to continue from counts each part, not the gap: 1 min + 1 min.
        assertEquals(120_000L, parse("walk.gpx", FeatureExport.gpx("Walk", listOf(walk))).features.single().track!!.resumeMillis)
        // Separate tracks stay separate.
        val two = FeatureExport.gpx("Two", listOf(walk.copy(track = null), walk.copy(name = "Other", track = null)))
        assertEquals(listOf("Walk", "Other"), parse("two.gpx", two).features.map { it.name })
    }
}
