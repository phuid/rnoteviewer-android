package com.example.rnoteviewer

import android.content.Context
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
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.caverock.androidsvg.SVG
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class RnoteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MIN_ZOOM  = 0.05f
        private const val MAX_ZOOM  = 15f
        private const val MARGIN_DU = 150f
    }

    // ──────────────────────────────────────────
    //  State
    // ──────────────────────────────────────────

    private var document: RnoteDocument? = null
    private var rtree: RTree?            = null

    private val matrix    = Matrix()
    private val invMatrix = Matrix()

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    // ──────────────────────────────────────────
    //  Paints & Reusable Objects
    // ──────────────────────────────────────────

    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val strokePath = Path()

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val imageMatrix = Matrix()

    private val shapeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePath = Path()

    private val originPageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 98, 0, 238)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val pageLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 120, 120, 120)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val pageLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 100, 100, 100)
        textSize = 12f
    }

    // ──────────────────────────────────────────
    //  Gesture Detectors
    // ──────────────────────────────────────────

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val focusX = detector.focusX
                val focusY = detector.focusY
                val newZoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                val factor  = newZoom / zoom
                zoom = newZoom
                panX = focusX + (panX - focusX) * factor
                panY = focusY + (panY - focusY) * factor
                clampPan()
                updateMatrix()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distX: Float, distY: Float
            ): Boolean {
                panX -= distX
                panY -= distY
                clampPan()
                updateMatrix()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                returnToOriginPage()
                return true
            }
        })

    // ──────────────────────────────────────────
    //  Navigation API
    // ──────────────────────────────────────────

    fun returnToOriginPage() {
        val doc = document ?: return
        fitToOriginPage(doc)
    }

    private fun fitToOriginPage(doc: RnoteDocument) {
        if (width == 0) return
        zoom = (width.toFloat() / (doc.pageWidth + 2 * 30f)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        panX = 30f * zoom
        panY = 30f * zoom
        clampPan()
        updateMatrix()
    }

    fun jumpToPage(page: Int) {
        val doc = document ?: return
        val targetY = (page - 1).coerceAtLeast(0) * doc.pageHeight
        scrollToDocY(targetY)
    }

    fun scrollToDocY(docY: Float) {
        val doc = document ?: return
        panY = (30f - docY) * zoom
        panX = (width.toFloat() - doc.pageWidth * zoom) / 2f
        clampPan()
        updateMatrix()
    }

    fun currentPage(): Int {
        val doc = document ?: return 1
        val centerY = viewportInDocCoords().centerY()
        return (centerY / doc.pageHeight).toInt().coerceAtLeast(0) + 1
    }

    fun scrollProgress(): Float {
        val doc = document ?: return 0f
        val cb = doc.contentBounds
        val centerY = viewportInDocCoords().centerY()
        val totalH = cb.height().coerceAtLeast(1f)
        return ((centerY - cb.top) / totalH).coerceIn(0f, 1f)
    }

    fun scrollToProgress(progress: Float) {
        val doc = document ?: return
        val cb = doc.contentBounds
        val targetY = cb.top + progress * cb.height()
        scrollToDocY(targetY)
    }

    fun setScrollListener(l: (Float, Int, Int) -> Unit) { scrollListener = l }
    private var scrollListener: ((Float, Int, Int) -> Unit)? = null

    // ──────────────────────────────────────────
    //  Search Highlights API
    // ──────────────────────────────────────────

    private var searchResults: List<SearchResult> = emptyList()
    private var activeSearchIndex: Int = -1
    private val highlightTempRect = RectF()

    private val highlightFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFEB3B.toInt()
        style = Paint.Style.FILL
    }
    private val highlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt()
        style = Paint.Style.STROKE
    }

    private val activeHighlightFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FF9800.toInt()
        style = Paint.Style.FILL
    }
    private val activeHighlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5722.toInt()
        style = Paint.Style.STROKE
    }

    fun setSearchResults(results: List<SearchResult>, activeIndex: Int = 0) {
        searchResults = results
        activeSearchIndex = if (results.isNotEmpty()) activeIndex.coerceIn(0, results.lastIndex) else -1
        invalidate()
        if (activeSearchIndex >= 0) {
            scrollToSearchResult(activeSearchIndex)
        }
    }

    fun setActiveSearchResult(index: Int) {
        if (searchResults.isEmpty()) return
        activeSearchIndex = index.coerceIn(0, searchResults.lastIndex)
        invalidate()
        scrollToSearchResult(activeSearchIndex)
    }

    fun clearSearchResults() {
        searchResults = emptyList()
        activeSearchIndex = -1
        invalidate()
    }

    fun scrollToSearchResult(index: Int) {
        val result = searchResults.getOrNull(index) ?: return
        panX = (width.toFloat() / 2f) - (result.docRect.centerX() * zoom)
        panY = (height.toFloat() / 2f) - (result.docRect.centerY() * zoom)
        clampPan()
        updateMatrix()
    }

    private fun notifyScroll() {
        val doc = document ?: return
        val totalPages = (doc.contentBounds.height() / doc.pageHeight).toInt().coerceAtLeast(1)
        scrollListener?.invoke(scrollProgress(), currentPage(), totalPages)
    }

    fun setDocument(doc: RnoteDocument, tree: RTree) {
        document = doc
        rtree    = tree
        post { fitToOriginPage(doc) }
    }

    // ──────────────────────────────────────────
    //  Layout & Matrix
    // ──────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        document?.let { fitToOriginPage(it) }
    }

    private fun updateMatrix() {
        matrix.setTranslate(panX, panY)
        matrix.preScale(zoom, zoom)
        matrix.invert(invMatrix)
        invalidate()
        notifyScroll()
    }

    private fun clampPan() {
        val doc = document ?: return
        val cb  = doc.contentBounds

        val minPanX = width - (cb.right + MARGIN_DU) * zoom
        val maxPanX = -(cb.left - MARGIN_DU) * zoom
        val minPanY = height - (cb.bottom + MARGIN_DU) * zoom
        val maxPanY = -(cb.top - MARGIN_DU) * zoom

        panX = if (minPanX <= maxPanX) panX.coerceIn(minPanX, maxPanX) else (width - (cb.left + cb.right) * zoom) / 2f
        panY = if (minPanY <= maxPanY) panY.coerceIn(minPanY, maxPanY) else (height - (cb.top + cb.bottom) * zoom) / 2f
    }

    // ──────────────────────────────────────────
    //  Drawing
    // ──────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val doc  = document ?: return
        val tree = rtree ?: return

        canvas.save()
        canvas.concat(matrix)

        val vp = viewportInDocCoords()
        val cb = doc.contentBounds

        // 1. Fill base infinite plane background
        bgPaint.color = doc.background.color.toAndroidColor()
        val planeLeft   = minOf(vp.left, cb.left - MARGIN_DU)
        val planeRight  = maxOf(vp.right, cb.right + MARGIN_DU)
        val planeTop    = minOf(vp.top, cb.top - MARGIN_DU)
        val planeBottom = maxOf(vp.bottom, cb.bottom + MARGIN_DU)

        canvas.drawRect(planeLeft, planeTop, planeRight, planeBottom, bgPaint)

        // 2. Draw background pattern
        drawPattern(canvas, vp)

        // 3. Query RTree and draw visible elements directly
        val visible = tree.query(vp.left, vp.top, vp.right, vp.bottom)
        for (el in visible) {
            when (el) {
                is StrokeElement -> drawStroke(canvas, el)
                is TextElement   -> drawText(canvas, el)
                is BitmapElement -> drawImage(canvas, el)
                is VectorElement -> drawVectorImage(canvas, el)
                is ShapeElement  -> drawShape(canvas, el)
            }
        }

        // 4. Draw Page Boundary Delimiters
        pageLinePaint.strokeWidth = 1f / zoom
        originPageBorderPaint.strokeWidth = 1.5f / zoom
        pageLabelPaint.textSize = 12f / zoom

        val minPageY = floor(planeTop / doc.pageHeight).toInt()
        val maxPageY = ceil(planeBottom / doc.pageHeight).toInt()

        for (k in minPageY..maxPageY) {
            val y = k * doc.pageHeight
            canvas.drawLine(planeLeft, y, planeRight, y, pageLinePaint)
            if (y >= planeTop && y <= planeBottom) {
                val label = if (k == 0) "Pagina di origine (1)" else "Pagina ${k + 1}"
                canvas.drawText(label, 12f / zoom, y + 16f / zoom, pageLabelPaint)
            }
        }

        canvas.drawLine(0f, planeTop, 0f, planeBottom, pageLinePaint)
        canvas.drawLine(doc.pageWidth, planeTop, doc.pageWidth, planeBottom, pageLinePaint)

        canvas.drawRect(0f, 0f, doc.pageWidth, doc.pageHeight, originPageBorderPaint)

        // 5. Search highlights
        if (searchResults.isNotEmpty()) {
            val pad = 3f / zoom
            highlightBorderPaint.strokeWidth = 1.5f / zoom
            activeHighlightBorderPaint.strokeWidth = 3f / zoom

            for (i in searchResults.indices) {
                val res = searchResults[i]
                highlightTempRect.set(
                    res.docRect.left - pad,
                    res.docRect.top - pad,
                    res.docRect.right + pad,
                    res.docRect.bottom + pad
                )
                if (i == activeSearchIndex) {
                    canvas.drawRoundRect(highlightTempRect, 4f / zoom, 4f / zoom, activeHighlightFillPaint)
                    canvas.drawRoundRect(highlightTempRect, 4f / zoom, 4f / zoom, activeHighlightBorderPaint)
                } else {
                    canvas.drawRoundRect(highlightTempRect, 4f / zoom, 4f / zoom, highlightFillPaint)
                    canvas.drawRoundRect(highlightTempRect, 4f / zoom, 4f / zoom, highlightBorderPaint)
                }
            }
        }

        canvas.restore()
    }

    private fun drawPattern(canvas: Canvas, vp: RectF) {
        val bg = document?.background ?: return
        if (bg.pattern == PatternType.BLANK) return

        patternPaint.color = bg.patternColor.toAndroidColor()
        patternPaint.strokeWidth = 1f / zoom

        val cellW = bg.patternWidth
        val cellH = bg.patternHeight
        if (cellW <= 0f || cellH <= 0f) return

        fun posMod(value: Float, step: Float): Float {
            val m = value % step
            return if (m < 0) m + step else m
        }

        val offsetX = posMod(vp.left, cellW)
        val offsetY = posMod(vp.top, cellH)

        val startX = vp.left - offsetX
        val endX   = vp.right + cellW
        val startY = vp.top - offsetY
        val endY   = vp.bottom + cellH

        when (bg.pattern) {
            PatternType.GRID -> {
                var x = startX
                while (x <= endX) {
                    canvas.drawLine(x, vp.top, x, vp.bottom, patternPaint)
                    x += cellW
                }
                var y = startY
                while (y <= endY) {
                    canvas.drawLine(vp.left, y, vp.right, y, patternPaint)
                    y += cellH
                }
            }
            PatternType.RULED -> {
                var y = startY
                while (y <= endY) {
                    canvas.drawLine(vp.left, y, vp.right, y, patternPaint)
                    y += cellH
                }
            }
            PatternType.DOTS -> {
                dotPaint.color = bg.patternColor.toAndroidColor()
                val radius = 1.5f / zoom
                var y = startY
                while (y <= endY) {
                    var x = startX
                    while (x <= endX) {
                        canvas.drawCircle(x, y, radius, dotPaint)
                        x += cellW
                    }
                    y += cellH
                }
            }
            else -> {}
        }
    }

    private fun drawStroke(canvas: Canvas, el: StrokeElement) {
        if (el.points.size < 2) return
        val argb = el.color.toAndroidColor()
        strokePaint.color = argb
        strokePaint.strokeWidth = el.strokeWidth
        strokePaint.alpha = if (el.isHighlighter) 80 else Color.alpha(argb)

        strokePath.reset()
        strokePath.moveTo(el.points[0].x, el.points[0].y)
        for (k in 1 until el.points.size) {
            val pt = el.points[k]
            strokePath.lineTo(pt.x, pt.y)
        }
        canvas.drawPath(strokePath, strokePaint)
    }

    private fun drawText(canvas: Canvas, el: TextElement) {
        val typeface = FontManager.resolve(el.fontFamily, el.fontStyle)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = el.color.toAndroidColor()
            textSize = el.fontSize
            this.typeface = typeface
        }
        val wrapPx = if (el.maxWidth > 0f) el.maxWidth.toInt()
                     else document?.pageWidth?.toInt() ?: 800

        canvas.save()
        canvas.translate(el.x, el.y)

        @Suppress("DEPRECATION")
        val layout = StaticLayout(
            el.text, tp, wrapPx.coerceAtLeast(1),
            Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false
        )
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawImage(canvas: Canvas, el: BitmapElement) {
        if (el.bitmap.isRecycled) return

        val pw = el.bitmap.width.toFloat().coerceAtLeast(1f)
        val ph = el.bitmap.height.toFloat().coerceAtLeast(1f)

        val sx = 2f * el.halfW / pw
        val sy = 2f * el.halfH / ph

        val a = el.m00 * sx
        val b = el.m01 * sy
        val c = el.tx - el.m00 * el.halfW - el.m01 * el.halfH
        val d = el.m10 * sx
        val e = el.m11 * sy
        val f = el.ty - el.m10 * el.halfW - el.m11 * el.halfH

        imageMatrix.setValues(floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f))
        imagePaint.alpha = if (el.opacity < 1f) (el.opacity * 255f).toInt().coerceIn(0, 255) else 255

        canvas.drawBitmap(el.bitmap, imageMatrix, imagePaint)
    }

    private fun drawVectorImage(canvas: Canvas, el: VectorElement) {
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

        val a = el.m00 * scaleX
        val b = el.m01 * scaleY
        val c = el.tx - el.m00 * el.halfW - el.m01 * el.halfH
        val d = el.m10 * scaleX
        val e = el.m11 * scaleY
        val f = el.ty - el.m10 * el.halfW - el.m11 * el.halfH

        val mat = Matrix()
        mat.setValues(floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f))

        canvas.save()
        canvas.concat(mat)

        try {
            svg.renderToCanvas(canvas)
        } catch (_: Exception) {
        } finally {
            canvas.restore()
        }
    }

    private fun applyShapeStyle(paint: Paint, style: ShapeStyle, filled: Boolean) {
        if (filled) {
            paint.style = Paint.Style.FILL
            paint.color = style.fillColor.toAndroidColor()
        } else {
            paint.style = Paint.Style.STROKE
            paint.color = style.strokeColor.toAndroidColor()
            paint.strokeWidth = style.strokeWidth
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            if (style.lineDash) {
                paint.pathEffect = DashPathEffect(
                    floatArrayOf(style.strokeWidth * 4f, style.strokeWidth * 2f), 0f)
            } else {
                paint.pathEffect = null
            }
        }
    }

    private fun drawShape(canvas: Canvas, el: ShapeElement) {
        val s = el.style
        val hasFill = s.fillColor.a > 0.01f

        applyShapeStyle(shapeStrokePaint, s, false)

        when (val geo = el.geometry) {
            is LineShape -> {
                canvas.drawLine(geo.x0, geo.y0, geo.x1, geo.y1, shapeStrokePaint)
            }
            is ArrowShape -> {
                canvas.drawLine(geo.startX, geo.startY, geo.tipX, geo.tipY, shapeStrokePaint)
                val dx = geo.tipX - geo.startX
                val dy = geo.tipY - geo.startY
                val len = sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(0.001f)
                val ux = dx / len; val uy = dy / len
                val headLen = (s.strokeWidth * 6f).coerceAtLeast(8f)
                val headW = headLen * 0.45f
                val bx = geo.tipX - ux * headLen
                val by = geo.tipY - uy * headLen
                shapePath.reset()
                shapePath.moveTo(bx - uy * headW, by + ux * headW)
                shapePath.lineTo(geo.tipX, geo.tipY)
                shapePath.lineTo(bx + uy * headW, by - ux * headW)
                if (hasFill) {
                    shapePath.close()
                    applyShapeStyle(shapeFillPaint, s, true)
                    canvas.drawPath(shapePath, shapeFillPaint)
                }
                canvas.drawPath(shapePath, shapeStrokePaint)
            }
            is RectShape -> {
                shapePath.reset()
                val hw = geo.halfW; val hh = geo.halfH
                val cx = geo.tx; val cy = geo.ty
                if (geo.rotSin == 0f) {
                    val r = RectF(cx - hw, cy - hh, cx + hw, cy + hh)
                    if (hasFill) { applyShapeStyle(shapeFillPaint, s, true); canvas.drawRect(r, shapeFillPaint) }
                    canvas.drawRect(r, shapeStrokePaint)
                } else {
                    val cos = geo.rotCos; val sin = geo.rotSin
                    fun corner(lx: Float, ly: Float): Pair<Float, Float> =
                        Pair(cx + lx * cos - ly * sin, cy + lx * sin + ly * cos)
                    val (ax, ay) = corner(-hw, -hh); val (bx, by) = corner(hw, -hh)
                    val (cx2, cy2) = corner(hw, hh); val (dx2, dy2) = corner(-hw, hh)
                    shapePath.moveTo(ax, ay); shapePath.lineTo(bx, by); shapePath.lineTo(cx2, cy2); shapePath.lineTo(dx2, dy2); shapePath.close()
                    if (hasFill) { applyShapeStyle(shapeFillPaint, s, true); canvas.drawPath(shapePath, shapeFillPaint) }
                    canvas.drawPath(shapePath, shapeStrokePaint)
                }
            }
            is EllipseShape -> {
                val rx = geo.rx; val ry = geo.ry
                val cx = geo.tx; val cy = geo.ty
                val isCircle = (geo.m10 == 0f && geo.m01 == 0f)
                val oval = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
                if (isCircle) {
                    if (hasFill) { applyShapeStyle(shapeFillPaint, s, true); canvas.drawOval(oval, shapeFillPaint) }
                    canvas.drawOval(oval, shapeStrokePaint)
                } else {
                    shapePath.reset()
                    val steps = 72
                    for (i in 0..steps) {
                        val angle = 2f * Math.PI.toFloat() * i / steps
                        val ex = geo.rx * cos(angle)
                        val ey = geo.ry * sin(angle)
                        val wx = geo.m00 * ex + geo.m01 * ey + geo.tx
                        val wy = geo.m10 * ex + geo.m11 * ey + geo.ty
                        if (i == 0) shapePath.moveTo(wx, wy) else shapePath.lineTo(wx, wy)
                    }
                    shapePath.close()
                    if (hasFill) { applyShapeStyle(shapeFillPaint, s, true); canvas.drawOval(oval, shapeFillPaint) }
                    canvas.drawPath(shapePath, shapeStrokePaint)
                }
            }
            is QuadBezShape -> {
                shapePath.reset()
                shapePath.moveTo(geo.x0, geo.y0)
                shapePath.quadTo(geo.cpX, geo.cpY, geo.x1, geo.y1)
                canvas.drawPath(shapePath, shapeStrokePaint)
            }
            is CubBezShape -> {
                shapePath.reset()
                shapePath.moveTo(geo.x0, geo.y0)
                shapePath.cubicTo(geo.cp1X, geo.cp1Y, geo.cp2X, geo.cp2Y, geo.x1, geo.y1)
                canvas.drawPath(shapePath, shapeStrokePaint)
            }
            is PolygonShape -> {
                if (geo.points.size < 2) return
                shapePath.reset()
                shapePath.moveTo(geo.points[0].first, geo.points[0].second)
                for (i in 1 until geo.points.size)
                    shapePath.lineTo(geo.points[i].first, geo.points[i].second)
                shapePath.close()
                if (hasFill) { applyShapeStyle(shapeFillPaint, s, true); canvas.drawPath(shapePath, shapeFillPaint) }
                canvas.drawPath(shapePath, shapeStrokePaint)
            }
            is PolylineShape -> {
                if (geo.points.size < 2) return
                shapePath.reset()
                shapePath.moveTo(geo.points[0].first, geo.points[0].second)
                for (i in 1 until geo.points.size)
                    shapePath.lineTo(geo.points[i].first, geo.points[i].second)
                canvas.drawPath(shapePath, shapeStrokePaint)
            }
        }
    }

    // ──────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────

    private val _vpPts = FloatArray(4)
    private fun viewportInDocCoords(): RectF {
        _vpPts[0] = 0f; _vpPts[1] = 0f
        _vpPts[2] = width.toFloat(); _vpPts[3] = height.toFloat()
        invMatrix.mapPoints(_vpPts)
        return RectF(_vpPts[0], _vpPts[1], _vpPts[2], _vpPts[3])
    }

    // ──────────────────────────────────────────
    //  Touch
    // ──────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
}
