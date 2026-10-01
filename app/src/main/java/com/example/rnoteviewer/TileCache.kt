package com.example.rnoteviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.caverock.androidsvg.SVG
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * LRU cache of bitmap tiles.
 *
 * Each tile is a horizontal band of the document of [tileHeightDu] document-units.
 *
 * ### Anti-flickering: capped scale
 * The effective rendering scale is capped to [MAX_TILE_WIDTH_PX] pixels in width.
 * Without this cap, at high zoom levels a single tile can exceed the size of the
 * entire cache (48 MB), be immediately evicted, and continuously re-render,
 * causing constant GC and flickering.
 *
 * The public field [scale] stores the scale requested by the viewport (used for
 * computing invScale during drawing); [renderScale] is the actual scale used
 * for the bitmap (never exceeding the cap).
 */
class TileCache(
    private val doc: RnoteDocument,
    private val rtree: RTree,
    private val context: Context,
    val tileHeightDu: Float = 512f,
    /** Scala viewport: zoom × density. Usata per invScale in onDraw. */
    var scale: Float = 1f,
    maxMemoryBytes: Int = 48 * 1024 * 1024
) {
    companion object {
        /** Larghezza massima in px di un tile bitmap per evitare OOM e GC thrashing. */
        private const val MAX_TILE_WIDTH_PX = 1536f
    }

    /** Scala effettiva usata per rendere i bitmap (≤ scale). */
    val renderScale: Float
        get() = minOf(scale, MAX_TILE_WIDTH_PX / doc.pageWidth.coerceAtLeast(1f))

    // ── Tile index ────────────────────────────
    val tileCount: Int
        get() = Math.ceil((doc.totalHeight / tileHeightDu).toDouble()).toInt().coerceAtLeast(1)

    fun tileTopDu(i: Int)    = i * tileHeightDu
    fun tileBottomDu(i: Int) = ((i + 1) * tileHeightDu).coerceAtMost(doc.totalHeight)

    // ── LRU cache ─────────────────────────────
    private val lru = object : LruCache<Int, Bitmap>(maxMemoryBytes) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: Int, old: Bitmap, new: Bitmap?) {
            if (evicted) old.recycle()
        }
    }

    private val inFlight = mutableSetOf<Int>()
    private var _onTileReady: ((Int) -> Unit) = {}
    fun setOnTileReady(cb: (Int) -> Unit) { _onTileReady = cb }

    fun get(tileIndex: Int): Bitmap? = lru.get(tileIndex)

    fun renderSync(tileIndex: Int) {
        synchronized(inFlight) {
            if (tileIndex in inFlight) return
            inFlight.add(tileIndex)
        }
        try {
            lru.put(tileIndex, renderTile(tileIndex))
        } finally {
            synchronized(inFlight) { inFlight.remove(tileIndex) }
        }
        _onTileReady(tileIndex)
    }

    fun invalidateAll() {
        lru.evictAll()
        synchronized(inFlight) { inFlight.clear() }
    }

    // ── Rendering ─────────────────────────────
    private fun renderTile(tileIndex: Int): Bitmap {
        val rs       = renderScale                          // capped scale
        val topDu    = tileTopDu(tileIndex)
        val bottomDu = tileBottomDu(tileIndex)
        val widthPx  = (doc.pageWidth      * rs).toInt().coerceAtLeast(1)
        val heightPx = ((bottomDu - topDu) * rs).toInt().coerceAtLeast(1)

        val bmp    = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        canvas.drawColor(doc.background.color.toAndroidColor())
        drawPattern(canvas, topDu, widthPx, heightPx, rs)

        val visible     = rtree.query(0f, topDu, doc.pageWidth, bottomDu)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val path        = Path()

        for (el in visible) {
            when (el) {
                is StrokeElement -> drawStroke(canvas, el, topDu, rs, strokePaint, path)
                is TextElement   -> drawText(canvas, el, topDu, rs)
                is BitmapElement -> drawImage(canvas, el, topDu, rs)
                is VectorElement -> drawVectorImage(canvas, el, topDu, rs)
                is ShapeElement  -> drawShape(canvas, el, topDu, rs, path)
            }
        }
        return bmp
    }

    // ── Stroke ────────────────────────────────
    private fun drawStroke(
        canvas: Canvas, el: StrokeElement, topDu: Float, rs: Float,
        paint: Paint, path: Path
    ) {
        if (el.points.size < 2) return
        val argb = el.color.toAndroidColor()
        paint.color       = argb
        paint.strokeWidth = el.strokeWidth * rs
        paint.alpha       = if (el.isHighlighter) 80 else Color.alpha(argb)
        path.reset()
        path.moveTo(el.points[0].x * rs, (el.points[0].y - topDu) * rs)
        for (k in 1 until el.points.size) {
            val pt = el.points[k]
            path.lineTo(pt.x * rs, (pt.y - topDu) * rs)
        }
        canvas.drawPath(path, paint)
    }

    // ── Text ──────────────────────────────────
    private fun drawText(canvas: Canvas, el: TextElement, topDu: Float, rs: Float) {
        val typeface = FontManager.resolve(el.fontFamily, el.fontStyle)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = el.color.toAndroidColor()
            textSize = el.fontSize * rs
            this.typeface = typeface
        }
        val wrapPx = if (el.maxWidth > 0f) (el.maxWidth * rs).toInt()
                     else (doc.pageWidth * rs).toInt()

        canvas.save()
        canvas.translate(el.x * rs, (el.y - topDu) * rs)

        @Suppress("DEPRECATION")
        val layout = StaticLayout(
            el.text, tp, wrapPx.coerceAtLeast(1),
            Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false
        )
        layout.draw(canvas)
        canvas.restore()
    }

    // ── Bitmap image ──────────────────────────
    /**
     * Draws a BitmapElement using the full affine transform stored on the element.
     *
     * The bitmap occupies local space [-halfW..halfW] × [-halfH..halfH].
     * The affine maps local → world:
     *   wx = m00*lx + m01*ly + tx
     *   wy = m10*lx + m11*ly + ty
     *
     * We build a single Matrix that maps bitmap pixel (px, py) directly to
     * tile canvas pixel (cx, cy) and pass it to drawBitmap().
     *
     *   lx = (px / pw) * 2*halfW - halfW   → pixel to local
     *   ly = (py / ph) * 2*halfH - halfH
     *
     * Combined (px → canvas):
     *   cx = (m00 * 2*halfW/pw) * px  +  (m01 * 2*halfH/ph) * py
     *        + (tx - m00*halfW - m01*halfH)       ← all multiplied by rs
     *   cy = same with m10, m11, ty, minus topDu before multiplying by rs
     */
    private fun drawImage(canvas: Canvas, el: BitmapElement, topDu: Float, rs: Float) {
        if (el.bitmap.isRecycled) return

        val pw = el.bitmap.width.toFloat().coerceAtLeast(1f)
        val ph = el.bitmap.height.toFloat().coerceAtLeast(1f)

        // Pixel → local scaling factors
        val sx = 2f * el.halfW / pw
        val sy = 2f * el.halfH / ph

        // The 6 independent elements of the affine (in screen pixels)
        val a  = el.m00 * sx * rs
        val b  = el.m01 * sy * rs
        val c  = (el.tx  - el.m00 * el.halfW - el.m01 * el.halfH) * rs
        val d  = el.m10 * sx * rs
        val e2 = el.m11 * sy * rs
        val f  = (el.ty  - el.m10 * el.halfW - el.m11 * el.halfH - topDu) * rs

        // Android Matrix setValues order:
        // [ MSCALE_X  MSKEW_X   MTRANS_X ]   →  screen_x = a*px + b*py + c
        // [ MSKEW_Y   MSCALE_Y  MTRANS_Y ]   →  screen_y = d*px + e*py + f
        // [ 0         0         1        ]
        val mat = android.graphics.Matrix()
        mat.setValues(floatArrayOf(a, b, c,  d, e2, f,  0f, 0f, 1f))

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            if (el.opacity < 1f) alpha = (el.opacity * 255f).toInt().coerceIn(0, 255)
        }

        android.util.Log.d("TileCache", "DRAW BITMAP: bmp=${el.bitmap.width}x${el.bitmap.height} halfW=${el.halfW} halfH=${el.halfH} tx=${el.tx} ty=${el.ty} m00=${el.m00} rs=$rs topDu=$topDu")
        android.util.Log.d("TileCache", "DRAW MATRIX: a=$a b=$b c=$c d=$d e=$e2 f=$f")
        // drawBitmap(bitmap, matrix, paint): matrix maps bitmap pixels to canvas pixels
        canvas.drawBitmap(el.bitmap, mat, paint)
    }

    // ── Vector / SVG image ────────────────────
    private fun drawVectorImage(canvas: Canvas, el: VectorElement, topDu: Float, rs: Float) {
        val svg = el.svg ?: try {
            SVG.getFromString(el.svgData)
        } catch (_: Exception) {
            null
        } ?: return

        // Determine SVG intrinsic size sw, sh
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

        // Pixel -> local scaling factors
        val scaleX = 2f * el.halfW / sw
        val scaleY = 2f * el.halfH / sh

        // Matrix elements mapping SVG coordinates (sx, sy) to canvas pixels
        val a  = el.m00 * scaleX * rs
        val b  = el.m01 * scaleY * rs
        val c  = (el.tx  - el.m00 * el.halfW - el.m01 * el.halfH) * rs
        val d  = el.m10 * scaleX * rs
        val e2 = el.m11 * scaleY * rs
        val f  = (el.ty  - el.m10 * el.halfW - el.m11 * el.halfH - topDu) * rs

        val mat = android.graphics.Matrix()
        mat.setValues(floatArrayOf(a, b, c, d, e2, f, 0f, 0f, 1f))

        canvas.save()
        canvas.concat(mat)

        try {
            svg.renderToCanvas(canvas)
        } catch (e: Exception) {
            android.util.Log.e("TileCache", "DRAW VECTOR SVG failed: $e")
        } finally {
            canvas.restore()
        }
    }

    // ── Background pattern ────────────────────
    private fun drawPattern(
        canvas: Canvas, topDu: Float, widthPx: Int, heightPx: Int, rs: Float
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
        val offsetY = (topDu * rs) % cellH

        when (bg.pattern) {
            PatternType.GRID -> {
                var x = 0f
                while (x <= widthPx) { canvas.drawLine(x, 0f, x, heightPx.toFloat(), paint); x += cellW }
                var y = -offsetY
                while (y <= heightPx) { canvas.drawLine(0f, y, widthPx.toFloat(), y, paint); y += cellH }
            }
            PatternType.RULED -> {
                var y = -offsetY
                while (y <= heightPx) { canvas.drawLine(0f, y, widthPx.toFloat(), y, paint); y += cellH }
            }
            PatternType.DOTS -> {
                val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = bg.patternColor.toAndroidColor(); style = Paint.Style.FILL
                }
                var y = -offsetY
                while (y <= heightPx) {
                    var x = 0f
                    while (x <= widthPx) { canvas.drawCircle(x, y, 1.5f, dotP); x += cellW }
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
                paint.pathEffect = android.graphics.DashPathEffect(
                    floatArrayOf(style.strokeWidth * rs * 4, style.strokeWidth * rs * 2), 0f)
            } else {
                paint.pathEffect = null
            }
        }
    }

    private fun drawShape(
        canvas: Canvas, el: ShapeElement, topDu: Float, rs: Float, path: Path
    ) {
        val s = el.style
        val hasFill = s.fillColor.a > 0.01f

        val fillPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        applyShapeStyle(strokePaint, s, rs, false)

        when (val geo = el.geometry) {

            is LineShape -> {
                canvas.drawLine(geo.x0*rs, (geo.y0-topDu)*rs, geo.x1*rs, (geo.y1-topDu)*rs, strokePaint)
            }

            is ArrowShape -> {
                // Shaft
                canvas.drawLine(geo.startX*rs, (geo.startY-topDu)*rs, geo.tipX*rs, (geo.tipY-topDu)*rs, strokePaint)
                // Arrowhead
                val dx = geo.tipX - geo.startX
                val dy = geo.tipY - geo.startY
                val len = kotlin.math.sqrt((dx*dx+dy*dy).toDouble()).toFloat().coerceAtLeast(0.001f)
                val ux = dx/len; val uy = dy/len
                val headLen = (s.strokeWidth * 6f).coerceAtLeast(8f)
                val headW   = headLen * 0.45f
                val bx = (geo.tipX - ux*headLen)*rs
                val by = ((geo.tipY - uy*headLen) - topDu)*rs
                val tx2 = geo.tipX*rs; val ty2 = (geo.tipY-topDu)*rs
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
                val cx = geo.tx*rs;    val cy = (geo.ty-topDu)*rs
                if (geo.rotSin == 0f) {
                    // Axis-aligned → fast drawRect
                    val r = android.graphics.RectF(cx-hw, cy-hh, cx+hw, cy+hh)
                    if (hasFill) { applyShapeStyle(fillPaint,s,rs,true); canvas.drawRect(r, fillPaint) }
                    canvas.drawRect(r, strokePaint)
                } else {
                    val cos = geo.rotCos; val sin = geo.rotSin
                    // Four corners in local space, rotated
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
                val cx = geo.tx*rs; val cy = (geo.ty-topDu)*rs
                val isCircle = (geo.m10 == 0f && geo.m01 == 0f)
                if (isCircle) {
                    val oval = android.graphics.RectF(cx-rx, cy-ry, cx+rx, cy+ry)
                    if (hasFill) { applyShapeStyle(fillPaint,s,rs,true); canvas.drawOval(oval, fillPaint) }
                    canvas.drawOval(oval, strokePaint)
                } else {
                    // Rotated/skewed ellipse → approximate with path (72 segments)
                    path.reset()
                    val steps = 72
                    for (i in 0..steps) {
                        val angle = 2f * Math.PI.toFloat() * i / steps
                        val ex = geo.rx * kotlin.math.cos(angle)
                        val ey = geo.ry * kotlin.math.sin(angle)
                        // Apply the 2x2 rotation part of the affine
                        val wx = (geo.m00 * ex + geo.m01 * ey + geo.tx) * rs
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
                path.moveTo(geo.x0*rs, (geo.y0-topDu)*rs)
                path.quadTo(geo.cpX*rs, (geo.cpY-topDu)*rs, geo.x1*rs, (geo.y1-topDu)*rs)
                canvas.drawPath(path, strokePaint)
            }

            is CubBezShape -> {
                path.reset()
                path.moveTo(geo.x0*rs, (geo.y0-topDu)*rs)
                path.cubicTo(geo.cp1X*rs,(geo.cp1Y-topDu)*rs, geo.cp2X*rs,(geo.cp2Y-topDu)*rs, geo.x1*rs,(geo.y1-topDu)*rs)
                canvas.drawPath(path, strokePaint)
            }

            is PolygonShape -> {
                if (geo.points.size < 2) return
                path.reset()
                path.moveTo(geo.points[0].first*rs, (geo.points[0].second-topDu)*rs)
                for (i in 1 until geo.points.size)
                    path.lineTo(geo.points[i].first*rs, (geo.points[i].second-topDu)*rs)
                path.close()
                if (hasFill) { applyShapeStyle(fillPaint,s,rs,true); canvas.drawPath(path, fillPaint) }
                canvas.drawPath(path, strokePaint)
            }

            is PolylineShape -> {
                if (geo.points.size < 2) return
                path.reset()
                path.moveTo(geo.points[0].first*rs, (geo.points[0].second-topDu)*rs)
                for (i in 1 until geo.points.size)
                    path.lineTo(geo.points[i].first*rs, (geo.points[i].second-topDu)*rs)
                canvas.drawPath(path, strokePaint)
            }
        }
    }

}