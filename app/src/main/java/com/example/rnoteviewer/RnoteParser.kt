package com.example.rnoteviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.caverock.androidsvg.SVG
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Parser streaming per file .rnote (gzip + JSON).
 * Gestisce: brushstroke, textstroke, bitmapimage.
 */
object RnoteParser {

    // ── Entry points ──────────────────────────
    fun parse(context: Context, uri: Uri): RnoteDocument =
        context.contentResolver.openInputStream(uri)!!.use { parse(it) }

    fun parse(inputStream: InputStream): RnoteDocument {
        val reader = JsonReader(InputStreamReader(GZIPInputStream(inputStream), Charsets.UTF_8))
        return parseRoot(reader)
    }

    // ── Holder types ──────────────────────────
    private data class FormatConfig(val width: Float = 794f, val height: Float = 1027f)
    private data class BgConfig(
        val color: RnoteColor = RnoteColor.WHITE,
        val pattern: PatternType = PatternType.BLANK,
        val patternW: Float = 21f, val patternH: Float = 21f,
        val patternColor: RnoteColor = RnoteColor(0.8f, 0.9f, 1f, 1f)
    )

    // ── Root ──────────────────────────────────
    private fun parseRoot(reader: JsonReader): RnoteDocument {
        var format = FormatConfig()
        var bg = BgConfig()
        var totalHeight = 0f
        val rawElements = mutableListOf<CanvasElement?>()
        val chronoOrder = mutableListOf<Int>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "data" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "engine_snapshot" -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "document" -> {
                                            val r = parseDocument(reader)
                                            format = r.first; bg = r.second; totalHeight = r.third
                                        }
                                        "stroke_components" -> parseStrokeComponents(reader, rawElements)
                                        "chrono_components" -> parseChronoComponents(reader, chronoOrder)
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val elements = buildOrderedElements(rawElements, chronoOrder)
        return RnoteDocument(
            pageWidth   = format.width,
            pageHeight  = format.height,
            totalHeight = totalHeight,
            background  = BackgroundConfig(bg.color, bg.pattern, bg.patternW, bg.patternH, bg.patternColor),
            elements    = elements
        )
    }

