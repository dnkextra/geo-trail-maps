package org.geotrailmaps.pdf

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/*
 * A small, read-only PDF object reader. It knows just enough of the format to walk
 * the page tree and read the georeferencing dictionaries of a geospatial PDF; rendering is left
 * to Android's PdfRenderer.
 *
 * Object model: numbers are Long or Double, names are PdfName, strings are PdfString,
 * arrays are List<Any?>, dictionaries are Map<String, Any?> (keys without the slash),
 * indirect references are PdfRef and streams are PdfStream.
 */

data class PdfName(val value: String)

data class PdfRef(val num: Int, val gen: Int)

class PdfString(val bytes: ByteArray) {
    /** Decodes using the BOM (UTF-16 BE/LE) when present, otherwise Latin-1. */
    val text: String
        get() = when {
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            else -> String(bytes, Charsets.ISO_8859_1)
        }.trimEnd('\u0000')

    override fun toString() = text
}

class PdfStream(val dict: Map<String, Any?>, val start: Int, val length: Int)

class PdfException(message: String) : RuntimeException(message)

class PdfFile private constructor(private val buf: ByteBuffer) {

    private sealed interface XrefEntry
    private data class AtOffset(val offset: Int) : XrefEntry
    private data class InObjectStream(val streamNum: Int, val index: Int) : XrefEntry

    private val xref = HashMap<Int, XrefEntry>()
    private val cache = HashMap<Int, Any?>()
    private val resolving = HashSet<Int>()

    var trailer: Map<String, Any?> = emptyMap()
        private set

    // ---------------------------------------------------------------- objects

    fun resolve(obj: Any?): Any? = if (obj is PdfRef) getObject(obj.num) else obj

    fun dict(obj: Any?): Map<String, Any?>? = when (val o = resolve(obj)) {
        is Map<*, *> -> @Suppress("UNCHECKED_CAST") (o as Map<String, Any?>)
        is PdfStream -> o.dict
        else -> null
    }

    fun array(obj: Any?): List<Any?>? = resolve(obj) as? List<Any?>

    /** Reads an array of numbers. Some producers write numbers as strings, e.g. (0.05). */
    fun numbers(obj: Any?): DoubleArray? {
        val list = array(obj) ?: return null
        val values = list.map { number(it) ?: return null }
        return values.toDoubleArray()
    }

    fun number(obj: Any?): Double? = when (val o = resolve(obj)) {
        is Long -> o.toDouble()
        is Double -> o
        is PdfString -> o.text.trim().toDoubleOrNull()
        else -> null
    }

    fun getObject(num: Int): Any? {
        if (cache.containsKey(num)) return cache[num]
        if (!resolving.add(num)) return null // reference cycle
        try {
            val value = when (val entry = xref[num]) {
                is AtOffset -> parseIndirectObject(entry.offset)
                is InObjectStream -> readFromObjectStream(entry.streamNum, entry.index)
                null -> null
            }
            cache[num] = value
            return value
        } catch (e: RuntimeException) {
            cache[num] = null
            return null
        } finally {
            resolving.remove(num)
        }
    }

    fun streamData(stream: PdfStream): ByteArray {
        val raw = ByteArray(stream.length)
        buf.get(stream.start, raw)
        val filter = resolve(stream.dict["Filter"])
        val filters = when (filter) {
            null -> emptyList()
            is PdfName -> listOf(filter.value)
            is List<*> -> filter.map { (resolve(it) as? PdfName)?.value }
            else -> throw PdfException("Bad filter")
        }
        // One DecodeParms per filter when there are several.
        val parms = resolve(stream.dict["DecodeParms"])
        var data = raw
        for ((i, f) in filters.withIndex()) {
            val p = dict(if (parms is List<*>) parms.getOrNull(i) else parms)
            data = when (f) {
                "FlateDecode", "Fl" -> applyPredictor(inflate(data), p)
                "ASCII85Decode", "A85" -> ascii85(data)
                else -> throw PdfException("Unsupported stream filter $f")
            }
        }
        return data
    }

