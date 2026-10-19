package com.example.rnoteviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.util.LruCache
import com.caverock.androidsvg.SVG
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class TileKey(val x: Int, val y: Int)

/**
 * LRU cache of 2D bitmap tiles for infinite canvas rendering.
 *
 * Each tile is a [tileSizeDu] × [tileSizeDu] square in document units.
 */
class TileCache(
    private val doc: RnoteDocument,
    private val rtree: RTree,
    private val context: Context,
    val tileSizeDu: Float = 512f,
    /** Viewport scale: zoom × density. Used for invScale during drawing. */
    var scale: Float = 1f,
    maxMemoryBytes: Int = 48 * 1024 * 1024
) {
    companion object {
        /** Maximum tile bitmap width/height in pixels to avoid OOM and GC thrashing. */
        private const val MAX_TILE_WIDTH_PX = 1536f
    }

    /** Effective rendering scale used for bitmaps (≤ scale). */
    val renderScale: Float
        get() = minOf(scale, MAX_TILE_WIDTH_PX / tileSizeDu)

    // ── LRU cache ─────────────────────────────
    private val lru = object : LruCache<TileKey, Bitmap>(maxMemoryBytes) {
        override fun sizeOf(key: TileKey, value: Bitmap) = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: TileKey, old: Bitmap, new: Bitmap?) {
            if (evicted) old.recycle()
        }
    }

    private val inFlight = mutableSetOf<TileKey>()
    private var _onTileReady: ((TileKey) -> Unit) = {}
    fun setOnTileReady(cb: (TileKey) -> Unit) { _onTileReady = cb }

    fun get(key: TileKey): Bitmap? = lru.get(key)

    fun renderSync(key: TileKey) {
        synchronized(inFlight) {
            if (key in inFlight) return
            inFlight.add(key)
        }
        try {
            lru.put(key, renderTile(key))
        } finally {
            synchronized(inFlight) { inFlight.remove(key) }
        }
        _onTileReady(key)
    }

    fun invalidateAll() {
        lru.evictAll()
        synchronized(inFlight) { inFlight.clear() }
    }

    // ── Rendering ─────────────────────────────
    private fun renderTile(key: TileKey): Bitmap {
        val rs       = renderScale
        val leftDu   = key.x * tileSizeDu
        val topDu    = key.y * tileSizeDu
        val rightDu  = leftDu + tileSizeDu
        val bottomDu = topDu + tileSizeDu

        val widthPx  = (tileSizeDu * rs).toInt().coerceAtLeast(1)
        val heightPx = (tileSizeDu * rs).toInt().coerceAtLeast(1)

        val bmp    = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        canvas.drawColor(doc.background.color.toAndroidColor())
        drawPattern(canvas, leftDu, topDu, widthPx, heightPx, rs)

        val visible     = rtree.query(leftDu, topDu, rightDu, bottomDu)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val path        = Path()

        for (el in visible) {
            when (el) {
                is StrokeElement -> drawStroke(canvas, el, leftDu, topDu, rs, strokePaint, path)
                is TextElement   -> drawText(canvas, el, leftDu, topDu, rs)
                is BitmapElement -> drawImage(canvas, el, leftDu, topDu, rs)
                is VectorElement -> drawVectorImage(canvas, el, leftDu, topDu, rs)
                is ShapeElement  -> drawShape(canvas, el, leftDu, topDu, rs, path)
            }
        }
        return bmp
    }

    // ── Stroke ────────────────────────────────
    private fun drawStroke(
        canvas: Canvas, el: StrokeElement, leftDu: Float, topDu: Float, rs: Float,
        paint: Paint, path: Path
    ) {
        if (el.points.size < 2) return
        val argb = el.color.toAndroidColor()
        paint.color       = argb
        paint.strokeWidth = el.strokeWidth * rs
        paint.alpha       = if (el.isHighlighter) 80 else Color.alpha(argb)
        path.reset()
        path.moveTo((el.points[0].x - leftDu) * rs, (el.points[0].y - topDu) * rs)
        for (k in 1 until el.points.size) {
            val pt = el.points[k]
            path.lineTo((pt.x - leftDu) * rs, (pt.y - topDu) * rs)
        }
        canvas.drawPath(path, paint)
    }

    // ── Text ──────────────────────────────────
    private fun drawText(canvas: Canvas, el: TextElement, leftDu: Float, topDu: Float, rs: Float) {
        val typeface = FontManager.resolve(el.fontFamily, el.fontStyle)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = el.color.toAndroidColor()
            textSize = el.fontSize * rs
            this.typeface = typeface
        }
        val wrapPx = if (el.maxWidth > 0f) (el.maxWidth * rs).toInt()
                     else (doc.pageWidth * rs).toInt()

        canvas.save()
        canvas.translate((el.x - leftDu) * rs, (el.y - topDu) * rs)

        @Suppress("DEPRECATION")
        val layout = StaticLayout(
            el.text, tp, wrapPx.coerceAtLeast(1),
            Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false
        )
        layout.draw(canvas)
        canvas.restore()
    }

    // ── Bitmap image ──────────────────────────
    private fun drawImage(canvas: Canvas, el: BitmapElement, leftDu: Float, topDu: Float, rs: Float) {
        if (el.bitmap.isRecycled) return

        val pw = el.bitmap.width.toFloat().coerceAtLeast(1f)
        val ph = el.bitmap.height.toFloat().coerceAtLeast(1f)

        val sx = 2f * el.halfW / pw
        val sy = 2f * el.halfH / ph

        val a  = el.m00 * sx * rs
        val b  = el.m01 * sy * rs
        val c  = (el.tx  - el.m00 * el.halfW - el.m01 * el.halfH - leftDu) * rs
        val d  = el.m10 * sx * rs
        val e2 = el.m11 * sy * rs
        val f  = (el.ty  - el.m10 * el.halfW - el.m11 * el.halfH - topDu) * rs

        val mat = Matrix()
        mat.setValues(floatArrayOf(a, b, c, d, e2, f, 0f, 0f, 1f))

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            if (el.opacity < 1f) alpha = (el.opacity * 255f).toInt().coerceIn(0, 255)
        }

        canvas.drawBitmap(el.bitmap, mat, paint)
    }

    // ── Vector / SVG image ────────────────────
    private fun drawVectorImage(canvas: Canvas, el: VectorElement, leftDu: Float, topDu: Float, rs: Float) {
        val svg = el.svg ?: try {
            SVG.getFromString(el.svgData)
        } catch (_: Exception) {
            null
        } ?: return

        var sw = svg.documentWidth
        var sh = svg.documentHeight

        if (sw <= 0f || sh <= 0f) {
            val viewBox = svg.documentViewBox
            if (viewBox != null && viewBox.width() > 0f && viewBox.height() > 0f) {
                sw = viewBox.width()
                sh = viewBox.height()
            }
        }
        if (sw <= 0f || sh <= 0f) {
            sw = if (el.intrinsicW > 0f) el.intrinsicW else 2f * el.halfW
            sh = if (el.intrinsicH > 0f) el.intrinsicH else 2f * el.halfH
        }

        val scaleX = 2f * el.halfW / sw
        val scaleY = 2f * el.halfH / sh

        val a  = el.m00 * scaleX * rs
        val b  = el.m01 * scaleY * rs
        val c  = (el.tx  - el.m00 * el.halfW - el.m01 * el.halfH - leftDu) * rs
        val d  = el.m10 * scaleX * rs
        val e2 = el.m11 * scaleY * rs
        val f  = (el.ty  - el.m10 * el.halfW - el.m11 * el.halfH - topDu) * rs

        val mat = Matrix()
        mat.setValues(floatArrayOf(a, b, c, d, e2, f, 0f, 0f, 1f))

        canvas.save()
        canvas.concat(mat)

        try {
            svg.renderToCanvas(canvas)
        } catch (e: Exception) {
            Log.e("TileCache", "DRAW VECTOR SVG failed: $e")
        } finally {
            canvas.restore()
        }
    }

    // ── Background pattern ────────────────────
    private fun drawPattern(
        canvas: Canvas, leftDu: Float, topDu: Float, widthPx: Int, heightPx: Int, rs: Float
    ) {
        val bg = doc.background
        if (bg.pattern == PatternType.BLANK) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = bg.patternColor.toAndroidColor()
            strokeWidth = 1f
            style       = Paint.Style.STROKE
        }
        val cellW   = bg.patternWidth  * rs
        val cellH   = bg.patternHeight * rs

        fun posMod(value: Float, step: Float): Float {
            val m = value % step
            return if (m < 0) m + step else m
        }

        val offsetX = posMod(leftDu * rs, cellW)
        val offsetY = posMod(topDu * rs, cellH)

        when (bg.pattern) {
            PatternType.GRID -> {
                var x = -offsetX
                while (x <= widthPx) {
                    if (x >= 0) canvas.drawLine(x, 0f, x, heightPx.toFloat(), paint)
                    x += cellW
                }
                var y = -offsetY
                while (y <= heightPx) {
                    if (y >= 0) canvas.drawLine(0f, y, widthPx.toFloat(), y, paint)
                    y += cellH
                }
            }
            PatternType.RULED -> {
                var y = -offsetY
                while (y <= heightPx) {
                    if (y >= 0) canvas.drawLine(0f, y, widthPx.toFloat(), y, paint)
                    y += cellH
                }
            }
            PatternType.DOTS -> {
                val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = bg.patternColor.toAndroidColor()
                    style = Paint.Style.FILL
                }
                var y = -offsetY
                while (y <= heightPx) {
                    var x = -offsetX
                    while (x <= widthPx) {
                        if (x >= 0 && y >= 0) canvas.drawCircle(x, y, 1.5f, dotP)
                        x += cellW
                    }
                    y += cellH
                }
            }
            else -> {}
        }
    }

    // ── Shape rendering ───────────────────────

    private fun applyShapeStyle(paint: Paint, style: ShapeStyle, rs: Float, filled: Boolean) {
        if (filled) {
            paint.style       = Paint.Style.FILL
            paint.color       = style.fillColor.toAndroidColor()
        } else {
            paint.style       = Paint.Style.STROKE
            paint.color       = style.strokeColor.toAndroidColor()
            paint.strokeWidth = style.strokeWidth * rs
            paint.strokeJoin  = Paint.Join.ROUND
            paint.strokeCap   = Paint.Cap.ROUND
            if (style.lineDash) {
                paint.pathEffect = DashPathEffect(
                    floatArrayOf(style.strokeWidth * rs * 4, style.strokeWidth * rs * 2), 0f)
            } else {
                paint.pathEffect = null
            }
        }
    }

    private fun drawShape(
        canvas: Canvas, el: ShapeElement, leftDu: Float, topDu: Float, rs: Float, path: Path
    ) {
        val s = el.style
        val hasFill = s.fillColor.a > 0.01f

        val fillPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        applyShapeStyle(strokePaint, s, rs, false)

        when (val geo = el.geometry) {

            is LineShape -> {
                canvas.drawLine((geo.x0 - leftDu)*rs, (geo.y0 - topDu)*rs, (geo.x1 - leftDu)*rs, (geo.y1 - topDu)*rs, strokePaint)
            }

            is ArrowShape -> {
                canvas.drawLine((geo.startX - leftDu)*rs, (geo.startY - topDu)*rs, (geo.tipX - leftDu)*rs, (geo.tipY - topDu)*rs, strokePaint)
                val dx = geo.tipX - geo.startX
                val dy = geo.tipY - geo.startY
                val len = sqrt((dx*dx+dy*dy).toDouble()).toFloat().coerceAtLeast(0.001f)
                val ux = dx/len; val uy = dy/len
                val headLen = (s.strokeWidth * 6f).coerceAtLeast(8f)
                val headW   = headLen * 0.45f
                val bx = (geo.tipX - ux*headLen - leftDu)*rs
                val by = (geo.tipY - uy*headLen - topDu)*rs
                val tx2 = (geo.tipX - leftDu)*rs; val ty2 = (geo.tipY - topDu)*rs
                path.reset()
                path.moveTo(bx - uy*headW*rs, by + ux*headW*rs)
                path.lineTo(tx2, ty2)
                path.lineTo(bx + uy*headW*rs, by - ux*headW*rs)
                if (hasFill) {
                    path.close()
                    applyShapeStyle(fillPaint, s, rs, true)
                    canvas.drawPath(path, fillPaint)
                }
                canvas.drawPath(path, strokePaint)
            }

            is RectShape -> {
                path.reset()
                val hw = geo.halfW*rs; val hh = geo.halfH*rs
                val cx = (geo.tx - leftDu)*rs; val cy = (geo.ty - topDu)*rs
                if (geo.rotSin == 0f) {
                    val r = RectF(cx-hw, cy-hh, cx+hw, cy+hh)
                    if (hasFill) { applyShapeStyle(fillPaint,s,rs,true); canvas.drawRect(r, fillPaint) }
                    canvas.drawRect(r, strokePaint)
                } else {
                    val cos = geo.rotCos; val sin = geo.rotSin
                    fun corner(lx:Float,ly:Float): Pair<Float,Float> =
                        Pair(cx + lx*cos - ly*sin, cy + lx*sin + ly*cos)
                    val (ax,ay) = corner(-hw,-hh); val (bx,by) = corner(hw,-hh)
                    val (cx2,cy2)= corner(hw,hh);  val (dx2,dy2)= corner(-hw,hh)
                    path.moveTo(ax,ay); path.lineTo(bx,by); path.lineTo(cx2,cy2); path.lineTo(dx2,dy2); path.close()
                    if (hasFill) { applyShapeStyle(fillPaint,s,rs,true); canvas.drawPath(path, fillPaint) }
                    canvas.drawPath(path, strokePaint)
                }
            }

            is EllipseShape -> {
                val rx = geo.rx*rs; val ry = geo.ry*rs
                val cx = (geo.tx - leftDu)*rs; val cy = (geo.ty - topDu)*rs
                val isCircle = (geo.m10 == 0f && geo.m01 == 0f)
                if (isCircle) {
                    val oval = RectF(cx-rx, cy-ry, cx+rx, cy+ry)
                    if (hasFill) { applyShapeStyle(fillPaint,s,rs,true); canvas.drawOval(oval, fillPaint) }
                    canvas.drawOval(oval, strokePaint)
                } else {
                    path.reset()
                    val steps = 72
                    for (i in 0..steps) {
                        val angle = 2f * Math.PI.toFloat() * i / steps
                        val ex = geo.rx * cos(angle)
                        val ey = geo.ry * sin(angle)
                        val wx = ((geo.m00 * ex + geo.m01 * ey + geo.tx) - leftDu) * rs
                        val wy = ((geo.m10 * ex + geo.m11 * ey + geo.ty) - topDu) * rs
                        if (i == 0) path.moveTo(wx,wy) else path.lineTo(wx,wy)
                    }
                    path.close()
                    if (hasFill) { applyShapeStyle(fillPaint,s,rs,true); canvas.drawPath(path, fillPaint) }
                    canvas.drawPath(path, strokePaint)
                }
            }

            is QuadBezShape -> {
                path.reset()
                path.moveTo((geo.x0 - leftDu)*rs, (geo.y0 - topDu)*rs)
                path.quadTo((geo.cpX - leftDu)*rs, (geo.cpY - topDu)*rs, (geo.x1 - leftDu)*rs, (geo.y1 - topDu)*rs)
                canvas.drawPath(path, strokePaint)
            }

            is CubBezShape -> {
                path.reset()
                path.moveTo((geo.x0 - leftDu)*rs, (geo.y0 - topDu)*rs)
                path.cubicTo((geo.cp1X - leftDu)*rs, (geo.cp1Y - topDu)*rs, (geo.cp2X - leftDu)*rs, (geo.cp2Y - topDu)*rs, (geo.x1 - leftDu)*rs, (geo.y1 - topDu)*rs)
                canvas.drawPath(path, strokePaint)
            }

            is PolygonShape -> {
                if (geo.points.size < 2) return
                path.reset()
                path.moveTo((geo.points[0].first - leftDu)*rs, (geo.points[0].second - topDu)*rs)
                for (i in 1 until geo.points.size)
                    path.lineTo((geo.points[i].first - leftDu)*rs, (geo.points[i].second - topDu)*rs)
                path.close()
                if (hasFill) { applyShapeStyle(fillPaint,s,rs,true); canvas.drawPath(path, fillPaint) }
                canvas.drawPath(path, strokePaint)
            }

            is PolylineShape -> {
                if (geo.points.size < 2) return
                path.reset()
                path.moveTo((geo.points[0].first - leftDu)*rs, (geo.points[0].second - topDu)*rs)
                for (i in 1 until geo.points.size)
                    path.lineTo((geo.points[i].first - leftDu)*rs, (geo.points[i].second - topDu)*rs)
                canvas.drawPath(path, strokePaint)
            }
        }
    }
}