    // ── Document ──────────────────────────────
    private fun parseDocument(reader: JsonReader): Triple<FormatConfig, BgConfig, Float> {
        var format = FormatConfig(); var bg = BgConfig(); var h = 0f
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "config" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "format"     -> format = parseFormatConfig(reader)
                            "background" -> bg     = parseBgConfig(reader)
                            else         -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                "height" -> h = reader.nextDouble().toFloat()
                else     -> reader.skipValue()
            }
        }
        reader.endObject()
        return Triple(format, bg, h)
    }

    private fun parseFormatConfig(reader: JsonReader): FormatConfig {
        var w = 794f; var h = 1027f
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "width"  -> w = reader.nextDouble().toFloat()
                "height" -> h = reader.nextDouble().toFloat()
                else     -> reader.skipValue()
            }
        }
        reader.endObject()
        return FormatConfig(w, h)
    }

    private fun parseBgConfig(reader: JsonReader): BgConfig {
        var color = RnoteColor.WHITE; var pattern = PatternType.BLANK
        var pw = 21f; var ph = 21f; var pc = RnoteColor(0.8f, 0.9f, 1f, 1f)
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "color"         -> color   = parseColor(reader)
                "pattern"       -> pattern = parsePatternType(reader.nextString())
                "pattern_size"  -> { reader.beginArray(); pw = reader.nextDouble().toFloat(); ph = reader.nextDouble().toFloat(); reader.endArray() }
                "pattern_color" -> pc      = parseColor(reader)
                else            -> reader.skipValue()
            }
        }
        reader.endObject()
        return BgConfig(color, pattern, pw, ph, pc)
    }

    private fun parsePatternType(s: String) = when (s.lowercase()) {
        "grid"  -> PatternType.GRID
        "ruled" -> PatternType.RULED
        "dots"  -> PatternType.DOTS
        else    -> PatternType.BLANK
    }

    // ── stroke_components ─────────────────────
    private fun parseStrokeComponents(reader: JsonReader, out: MutableList<CanvasElement?>) {
        reader.beginArray()
        while (reader.hasNext()) out.add(parseOneComponent(reader))
        reader.endArray()
    }

    private fun parseOneComponent(reader: JsonReader): CanvasElement? {
        var element: CanvasElement? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "value" -> element = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null }
                           else parseElementValue(reader)
                else    -> reader.skipValue()
            }
        }
        reader.endObject()
        return element
    }

    private fun parseElementValue(reader: JsonReader): CanvasElement? {
        var element: CanvasElement? = null
        reader.beginObject()
        while (reader.hasNext()) {
            element = when (reader.nextName()) {
                "brushstroke"  -> parseBrushstroke(reader)
                "textstroke"   -> parseTextstroke(reader)
                "bitmapimage"  -> parseBitmapimage(reader)
                "shapestroke"  -> parseShapestroke(reader)
                "vectorimage"  -> parseVectorimage(reader)
                else           -> { reader.skipValue(); null }
            }
        }
        reader.endObject()
        return element
    }

    // ── Brushstroke ───────────────────────────
    private fun parseBrushstroke(reader: JsonReader): StrokeElement? {
        val points = mutableListOf<StrokePoint>()
        var width = 1.5f; var color = RnoteColor.BLACK; var isHighlighter = false

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "path"  -> parsePath(reader, points)
                "style" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val styleType = reader.nextName()
                        val r = parseStyle(reader)
                        width = r.first; color = r.second
                        isHighlighter = (styleType == "highlighter")
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (points.size < 2) return null
        val hw = width / 2f
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < x0) x0 = p.x; if (p.x > x1) x1 = p.x
            if (p.y < y0) y0 = p.y; if (p.y > y1) y1 = p.y
        }
        return StrokeElement(points, width, color, isHighlighter, x0-hw, y0-hw, x1+hw, y1+hw)
    }

    private fun parsePath(reader: JsonReader, out: MutableList<StrokePoint>) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "start"    -> out.add(parseStrokePoint(reader))
                "segments" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "lineto", "curveto" -> {
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "end" -> out.add(parseStrokePoint(reader))
                                            else  -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun parseStrokePoint(reader: JsonReader): StrokePoint {
        var x = 0f; var y = 0f; var p = 0.5f
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "pos"      -> { reader.beginArray(); x = reader.nextDouble().toFloat(); y = reader.nextDouble().toFloat(); reader.endArray() }
                "pressure" -> p = reader.nextDouble().toFloat()
                else       -> reader.skipValue()
            }
        }
        reader.endObject()
        return StrokePoint(x, y, p)
    }

    private fun parseStyle(reader: JsonReader): Pair<Float, RnoteColor> {
        var width = 1.5f; var color = RnoteColor.BLACK
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "stroke_width" -> width = reader.nextDouble().toFloat()
                "stroke_color" -> color = parseColor(reader)
                else           -> reader.skipValue()
            }
        }
        reader.endObject()
        return Pair(width, color)
    }

    // ── Textstroke ────────────────────────────
    private fun parseTextstroke(reader: JsonReader): TextElement? {
        var text = ""; var tx = 0f; var ty = 0f
        var fontSize = 16f; var color = RnoteColor.BLACK; var textW = 0f
        var fontFamily = ""; var fontStyle = "regular"

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "text"       -> text = reader.nextString()
                "transform"  -> {
                    val t = parseTransform(reader)
                    tx = t.first; ty = t.second
                }
                "text_style" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "font_family" -> fontFamily = reader.nextString()
                            "font_size"   -> fontSize   = reader.nextDouble().toFloat()
                            "font_style"  -> fontStyle  = reader.nextString().lowercase()
                            "font_weight" -> reader.skipValue()   // handled via fontStyle
                            "color"       -> color = parseColor(reader)
                            "max_width"   -> textW = reader.nextDouble().toFloat()
                            else          -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (text.isBlank()) return null
        val lineHeight   = fontSize * 1.4f
        val explicitLines = text.split('\n').size
        // Estimate wrapped lines: assume ~0.55× font-size per char width
        val wrapW       = if (textW > 0f) textW else fontSize * 40f
        val avgCharW    = fontSize * 0.55f
        val charsPerLine = (wrapW / avgCharW).coerceAtLeast(1f)
        val wrappedLines = kotlin.math.ceil(text.length.toFloat() / charsPerLine).toInt()
        val totalLines  = (wrappedLines + explicitLines - 1).coerceAtLeast(1)
        // Add 2 extra lines of padding to account for font metrics and underestimation
        val estimatedH  = lineHeight * (totalLines + 2)
        val estimatedW  = if (textW > 0f) textW
                          else (text.length * fontSize * 0.6f).coerceAtLeast(fontSize)
        return TextElement(
            text, tx, ty, fontSize, color, textW,
            fontFamily, fontStyle,
            minX = tx, minY = ty,
            maxX = tx + estimatedW, maxY = ty + estimatedH
        )
    }