    /**
     * Reads a content stream, calling [onOperator] with each operator and its operands (only
     * valid during the call). Inline image data is skipped; malformed data ends it quietly.
     */
    fun readContent(data: ByteArray, onOperator: (String, List<Any?>) -> Unit) {
        val lx = Lexer(0, ByteBuffer.wrap(data))
        val operands = ArrayList<Any?>()
        try {
            while (!lx.atEnd()) {
                val t = lx.next()
                if (t !is Keyword) {
                    if (t !== EndArray && t !== EndDict) operands += t
                    continue
                }
                if (t.value == "ID") lx.skipInlineImage() else onOperator(t.value, operands)
                operands.clear()
            }
        } catch (_: PdfException) {
        }
    }

    // ---------------------------------------------------------------- xref

    private fun load() {
        try {
            readXrefChain(findStartXref())
        } catch (e: RuntimeException) {
            xref.clear()
            trailer = emptyMap()
        }
        if (xref.isEmpty() || trailer["Root"] == null) rebuildXrefByScanning()
        if (trailer["Root"] == null) throw PdfException("No document catalog")
    }

    private fun findStartXref(): Int {
        val key = "startxref".toByteArray()
        val from = maxOf(0, buf.limit() - 4096)
        for (i in buf.limit() - key.size downTo from) {
            if (matches(i, key)) {
                val lx = Lexer(i + key.size)
                return (lx.next() as Long).toInt()
            }
        }
        throw PdfException("startxref not found")
    }

    private fun readXrefChain(start: Int) {
        var offset: Int? = start
        val seen = HashSet<Int>()
        while (offset != null && seen.add(offset)) {
            val section = if (matches(offset, "xref".toByteArray())) {
                readXrefTable(offset)
            } else {
                readXrefStream(offset)
            }
            if (trailer.isEmpty()) trailer = section
            // Hybrid files keep compressed-object entries in a separate xref stream.
            (section["XRefStm"] as? Long)?.let { readXrefStream(it.toInt()) }
            offset = (section["Prev"] as? Long)?.toInt()
        }
    }

    /** Classic "xref" table. Entries already seen (from newer sections) win. */
    private fun readXrefTable(offset: Int): Map<String, Any?> {
        val lx = Lexer(offset + 4)
        while (true) {
            val first = lx.next()
            if (first == Keyword("trailer")) break
            val count = lx.next() as Long
            for (i in 0 until count.toInt()) {
                val pos = lx.next() as Long
                lx.next() // generation
                val type = lx.next() as Keyword
                val num = (first as Long).toInt() + i
                if (type.value == "n" && num !in xref) xref[num] = AtOffset(pos.toInt())
            }
        }
        @Suppress("UNCHECKED_CAST")
        return lx.next() as Map<String, Any?>
    }

    private fun readXrefStream(offset: Int): Map<String, Any?> {
        val stream = parseIndirectObject(offset) as? PdfStream ?: throw PdfException("Bad xref stream")
        val d = stream.dict
        val w = (d["W"] as List<*>).map { (it as Long).toInt() }
        val size = (d["Size"] as Long).toInt()
        val index = (d["Index"] as? List<*>)?.map { (it as Long).toInt() } ?: listOf(0, size)
        val data = streamData(stream)
        val rowLen = w.sum()
        var p = 0
        fun field(width: Int, default: Long): Long {
            if (width == 0) return default
            var v = 0L
            repeat(width) { v = (v shl 8) or (data[p++].toLong() and 0xFF) }
            return v
        }
        for (s in index.indices step 2) {
            for (i in 0 until index[s + 1]) {
                if (p + rowLen > data.size) break
                val num = index[s] + i
                val type = field(w[0], 1)
                val f2 = field(w[1], 0)
                val f3 = field(w[2], 0)
                if (num in xref) continue
                when (type) {
                    1L -> xref[num] = AtOffset(f2.toInt())
                    2L -> xref[num] = InObjectStream(f2.toInt(), f3.toInt())
                }
            }
        }
        return d
    }

