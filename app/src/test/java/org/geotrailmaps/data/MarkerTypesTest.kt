package org.geotrailmaps.data

import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.ui.map.retype
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarkerTypesTest {

    private val example = File("src/main/assets/marker-types/wildlife.json").readText()

    @Test
    fun readsTheBundledExample() {
        val set = MarkerTypes.parse(example)
        assertEquals("wildlife", set.id)
        assertEquals("Wildlife", set.name)
        assertEquals(listOf("Snake", "Bear sign", "Deer / elk", "Bird", "Plant"), set.types.map { it.name })
        val snake = set.types.first()
        assertEquals("wildlife/snake", snake.id)
        assertEquals(0xFF5D7A2B.toInt(), snake.color)
        // The type's own fields first, then the set's.
        assertEquals(listOf("species", "count", "behaviour", "confidence"), snake.fields.map { it.key })
        assertEquals(FieldKind.CHOICE, snake.fields[0].kind)
        assertEquals("1", snake.fields[1].default)
        assertEquals("Certain", snake.fields[3].default)
        // Body and tongue stroked, head filled.
        val parts = snake.icon!!.parts
        assertEquals(3, parts.size)
        assertTrue(!parts[0].fill && parts[0].strokeWidth == 2.2f && parts[0].roundCaps)
        assertTrue(parts[1].fill && parts[1].strokeWidth == null)
        assertEquals(1.2f, parts[2].strokeWidth)
        assertTrue(set.types.all { it.icon != null })
    }

    @Test
    fun minimalFilesAndDefaults() {
        // A bare list of types, named by the caller (the file name); no colour, icon or fields.
        val set = MarkerTypes.parse("""[{"name": "Nest"}, {"name": "Den", "color": "#80FF0000"}]""", "Birding")
        assertEquals("birding", set.id)
        assertEquals("Birding", set.name)
        assertEquals("birding/nest", set.types[0].id)
        assertNull(set.types[0].icon)
        assertEquals(0xFF616161.toInt(), set.types[0].color)
        assertEquals(0x80FF0000.toInt(), set.types[1].color)
        // Keys default to the label; "select" is accepted for choice.
        val f = MarkerTypes.parse("""{"name":"X","types":[{"name":"A","fields":[{"label":"Water source","type":"select","options":["Yes","No"]}]}]}""")
            .types[0].fields[0]
        assertEquals("water-source", f.key)
        assertEquals(FieldKind.CHOICE, f.kind)
    }

    @Test
    fun explainsBadFiles() {
        fun message(text: String) = assertThrows(MarkerTypesException::class.java) { MarkerTypes.parse(text) }.message!!
        assertTrue(message("<kml/>").startsWith("This isn't a JSON file"))
        assertEquals("No \"types\" list found.", message("""{"name":"X"}"""))
        assertEquals("The \"types\" list is empty.", message("""{"types":[]}"""))
        assertEquals("Type 1 has no name.", message("""{"types":[{"color":"#ffffff"}]}"""))
        assertTrue(message("""{"types":[{"name":"A"},{"name":"a"}]}""").startsWith("Two types are called"))
        assertTrue(message("""{"types":[{"name":"A","color":"green"}]}""").contains("colour"))
        assertTrue(message("""{"types":[{"name":"A","fields":[{"label":"S","type":"choice"}]}]}""").contains("needs \"options\""))
        assertTrue(message("""{"types":[{"name":"A","fields":[{"label":"S","type":"date"}]}]}""").contains("text, number or choice"))
        assertTrue(message("""{"types":[{"name":"A","icon":"<svg viewBox='0 0 24 24'></svg>"}]}""").contains("no shapes"))
    }

    @Test
    fun svgSubset() {
        // A bare path is a 24×24 filled icon.
        val bare = SvgIcon.parse("M12 2L2 22h20z")
        assertEquals(24f, bare.width)
        assertEquals(MarkerIcon.Part("M12 2L2 22h20z", fill = true, strokeWidth = null), bare.parts.single())

        val icon = SvgIcon.parse(
            """
            <?xml version="1.0"?>
            <svg xmlns="http://www.w3.org/2000/svg" width="48" height="32">
              <defs><clipPath id="c"><rect width="10" height="10"/></clipPath></defs>
              <path fill="none" d="M0 0h24v24H0z"/>
              <g fill="none" stroke="#000" stroke-width="3">
                <circle cx="5" cy="6" r="2"/>
                <g><line x1="0" y1="0" x2="4" y2="4" style="stroke-width: 1.5"/></g>
              </g>
              <rect x="1" y="2" width="3" height="4" fill-rule="evenodd"/>
              <polygon points="0,0 4,0 2,3"/>
              <ellipse cx="1" cy="1" rx="0" ry="2"/>
            </svg>
            """.trimIndent(),
        )
        // No viewBox: width and height. Defs and the invisible background square are skipped.
        assertEquals(48f, icon.width)
        assertEquals(32f, icon.height)
        val p = icon.parts
        assertEquals(4, p.size)
        assertEquals(MarkerIcon.Part("M3.0 6.0a2.0 2.0 0 1 0 4.0 0a2.0 2.0 0 1 0 -4.0 0z", fill = false, strokeWidth = 3f), p[0])
        assertEquals(MarkerIcon.Part("M0.0 0.0L4.0 4.0", fill = false, strokeWidth = 1.5f), p[1])
        // Back outside the <g>: filled again.
        assertEquals(MarkerIcon.Part("M1.0 2.0h3.0v4.0h-3.0z", fill = true, strokeWidth = null, evenOdd = true), p[2])
        assertEquals("M0.0 0.0 L4.0 0.0 L2.0 3.0z", p[3].pathData)
    }

    @Test
    fun namesAndSummaries() {
        val type = MarkerTypes.parse(example).types.first()
        fun point(name: String) = Feature(type = FeatureType.POINT, name = name, points = listOf(LatLon(0.0, 0.0)), color = 0)
        assertEquals("Snake 1", MarkerTypes.defaultName("Snake", listOf(point("Placemark 1"))))
        assertEquals("Snake 5", MarkerTypes.defaultName("Snake", listOf(point("Snake 4"), point("Snake 2"), point("Snakes 9"))))
        val f = point("Snake 1").copy(markerType = type.id, attributes = mapOf("confidence" to "Likely", "species" to "Rattlesnake", "old" to "x", "count" to "2"))
        // Field order, then values for fields the type no longer has.
        assertEquals("Rattlesnake · 2 · Likely · x", MarkerTypes.summary(f, type))
    }

    @Test
    fun changingType() {
        val types = MarkerTypes.parse(example).types
        val (snake, bear) = types
        val plant = types.last()
        // A default name follows the type; defaults fill in only what's empty.
        assertEquals("Snake 3" to mapOf("species" to "Garter snake", "count" to "1", "confidence" to "Certain"),
            retype("Placemark 3", null, mapOf("species" to "Garter snake"), snake))
        val (name, values) = retype("Snake 3", snake, mapOf("count" to "2", "confidence" to ""), bear)
        assertEquals("Bear sign 3", name)
        assertEquals(mapOf("count" to "2", "confidence" to "Certain", "evidence" to "Seen"), values)
        // Plants have no count: an untouched default goes, an entered value stays.
        assertEquals(mapOf("confidence" to "Certain"), retype("Snake 3", snake, mapOf("count" to "1", "confidence" to "Certain"), plant).second)
        assertEquals("5", retype("Snake 3", snake, mapOf("count" to "5"), plant).second["count"])
        // A name someone typed stays.
        assertEquals("By the creek", retype("By the creek", snake, emptyMap(), null).first)
    }

    @Test
    fun exportsTypeAndDetails() {
        val types = MarkerTypes.parse(example).types.associateBy { it.id }
        val f = Feature(
            type = FeatureType.POINT, name = "Snake 1", points = listOf(LatLon(49.0, -120.0)), color = 0,
            markerType = "wildlife/snake", attributes = mapOf("species" to "Rattlesnake", "count" to "2"),
        )
        val kml = FeatureExport.kml("Trip", listOf(f), markerTypes = types)
        assertTrue(kml.contains("""<Data name="type"><displayName>Type</displayName><value>Snake</value></Data>"""))
        assertTrue(kml.contains("""<Data name="typeId"><value>wildlife/snake</value></Data>"""))
        assertTrue(kml.contains("""<Data name="count"><displayName>How many</displayName><value>2</value></Data>"""))
        // ExtendedData comes before the geometry, as KML's schema orders them.
        assertTrue(kml.indexOf("</ExtendedData>") < kml.indexOf("<Point>"))
        val gpx = FeatureExport.gpx("Trip", listOf(f), types)
        assertTrue(gpx.contains("<cmt>Species: Rattlesnake; How many: 2</cmt>"))
        assertTrue(gpx.contains("<type>Snake</type>"))
        // A plain placemark gets neither.
        val plain = FeatureExport.kml("Trip", listOf(f.copy(markerType = null, attributes = emptyMap())))
        assertFalse(plain.contains("ExtendedData"))
    }
}