/**
 * Reads the "transform" field of a textstroke.
 *
 * Actual rnote 0.13 format (nalgebra Affine2 serialized with serde):
 *   { "affine": [m00,m10,m20, m01,m11,m21, tx,ty,1] }
 *   Flat column-major 3×3 array: tx=index 6, ty=index 7
 *
 * Supported legacy formats:
 *   { "matrix": { "data": [[col0],[col1],[col2]] } }
 *   { "translation": [x,y] }
 *   raw array [[col0],[col1],[col2]]
 */
 
    private fun parseTransform(reader: JsonReader): Pair<Float, Float> {
        var tx = 0f; var ty = 0f

        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        // rnote 0.13 reale: { "affine": [9 floats] }
                        "affine" -> {
                            reader.beginArray()
                            val vals = mutableListOf<Float>()
                            while (reader.hasNext()) vals.add(reader.nextDouble().toFloat())
                            reader.endArray()
                            // column-major 3x3: [m00,m10,m20, m01,m11,m21, tx,ty,1]
                            if (vals.size >= 8) { tx = vals[6]; ty = vals[7] }
                        }
                        // nalgebra 0.32 nested: { "matrix": { "data": [[c0],[c1],[c2]] } }
                        "matrix" -> {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "data" -> {
                                        val t = readColumnMajorMatrix(reader)
                                        tx = t.first; ty = t.second
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                        // legacy: { "translation": [x,y] }
                        "translation" -> when (reader.peek()) {
                            JsonToken.BEGIN_ARRAY -> {
                                reader.beginArray()
                                tx = reader.nextDouble().toFloat()
                                ty = reader.nextDouble().toFloat()
                                while (reader.hasNext()) reader.skipValue()
                                reader.endArray()
                            }
                            JsonToken.BEGIN_OBJECT -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "vector" -> {
                                            reader.beginArray()
                                            tx = reader.nextDouble().toFloat()
                                            ty = reader.nextDouble().toFloat()
                                            while (reader.hasNext()) reader.skipValue()
                                            reader.endArray()
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }
                            else -> reader.skipValue()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            // raw column-major array [[col0],[col1],[col2]]
            JsonToken.BEGIN_ARRAY -> {
                val t = readColumnMajorMatrix(reader)
                tx = t.first; ty = t.second
            }
            else -> reader.skipValue()
        }
        return Pair(tx, ty)
    }
    private fun readColumnMajorMatrix(reader: JsonReader): Pair<Float, Float> {
        var tx = 0f; var ty = 0f
        reader.beginArray()
        var colIdx = 0
        while (reader.hasNext()) {
            reader.beginArray()
            val vals = mutableListOf<Float>()
            while (reader.hasNext()) vals.add(reader.nextDouble().toFloat())
            reader.endArray()
            if (colIdx == 2 && vals.size >= 2) { tx = vals[0]; ty = vals[1] }
            colIdx++
        }
        reader.endArray()
        return Pair(tx, ty)
    }

    // ── Bitmapimage ───────────────────────────
    private fun parseBitmapimage(reader: JsonReader): BitmapElement? {
        // bitmapimage has exactly two relevant top-level keys:
        //   "image"     → pixel data (data, pixel_width, pixel_height)
        //   "rectangle" → canvas placement (transform.affine, cuboid.half_extents)
        // The inner image.rectangle describes the image in its own coordinate space
        // and must be IGNORED for positioning.

        var imageData: String? = null
        var pixelW = 0; var pixelH = 0
        var opacity = 1f

        // Canvas rectangle (outer, from bitmapimage.rectangle)
        var tx = 0f; var ty = 0f
        var m00 = 1f; var m10 = 0f; var m01 = 0f; var m11 = 1f
        var halfW = 0f; var halfH = 0f

        reader.beginObject()                          // open bitmapimage { …
        while (reader.hasNext()) {
            when (reader.nextName()) {

                "image" -> {
                    // Read only pixel data fields; skip everything else (incl. image.rectangle)
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "data"          -> imageData = reader.nextString()
                            "pixel_width"   -> pixelW    = reader.nextInt()
                            "pixel_height"  -> pixelH    = reader.nextInt()
                            else            -> reader.skipValue()   // skips image.rectangle etc.
                        }
                    }
                    reader.endObject()
                }

                "rectangle" -> {
                    // This is bitmapimage.rectangle — the canvas placement we need
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {

                            "transform" -> {
                                // { "affine": [m00,m10,_,  m01,m11,_,  tx,ty,_] }
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "affine" -> {
                                            reader.beginArray()
                                            val v = ArrayList<Float>(9)
                                            while (reader.hasNext()) v.add(reader.nextDouble().toFloat())
                                            reader.endArray()
                                            if (v.size >= 8) {
                                                m00 = v[0]; m10 = v[1]   // column 0
                                                m01 = v[3]; m11 = v[4]   // column 1
                                                tx  = v[6]; ty  = v[7]   // column 2 = translation
                                            }
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }

                            "cuboid" -> {
                                // { "half_extents": [halfW, halfH] }
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "half_extents" -> {
                                            reader.beginArray()
                                            halfW = reader.nextDouble().toFloat()
                                            halfH = reader.nextDouble().toFloat()
                                            reader.endArray()
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }

                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }

                "opacity" -> opacity = reader.nextDouble().toFloat()
                else      -> reader.skipValue()
            }
        }
        reader.endObject()                            // close bitmapimage }

        val data = imageData ?: return null

        // Compute AABB by transforming the 4 corners of the canvas rectangle.
        // Corners in LOCAL space (before affine): (±halfW, ±halfH)
        // World coords: wx = m00*lx + m01*ly + tx,  wy = m10*lx + m11*ly + ty
        fun wx(lx: Float, ly: Float) = m00 * lx + m01 * ly + tx
        fun wy(lx: Float, ly: Float) = m10 * lx + m11 * ly + ty
        val wxs = floatArrayOf(wx(-halfW,-halfH), wx(halfW,-halfH), wx(halfW,halfH), wx(-halfW,halfH))
        val wys = floatArrayOf(wy(-halfW,-halfH), wy(halfW,-halfH), wy(halfW,halfH), wy(-halfW,halfH))

        android.util.Log.d("RnoteParser", "BITMAP PARSED: tx=$tx ty=$ty m00=$m00 m10=$m10 m01=$m01 m11=$m11 halfW=$halfW halfH=$halfH pixelW=$pixelW pixelH=$pixelH")
        android.util.Log.d("RnoteParser", "BITMAP AABB: x=[${wxs.min()},${wxs.max()}] y=[${wys.min()},${wys.max()}]")

        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            val bmp   = decodeRgba8Premul(bytes, pixelW, pixelH) ?: return null
            BitmapElement(
                bitmap  = bmp,
                opacity = opacity,
                halfW = halfW, halfH = halfH,
                m00 = m00, m10 = m10, m01 = m01, m11 = m11,
                tx  = tx,  ty  = ty,
                minX = wxs.min(), minY = wys.min(),
                maxX = wxs.max(), maxY = wys.max()
            )
        } catch (e: Exception) { android.util.Log.e("RnoteParser", "BITMAP decode failed: $e"); null }
    }

    private fun decodeRgba8Premul(bytes: ByteArray, w: Int, h: Int): android.graphics.Bitmap? {
        if (w <= 0 || h <= 0 || bytes.size < w * h * 4) return null
        return try {
            // Usa ByteBuffer + copyPixelsFromBuffer: molto più veloce di setPixels
            // Android ARGB_8888 in memoria è ARGB (A più significativo), ma
            // copyPixelsFromBuffer si aspetta RGBA nell'ordine dei byte.
            // Dobbiamo convertire R,G,B,A → A,R,G,B (ARGB packed big-endian).
            val pixels = IntArray(w * h)
            for (i in 0 until w * h) {
                val b4 = i * 4
                val r = bytes[b4    ].toInt() and 0xFF
                val g = bytes[b4 + 1].toInt() and 0xFF
                val b = bytes[b4 + 2].toInt() and 0xFF
                val a = bytes[b4 + 3].toInt() and 0xFF
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            bmp
        } catch (_: Exception) { null }
    }

    // ── Vectorimage ───────────────────────────
    private fun parseVectorimage(reader: JsonReader): VectorElement? {
        var svgData: String? = null
        var intrinsicW = 0f
        var intrinsicH = 0f
        var opacity = 1f

        var tx = 0f; var ty = 0f
        var m00 = 1f; var m10 = 0f; var m01 = 0f; var m11 = 1f
        var halfW = 0f; var halfH = 0f

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "svg_data" -> svgData = reader.nextString()
                "intrinsic_size" -> {
                    reader.beginArray()
                    if (reader.hasNext()) intrinsicW = reader.nextDouble().toFloat()
                    if (reader.hasNext()) intrinsicH = reader.nextDouble().toFloat()
                    while (reader.hasNext()) reader.skipValue()
                    reader.endArray()
                }
                "rectangle" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "transform" -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "affine" -> {
                                            reader.beginArray()
                                            val v = ArrayList<Float>(9)
                                            while (reader.hasNext()) v.add(reader.nextDouble().toFloat())
                                            reader.endArray()
                                            if (v.size >= 8) {
                                                m00 = v[0]; m10 = v[1]
                                                m01 = v[3]; m11 = v[4]
                                                tx  = v[6]; ty  = v[7]
                                            }
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }
                            "cuboid" -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "half_extents" -> {
                                            reader.beginArray()
                                            halfW = reader.nextDouble().toFloat()
                                            halfH = reader.nextDouble().toFloat()
                                            reader.endArray()
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                "opacity" -> opacity = reader.nextDouble().toFloat()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val data = svgData ?: return null

        val parsedSvg: SVG? = try {
            SVG.getFromString(data)
        } catch (e: Exception) {
            android.util.Log.e("RnoteParser", "VECTOR SVG parse failed: $e")
            null
        }

        fun wx(lx: Float, ly: Float) = m00 * lx + m01 * ly + tx
        fun wy(lx: Float, ly: Float) = m10 * lx + m11 * ly + ty
        val wxs = floatArrayOf(wx(-halfW, -halfH), wx(halfW, -halfH), wx(halfW, halfH), wx(-halfW, halfH))
        val wys = floatArrayOf(wy(-halfW, -halfH), wy(halfW, -halfH), wy(halfW, halfH), wy(-halfW, halfH))

        android.util.Log.d("RnoteParser", "VECTOR PARSED: tx=$tx ty=$ty halfW=$halfW halfH=$halfH intrinsicW=$intrinsicW intrinsicH=$intrinsicH")

        return VectorElement(
            svgData = data,
            svg = parsedSvg,
            opacity = opacity,
            halfW = halfW, halfH = halfH,
            intrinsicW = intrinsicW, intrinsicH = intrinsicH,
            m00 = m00, m10 = m10, m01 = m01, m11 = m11,
            tx = tx, ty = ty,
            minX = wxs.min(), minY = wys.min(),
            maxX = wxs.max(), maxY = wys.max()
        )
    }

    // ── Chrono components ─────────────────────
    private fun parseChronoComponents(reader: JsonReader, out: MutableList<Int>) {
        val pairs = mutableListOf<Pair<Int, Int>>()
        var idx = 0
        reader.beginArray()
        while (reader.hasNext()) {
            val t = parseChronoEntry(reader)
            if (t != null) pairs.add(Pair(idx, t))
            idx++
        }
        reader.endArray()
        pairs.sortBy { it.second }
        pairs.mapTo(out) { it.first }
    }

    private fun parseChronoEntry(reader: JsonReader): Int? {
        var t: Int? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "value" -> {
                    if (reader.peek() == JsonToken.NULL) { reader.nextNull() }
                    else {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "t"  -> t = reader.nextInt()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return t
    }

    // ── Color ─────────────────────────────────
    private fun parseColor(reader: JsonReader): RnoteColor {
        var r = 0f; var g = 0f; var b = 0f; var a = 1f
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "r" -> r = reader.nextDouble().toFloat()
                "g" -> g = reader.nextDouble().toFloat()
                "b" -> b = reader.nextDouble().toFloat()
                "a" -> a = reader.nextDouble().toFloat()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return RnoteColor(r, g, b, a)
    }

    // ── Assembly ──────────────────────────────
    private fun buildOrderedElements(
        raw: List<CanvasElement?>, chronoOrder: List<Int>
    ): List<CanvasElement> {
        val result = mutableListOf<CanvasElement>()
        val indices = if (chronoOrder.isNotEmpty()) chronoOrder else raw.indices.toList()
        for (idx in indices) {
            val el = raw.getOrNull(idx) ?: continue
            result.add(el)
        }
        return result
    }
    // ── Shapestroke ───────────────────────────
    /**
     * shapestroke: { "shape": { "<type>": {...} }, "style": { "smooth": {...} } }
     * Types: line, arrow, rect, ellipse, quadbez, cubbez, polygon, polyline
     */
    private fun parseShapestroke(reader: JsonReader): ShapeElement? {
        var geometry: ShapeGeometry? = null
        var style = ShapeStyle(RnoteColor.BLACK, 1.5f, RnoteColor.TRANSPARENT)

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "shape" -> {
                    reader.beginObject()
                    val name = reader.nextName()
                    geometry = when (name) {
                        "line"     -> parseShapeLine(reader)
                        "arrow"    -> parseShapeArrow(reader)
                        "rect"     -> parseShapeRect(reader)
                        "ellipse"  -> parseShapeEllipse(reader)
                        "quadbez"  -> parseShapeQuadBez(reader)
                        "cubbez"   -> parseShapeCubBez(reader)
                        "polygon"  -> parseShapePolygon(reader)
                        "polyline" -> parseShapePolyline(reader)
                        else       -> { reader.skipValue(); null }
                    }
                    reader.endObject()
                }
                "style" -> style = parseShapeStyle(reader)
                else    -> reader.skipValue()
            }
        }
        reader.endObject()

        val geo = geometry ?: return null
        val bbox = shapeGeoBbox(geo, style.strokeWidth)
        return ShapeElement(geo, style, bbox[0], bbox[1], bbox[2], bbox[3])
    }

    private fun parseShapeStyle(reader: JsonReader): ShapeStyle {
        var strokeColor = RnoteColor.BLACK
        var strokeWidth = 1.5f
        var fillColor   = RnoteColor.TRANSPARENT
        var dashed      = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "smooth", "textured" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "stroke_color" -> strokeColor = parseColor(reader)
                            "fill_color"   -> fillColor   = parseColor(reader)
                            "stroke_width" -> strokeWidth = reader.nextDouble().toFloat()
                            "line_style"   -> { val s = reader.nextString(); dashed = s.contains("dash") }
                            else           -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return ShapeStyle(strokeColor, strokeWidth, fillColor, dashed)
    }

    private fun readXY(reader: JsonReader): Pair<Float, Float> {
        reader.beginArray()
        val x = reader.nextDouble().toFloat()
        val y = reader.nextDouble().toFloat()
        while (reader.hasNext()) reader.skipValue()
        reader.endArray()
        return Pair(x, y)
    }

    private fun parseShapeLine(reader: JsonReader): LineShape {
        var x0=0f; var y0=0f; var x1=0f; var y1=0f
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "start" -> readXY(reader).also { x0=it.first; y0=it.second }
                "end"   -> readXY(reader).also { x1=it.first; y1=it.second }
                else    -> reader.skipValue()
            }
        }
        reader.endObject()
        return LineShape(x0, y0, x1, y1)
    }

    private fun parseShapeArrow(reader: JsonReader): ArrowShape {
        var sx=0f; var sy=0f; var tx=0f; var ty=0f
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "start" -> readXY(reader).also { sx=it.first; sy=it.second }
                "tip"   -> readXY(reader).also { tx=it.first; ty=it.second }
                else    -> reader.skipValue()
            }
        }
        reader.endObject()
        return ArrowShape(sx, sy, tx, ty)
    }

    private fun parseShapeRect(reader: JsonReader): RectShape {
        var tx=0f; var ty=0f; var hw=0f; var hh=0f
        var m00=1f; var m10=0f
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "cuboid" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "half_extents" -> { reader.beginArray(); hw=reader.nextDouble().toFloat(); hh=reader.nextDouble().toFloat(); reader.endArray() }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                "transform" -> {
                    // Read full affine to extract rotation (m00, m10)
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "affine" -> {
                                        reader.beginArray()
                                        val vals = mutableListOf<Float>()
                                        while (reader.hasNext()) vals.add(reader.nextDouble().toFloat())
                                        reader.endArray()
                                        if (vals.size >= 8) { m00=vals[0]; m10=vals[1]; tx=vals[6]; ty=vals[7] }
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                        else -> reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return RectShape(tx, ty, hw, hh, m00, m10)
    }

    private fun parseShapeEllipse(reader: JsonReader): EllipseShape {
        var rx=0f; var ry=0f; var tx=0f; var ty=0f
        var m00=1f; var m10=0f; var m01=0f; var m11=1f
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "radii" -> { reader.beginArray(); rx=reader.nextDouble().toFloat(); ry=reader.nextDouble().toFloat(); reader.endArray() }
                "transform" -> {
                    // Need all 9 values of the affine to reconstruct rotation
                    val vals = mutableListOf<Float>()
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "affine" -> { reader.beginArray(); while (reader.hasNext()) vals.add(reader.nextDouble().toFloat()); reader.endArray() }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                        else -> reader.skipValue()
                    }
                    if (vals.size >= 8) {
                        m00=vals[0]; m10=vals[1]; m01=vals[3]; m11=vals[4]
                        tx=vals[6];  ty=vals[7]
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return EllipseShape(rx, ry, tx, ty, m00, m10, m01, m11)
    }

    private fun parseShapeQuadBez(reader: JsonReader): QuadBezShape {
        var x0=0f; var y0=0f; var cpX=0f; var cpY=0f; var x1=0f; var y1=0f
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "start" -> readXY(reader).also { x0=it.first; y0=it.second }
                "cp"    -> readXY(reader).also { cpX=it.first; cpY=it.second }
                "end"   -> readXY(reader).also { x1=it.first; y1=it.second }
                else    -> reader.skipValue()
            }
        }
        reader.endObject()
        return QuadBezShape(x0, y0, cpX, cpY, x1, y1)
    }

    private fun parseShapeCubBez(reader: JsonReader): CubBezShape {
        var x0=0f; var y0=0f; var c1x=0f; var c1y=0f; var c2x=0f; var c2y=0f; var x1=0f; var y1=0f
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "start" -> readXY(reader).also { x0=it.first; y0=it.second }
                "cp1"   -> readXY(reader).also { c1x=it.first; c1y=it.second }
                "cp2"   -> readXY(reader).also { c2x=it.first; c2y=it.second }
                "end"   -> readXY(reader).also { x1=it.first; y1=it.second }
                else    -> reader.skipValue()
            }
        }
        reader.endObject()
        return CubBezShape(x0, y0, c1x, c1y, c2x, c2y, x1, y1)
    }

    private fun readPointList(reader: JsonReader): List<Pair<Float, Float>> {
        val pts = mutableListOf<Pair<Float, Float>>()
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginArray()
            val x = reader.nextDouble().toFloat()
            val y = reader.nextDouble().toFloat()
            while (reader.hasNext()) reader.skipValue()
            reader.endArray()
            pts.add(Pair(x, y))
        }
        reader.endArray()
        return pts
    }

    private fun parseShapePolygon(reader: JsonReader): PolygonShape {
        val pts = mutableListOf<Pair<Float,Float>>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "start" -> readXY(reader).let { pts.add(0, it) }
                "path"  -> {
                    val all = readPointList(reader)
                    // "path" includes start repeated; deduplicate
                    if (pts.isEmpty()) pts.addAll(all)
                    else { pts.clear(); pts.addAll(all) }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return PolygonShape(pts)
    }

    private fun parseShapePolyline(reader: JsonReader): PolylineShape {
        val pts = mutableListOf<Pair<Float,Float>>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "start" -> readXY(reader).let { pts.add(0, it) }
                "path"  -> {
                    val all = readPointList(reader)
                    if (pts.isEmpty()) pts.addAll(all)
                    else { pts.clear(); pts.addAll(all) }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return PolylineShape(pts)
    }

    /** Compute AABB for any shape geometry, expanded by half stroke width. */
    private fun shapeGeoBbox(geo: ShapeGeometry, sw: Float): FloatArray {
        val hw = sw / 2f
        return when (geo) {
            is LineShape    -> fa(minOf(geo.x0,geo.x1)-hw, minOf(geo.y0,geo.y1)-hw, maxOf(geo.x0,geo.x1)+hw, maxOf(geo.y0,geo.y1)+hw)
            is ArrowShape   -> fa(minOf(geo.startX,geo.tipX)-hw, minOf(geo.startY,geo.tipY)-hw, maxOf(geo.startX,geo.tipX)+hw+sw*4, maxOf(geo.startY,geo.tipY)+hw+sw*4)
            is RectShape    -> {
                // Bounding box of rotated rectangle
                val cos = geo.rotCos; val sin = geo.rotSin
                val corners = listOf(
                    Pair(-geo.halfW*cos - geo.halfH*sin, -geo.halfW*sin + geo.halfH*cos),
                    Pair( geo.halfW*cos - geo.halfH*sin,  geo.halfW*sin + geo.halfH*cos),
                    Pair(-geo.halfW*cos + geo.halfH*sin, -geo.halfW*sin - geo.halfH*cos),
                    Pair( geo.halfW*cos + geo.halfH*sin,  geo.halfW*sin - geo.halfH*cos)
                )
                fa(corners.minOf{it.first}+geo.tx-hw, corners.minOf{it.second}+geo.ty-hw,
                   corners.maxOf{it.first}+geo.tx+hw, corners.maxOf{it.second}+geo.ty+hw)
            }
            is EllipseShape -> fa(geo.tx-geo.rx-hw, geo.ty-geo.ry-hw, geo.tx+geo.rx+hw, geo.ty+geo.ry+hw)
            is QuadBezShape -> fa(minOf(geo.x0,geo.cpX,geo.x1)-hw, minOf(geo.y0,geo.cpY,geo.y1)-hw, maxOf(geo.x0,geo.cpX,geo.x1)+hw, maxOf(geo.y0,geo.cpY,geo.y1)+hw)
            is CubBezShape  -> fa(minOf(geo.x0,geo.cp1X,geo.cp2X,geo.x1)-hw, minOf(geo.y0,geo.cp1Y,geo.cp2Y,geo.y1)-hw, maxOf(geo.x0,geo.cp1X,geo.cp2X,geo.x1)+hw, maxOf(geo.y0,geo.cp1Y,geo.cp2Y,geo.y1)+hw)
            is PolygonShape -> { val xs=geo.points.map{it.first}; val ys=geo.points.map{it.second}; fa(xs.min()-hw, ys.min()-hw, xs.max()+hw, ys.max()+hw) }
            is PolylineShape-> { val xs=geo.points.map{it.first}; val ys=geo.points.map{it.second}; fa(xs.min()-hw, ys.min()-hw, xs.max()+hw, ys.max()+hw) }
        }
    }
    private fun fa(x0:Float,y0:Float,x1:Float,y1:Float) = floatArrayOf(x0,y0,x1,y1)

}