    /** Fallback for damaged files: find every "N G obj" header directly. */
    private fun rebuildXrefByScanning() {
        xref.clear()
        val objStreams = ArrayList<Int>()
        var i = 0
        val end = buf.limit() - 3
        while (i < end) {
            if (buf.get(i) == 'o'.code.toByte() && buf.get(i + 1) == 'b'.code.toByte() &&
                buf.get(i + 2) == 'j'.code.toByte()
            ) {
                headerStart(i)?.let { (num, start) ->
                    xref[num] = AtOffset(start) // later definitions replace earlier ones
                }
            }
            i++
        }
        for (num in xref.keys.toList()) {
            val d = (getObject(num) as? PdfStream)?.dict ?: continue
            when ((d["Type"] as? PdfName)?.value) {
                "ObjStm" -> objStreams += num
                "XRef" -> if (d["Root"] != null) trailer = d
            }
        }
        for (streamNum in objStreams) {
            val header = runCatching { objectStreamHeader(getObject(streamNum) as PdfStream) }.getOrNull() ?: continue
            header.forEachIndexed { idx, (num, _) ->
                if (num !in xref) xref[num] = InObjectStream(streamNum, idx)
            }
        }
        if (trailer["Root"] == null) findTrailerKeyword()?.let { trailer = it }
        if (trailer["Root"] == null) {
            val catalog = xref.keys.firstOrNull { (dict(getObject(it))?.get("Type") as? PdfName)?.value == "Catalog" }
            if (catalog != null) trailer = mapOf("Root" to PdfRef(catalog, 0))
        }
    }

    /** Given the index of "obj", walks back over "N G " and returns (N, offset of N). */
    private fun headerStart(objIndex: Int): Pair<Int, Int>? {
        var p = objIndex - 1
        fun skipWs() { while (p >= 0 && isWhitespace(buf.get(p))) p-- }
        fun digitsBack(): Int? {
            val endP = p
            while (p >= 0 && buf.get(p) in '0'.code.toByte()..'9'.code.toByte()) p--
            if (p == endP) return null
            return String(ByteArray(endP - p) { buf.get(p + 1 + it) }, Charsets.US_ASCII).toIntOrNull()
        }
        skipWs()
        digitsBack() ?: return null
        skipWs()
        val num = digitsBack() ?: return null
        if (p >= 0 && !isWhitespace(buf.get(p)) && !isDelimiter(buf.get(p))) return null
        return num to p + 1
    }

    private fun findTrailerKeyword(): Map<String, Any?>? {
        val key = "trailer".toByteArray()
        for (i in buf.limit() - key.size downTo 0) {
            if (matches(i, key)) {
                @Suppress("UNCHECKED_CAST")
                return runCatching { Lexer(i + key.size).next() as Map<String, Any?> }.getOrNull()
            }
        }
        return null
    }

    // ---------------------------------------------------------------- object streams

    private val objStmHeaders = HashMap<Int, List<Pair<Int, Int>>>()
    private val objStmData = HashMap<Int, ByteArray>()

    private fun objectStreamHeader(stream: PdfStream): List<Pair<Int, Int>>? {
        val data = streamData(stream)
        val n = (stream.dict["N"] as? Long)?.toInt() ?: return null
        val lx = Lexer(0, ByteBuffer.wrap(data))
        return List(n) { (lx.next() as Long).toInt() to (lx.next() as Long).toInt() }
    }

    private fun readFromObjectStream(streamNum: Int, index: Int): Any? {
        val stream = getObject(streamNum) as? PdfStream ?: return null
        val data = objStmData.getOrPut(streamNum) { streamData(stream) }
        val header = objStmHeaders.getOrPut(streamNum) { objectStreamHeader(stream) ?: emptyList() }
        val first = (stream.dict["First"] as Long).toInt()
        val offset = header.getOrNull(index)?.second ?: return null
        return Lexer(first + offset, ByteBuffer.wrap(data)).next()
    }

    // ---------------------------------------------------------------- parsing

    private fun parseIndirectObject(offset: Int): Any? {
        val lx = Lexer(offset)
        lx.next(); lx.next() // object number and generation
        if (lx.next() != Keyword("obj")) throw PdfException("Expected obj at $offset")
        return lx.next()
    }

    private fun matches(at: Int, bytes: ByteArray): Boolean {
        if (at < 0 || at + bytes.size > buf.limit()) return false
        for (k in bytes.indices) if (buf.get(at + k) != bytes[k]) return false
        return true
    }

    private data class Keyword(val value: String)

    private object EndArray
    private object EndDict

    private inner class Lexer(var pos: Int, val b: ByteBuffer = buf) {
        private val limit = b.limit()

        private fun peek(): Int = if (pos < limit) b.get(pos).toInt() and 0xFF else -1

        private fun skipWhitespaceAndComments() {
            while (pos < limit) {
                val c = b.get(pos)
                when {
                    isWhitespace(c) -> pos++
                    c == '%'.code.toByte() -> while (pos < limit && b.get(pos) != '\n'.code.toByte() && b.get(pos) != '\r'.code.toByte()) pos++
                    else -> return
                }
            }
        }

        fun atEnd(): Boolean {
            skipWhitespaceAndComments()
            return pos >= limit
        }

        /** Skips an inline image's data, which runs from after "ID" to "EI". */
        fun skipInlineImage() {
            pos++ // the whitespace after ID
            while (pos + 2 <= limit) {
                if (b.get(pos) == 'E'.code.toByte() && b.get(pos + 1) == 'I'.code.toByte() && isWhitespace(b.get(pos - 1)) &&
                    (pos + 2 == limit || isWhitespace(b.get(pos + 2)) || isDelimiter(b.get(pos + 2)))
                ) {
                    pos += 2
                    return
                }
                pos++
            }
            pos = limit
        }

        fun next(): Any? {
            skipWhitespaceAndComments()
            if (pos >= limit) throw PdfException("Unexpected end of data")
            val c = peek().toChar()
            return when {
                c == '[' -> { pos++; readArray() }
                c == ']' -> { pos++; EndArray }
                c == '<' && pos + 1 < limit && b.get(pos + 1) == '<'.code.toByte() -> { pos += 2; readDictOrStream() }
                c == '>' && pos + 1 < limit && b.get(pos + 1) == '>'.code.toByte() -> { pos += 2; EndDict }
                c == '<' -> { pos++; readHexString() }
                c == '(' -> { pos++; readLiteralString() }
                c == '/' -> { pos++; PdfName(readName()) }
                c.isDigit() || c == '-' || c == '+' || c == '.' -> readNumberOrRef()
                else -> {
                    val word = readRegular()
                    if (word.isEmpty()) { pos++; Keyword(c.toString()) } // stray delimiter
                    else when (word) {
                        "true" -> true
                        "false" -> false
                        "null" -> null
                        else -> Keyword(word)
                    }
                }
            }
        }

        private fun readRegular(): String {
            val sb = StringBuilder()
            while (pos < limit) {
                val c = b.get(pos)
                if (isWhitespace(c) || isDelimiter(c)) break
                sb.append((c.toInt() and 0xFF).toChar())
                pos++
            }
            return sb.toString()
        }

        private fun readName(): String {
            val raw = readRegular()
            if ('#' !in raw) return raw
            val out = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val code = if (raw[i] == '#' && i + 2 < raw.length) raw.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (code != null) {
                    out.append(code.toChar()); i += 3
                } else {
                    out.append(raw[i]); i++
                }
            }
            return out.toString()
        }

        private fun readNumberOrRef(): Any {
            val word = readRegular()
            if ('.' in word) return word.toDoubleOrNull() ?: 0.0
            val n = word.toLongOrNull() ?: return word.toDoubleOrNull() ?: 0L
            // Look ahead for "G R"
            val save = pos
            skipWhitespaceAndComments()
            val gen = readRegular().toIntOrNull()
            if (gen != null) {
                skipWhitespaceAndComments()
                // "R" alone, not the start of an operator like "RG" in a content stream.
                if (peek() == 'R'.code && (pos + 1 >= limit || isWhitespace(b.get(pos + 1)) || isDelimiter(b.get(pos + 1)))) {
                    pos++
                    return PdfRef(n.toInt(), gen)
                }
            }
            pos = save
            return n
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            while (true) {
                val o = next()
                if (o === EndArray) return list
                if (o === EndDict) throw PdfException("Unexpected >> in array")
                list.add(o)
            }
        }

        private fun readDictOrStream(): Any {
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                val key = next()
                if (key === EndDict) break
                val name = (key as? PdfName)?.value ?: throw PdfException("Dictionary key expected")
                val value = next()
                if (value === EndDict) { map[name] = null; break }
                map[name] = value
            }
            // A dictionary followed by the "stream" keyword is a stream.
            val save = pos
            skipWhitespaceAndComments()
            if (readRegular() != "stream") {
                pos = save
                return map
            }
            if (peek() == '\r'.code) pos++
            if (peek() == '\n'.code) pos++
            val start = pos
            val declared = (resolveLength(map["Length"]))
            val length = if (declared != null && start + declared <= limit && endstreamNear(start + declared)) {
                declared
            } else {
                findEndstream(start) - start
            }
            pos = start + length
            skipWhitespaceAndComments()
            readRegular() // "endstream"
            return PdfStream(map, start, length)
        }

        private fun resolveLength(v: Any?): Int? = when (v) {
            is Long -> v.toInt()
            is PdfRef -> if (b === buf) (getObject(v.num) as? Long)?.toInt() else null
            else -> null
        }

        private fun endstreamNear(at: Int): Boolean {
            var p = at
            while (p < limit && isWhitespace(b.get(p))) p++
            val kw = "endstream".toByteArray()
            if (p + kw.size > limit) return false
            for (k in kw.indices) if (b.get(p + k) != kw[k]) return false
            return true
        }

        private fun findEndstream(from: Int): Int {
            val kw = "endstream".toByteArray()
            var p = from
            while (p + kw.size <= limit) {
                var ok = true
                for (k in kw.indices) if (b.get(p + k) != kw[k]) { ok = false; break }
                if (ok) {
                    var e = p
                    if (e > from && b.get(e - 1) == '\n'.code.toByte()) e--
                    if (e > from && b.get(e - 1) == '\r'.code.toByte()) e--
                    return e
                }
                p++
            }
            throw PdfException("endstream not found")
        }

        private fun readHexString(): PdfString {
            val out = ByteArrayOutputStream()
            var hi = -1
            while (pos < limit) {
                val c = b.get(pos++).toInt().toChar()
                if (c == '>') break
                val d = Character.digit(c, 16)
                if (d < 0) continue
                if (hi < 0) hi = d else { out.write(hi * 16 + d); hi = -1 }
            }
            if (hi >= 0) out.write(hi * 16)
            return PdfString(out.toByteArray())
        }

        private fun readLiteralString(): PdfString {
            val out = ByteArrayOutputStream()
            var depth = 1
            while (pos < limit) {
                val c = b.get(pos++).toInt() and 0xFF
                when (c.toChar()) {
                    '(' -> { depth++; out.write(c) }
                    ')' -> { depth--; if (depth == 0) break; out.write(c) }
                    '\\' -> {
                        if (pos >= limit) break
                        val e = b.get(pos++).toInt().toChar()
                        when (e) {
                            'n' -> out.write('\n'.code)
                            'r' -> out.write('\r'.code)
                            't' -> out.write('\t'.code)
                            'b' -> out.write('\b'.code)
                            'f' -> out.write(12)
                            '\r' -> if (peek() == '\n'.code) pos++
                            '\n' -> Unit
                            in '0'..'7' -> {
                                var v = e - '0'
                                repeat(2) {
                                    val d = peek()
                                    if (d in '0'.code..'7'.code) { v = v * 8 + (d - '0'.code); pos++ }
                                }
                                out.write(v and 0xFF)
                            }
                            else -> out.write(e.code)
                        }
                    }
                    else -> out.write(c)
                }
            }
            return PdfString(out.toByteArray())
        }
    }

    companion object {
        fun open(file: File): PdfFile {
            RandomAccessFile(file, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                return PdfFile(map).also { it.load() }
            }
        }

        fun fromBytes(bytes: ByteArray): PdfFile = PdfFile(ByteBuffer.wrap(bytes)).also { it.load() }

        private fun isWhitespace(c: Byte) =
            c == 0x20.toByte() || c == 0x0A.toByte() || c == 0x0D.toByte() ||
                c == 0x09.toByte() || c == 0x0C.toByte() || c == 0x00.toByte()

        private fun isDelimiter(c: Byte) = when (c.toInt().toChar()) {
            '(', ')', '<', '>', '[', ']', '{', '}', '/', '%' -> true
            else -> false
        }

        /** A few hundred bytes of Flate can expand to gigabytes; no real map needs streams this big. */
        internal const val MAX_INFLATED_BYTES = 64 * 1024 * 1024

        private fun inflate(data: ByteArray): ByteArray {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val chunk = ByteArray(64 * 1024)
            try {
                while (!inflater.finished()) {
                    val n = inflater.inflate(chunk)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    if (out.size() + n > MAX_INFLATED_BYTES) throw PdfException("Flate stream too large")
                    out.write(chunk, 0, n)
                }
            } catch (e: java.util.zip.DataFormatException) {
                if (out.size() == 0) throw PdfException("Corrupt Flate stream")
            } finally {
                inflater.end()
            }
            return out.toByteArray()
        }

        /** ASCII base-85 ("<~" optional, "~>" ends it, "z" is four zero bytes, whitespace ignored). */
        private fun ascii85(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size * 4 / 5 + 4)
            var start = 0
            while (start < data.size && isWhitespace(data[start])) start++
            if (start + 1 < data.size && data[start] == '<'.code.toByte() && data[start + 1] == '~'.code.toByte()) start += 2
            var group = 0L
            var count = 0
            for (i in start until data.size) {
                val c = data[i].toInt() and 0xFF
                when {
                    c == '~'.code -> break
                    c == 'z'.code && count == 0 -> repeat(4) { out.write(0) }
                    c in '!'.code..'u'.code -> {
                        group = group * 85 + (c - '!'.code)
                        if (++count == 5) {
                            for (shift in 24 downTo 0 step 8) out.write((group shr shift).toInt() and 0xFF)
                            group = 0
                            count = 0
                        }
                    }
                    isWhitespace(data[i]) -> {}
                    else -> throw PdfException("Bad ASCII85 data")
                }
            }
            // A final partial group of n characters is padded with 'u' and gives n - 1 bytes.
            if (count == 1) throw PdfException("Bad ASCII85 data")
            if (count > 1) {
                repeat(5 - count) { group = group * 85 + 84 }
                for (k in 0 until count - 1) out.write((group shr (24 - 8 * k)).toInt() and 0xFF)
            }
            return out.toByteArray()
        }

        /** PNG predictors (10-15), as used by xref and object streams. */
        private fun applyPredictor(data: ByteArray, parms: Map<String, Any?>?): ByteArray {
            val predictor = (parms?.get("Predictor") as? Long)?.toInt() ?: 1
            if (predictor < 10) return data
            val colors = (parms?.get("Colors") as? Long)?.toInt() ?: 1
            val bpc = (parms?.get("BitsPerComponent") as? Long)?.toInt() ?: 8
            val columns = (parms?.get("Columns") as? Long)?.toInt() ?: 1
            val bpp = maxOf(1, colors * bpc / 8)
            val rowLen = (columns * colors * bpc + 7) / 8
            val out = ByteArrayOutputStream()
            val prev = ByteArray(rowLen)
            val row = ByteArray(rowLen)
            var p = 0
            while (p + rowLen + 1 <= data.size) {
                val type = data[p++].toInt()
                System.arraycopy(data, p, row, 0, rowLen)
                p += rowLen
                for (i in 0 until rowLen) {
                    val left = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                    val up = prev[i].toInt() and 0xFF
                    val upLeft = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                    val add = when (type) {
                        1 -> left
                        2 -> up
                        3 -> (left + up) / 2
                        4 -> paeth(left, up, upLeft)
                        else -> 0
                    }
                    row[i] = (row[i] + add).toByte()
                }
                out.write(row)
                System.arraycopy(row, 0, prev, 0, rowLen)
            }
            return out.toByteArray()
        }

        private fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = kotlin.math.abs(p - a)
            val pb = kotlin.math.abs(p - b)
            val pc = kotlin.math.abs(p - c)
            return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
        }
    }
}
