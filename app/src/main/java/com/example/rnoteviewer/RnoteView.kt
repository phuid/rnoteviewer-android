package com.example.rnoteviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlinx.coroutines.*

class RnoteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TILE_HEIGHT_DU       = 512f
        private const val MIN_ZOOM             = 0.15f
        private const val MAX_ZOOM             = 12f
        private const val MARGIN_DU            = 20f
        private const val SHADOW_OFFSET        = 4f
        /** Debounce in ms prima di svuotare la cache dopo un pinch */
        private const val TILE_INVALIDATE_DELAY_MS = 150L
    }

    // ──────────────────────────────────────────
    //  State
    // ──────────────────────────────────────────

    private var document: RnoteDocument? = null
    private var rtree: RTree?            = null
    private var tileCache: TileCache?    = null

    private val matrix    = Matrix()
    private val invMatrix = Matrix()

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    /** Zoom al momento dell'ultimo invalidateTiles; serve per capire se è cambiato. */
    private var lastInvalidatedZoom = 0f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val invalidateTilesRunnable = Runnable { invalidateTiles() }

    private val coroutineScope   = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val renderDispatcher = Dispatchers.IO

    /** tile index → coroutine in volo; evita render duplicati. */
    private val pendingRenders = HashMap<Int, Job>()

    // ──────────────────────────────────────────
    //  Gesture detectors
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
                // Solo aggiorna matrice — NON invalidare tile durante il gesto
                updateMatrix()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Schedula il re-render con debounce dopo che l'utente toglie le dita
                mainHandler.removeCallbacks(invalidateTilesRunnable)
                mainHandler.postDelayed(invalidateTilesRunnable, TILE_INVALIDATE_DELAY_MS)
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
                document?.let { fitToWidth(it) }
                return true
            }
        })

    // ──────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────


    // ──────────────────────────────────────────
    //  Navigation API
    // ──────────────────────────────────────────

    /** Salta alla pagina [page] (1-based). */
    fun jumpToPage(page: Int) {
        val doc = document ?: return
        val targetY = (page - 1).coerceAtLeast(0) * doc.pageHeight
        scrollToDocY(targetY)
    }

    /** Scorre in modo che docY (doc-units) sia in cima al viewport. */
    fun scrollToDocY(docY: Float) {
        panY = (MARGIN_DU - docY) * zoom
        clampPan()
        updateMatrix()
    }

    /** Pagina corrente (1-based). */
    fun currentPage(): Int {
        val doc = document ?: return 1
        return (viewportInDocCoords().top / doc.pageHeight).toInt() + 1
    }

    /** Progresso di scroll in [0,1]. */
    fun scrollProgress(): Float {
        val doc = document ?: return 0f
        return (viewportInDocCoords().top / doc.totalHeight).coerceIn(0f, 1f)
    }

    /** Scorre alla posizione corrispondente a [progress] in [0,1]. */
    fun scrollToProgress(progress: Float) {
        val doc = document ?: return
        scrollToDocY(progress * doc.totalHeight)
    }

    /** Imposta un listener chiamato ad ogni scroll/zoom con (progress, pagina, totPagine). */
    fun setScrollListener(l: (Float, Int, Int) -> Unit) { scrollListener = l }
    private var scrollListener: ((Float, Int, Int) -> Unit)? = null

    // ──────────────────────────────────────────
    //  Search Highlights API
    // ──────────────────────────────────────────

    private var searchResults: List<SearchResult> = emptyList()
    private var activeSearchIndex: Int = -1
    private val highlightTempRect = RectF()
    private val pageRect = RectF()

    private val highlightFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFEB3B.toInt() // Semi-transparent Yellow
        style = Paint.Style.FILL
    }
    private val highlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt() // Amber Border
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val activeHighlightFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FF9800.toInt() // Semi-transparent Orange
        style = Paint.Style.FILL
    }
    private val activeHighlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5722.toInt() // Deep Orange Border
        style = Paint.Style.STROKE
        strokeWidth = 3f
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
        val vp = viewportInDocCoords()
        val targetY = result.docRect.centerY() - (vp.height() / 2f)
        scrollToDocY(targetY)
    }

    private fun notifyScroll() {
        val doc = document ?: return
        val total = (doc.totalHeight / doc.pageHeight).toInt().coerceAtLeast(1)
        scrollListener?.invoke(scrollProgress(), currentPage(), total)
    }

    fun setDocument(doc: RnoteDocument, tree: RTree) {
        mainHandler.removeCallbacks(invalidateTilesRunnable)
        cancelAllRenders()
        document = doc
        rtree    = tree

        // Calcola lo zoom iniziale (verrà rifinito in onSizeChanged/fitToWidth)
        val initZoom = if (width > 0)
            (width.toFloat() / (doc.pageWidth + 2 * MARGIN_DU)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        else 1f

        tileCache = TileCache(
            doc          = doc,
            rtree        = tree,
            context      = context,
            tileHeightDu = TILE_HEIGHT_DU,
            scale        = initZoom * resources.displayMetrics.density
        ).also { cache ->
            cache.setOnTileReady { postInvalidate() }   // thread-safe, coalescente
        }

        post { fitToWidth(doc) }
    }

    // ──────────────────────────────────────────
    //  Layout & matrix
    // ──────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        document?.let { fitToWidth(it) }
    }

    private fun fitToWidth(doc: RnoteDocument) {
        if (width == 0) return
        zoom = (width.toFloat() / (doc.pageWidth + 2 * MARGIN_DU)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        panX = MARGIN_DU * zoom
        panY = MARGIN_DU * zoom
        invalidateTiles()       // scala cambiata, tiles non più validi
        updateMatrix()
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
        val docW  = doc.pageWidth   * zoom
        val docH  = doc.totalHeight * zoom
        val marg  = MARGIN_DU * zoom

        val minX = width  - docW  - marg;  val maxX = marg
        val minY = height - docH  - marg;  val maxY = marg

        panX = if (minX <= maxX) panX.coerceIn(minX, maxX) else (width  - docW)  / 2f
        panY = if (minY <= maxY) panY.coerceIn(minY, maxY) else marg
    }

    // ──────────────────────────────────────────
    //  Drawing
    // ──────────────────────────────────────────

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val placeholderPaint = Paint().apply {
        color = Color.rgb(235, 235, 235)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val doc   = document ?: return
        val cache = tileCache ?: return

        canvas.save()
        canvas.concat(matrix)

        pageRect.set(0f, 0f, doc.pageWidth, doc.totalHeight)

        // Ombra della pagina
        canvas.drawRect(
            pageRect.left  + SHADOW_OFFSET, pageRect.top    + SHADOW_OFFSET,
            pageRect.right + SHADOW_OFFSET, pageRect.bottom + SHADOW_OFFSET,
            shadowPaint
        )

        // Tile visibili
        val vp         = viewportInDocCoords()
        val firstTile  = kotlin.math.floor(vp.top    / TILE_HEIGHT_DU).toInt().coerceAtLeast(0)
        val lastTile   = kotlin.math.ceil( vp.bottom / TILE_HEIGHT_DU).toInt().coerceAtMost(cache.tileCount - 1)

        for (ti in firstTile..lastTile) {
            val topDu = cache.tileTopDu(ti)
            val bmp   = cache.get(ti)

            if (bmp != null && !bmp.isRecycled) {
                // Il bitmap è renderizzato a renderScale; il canvas è in doc-units.
                // Disegniamo a (1/renderScale) per tornare alle unità doc.
                val inv = 1f / cache.renderScale
                canvas.save()
                canvas.translate(0f, topDu)
                canvas.scale(inv, inv)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                canvas.restore()
            } else {
                canvas.drawRect(0f, topDu, doc.pageWidth, cache.tileBottomDu(ti), placeholderPaint)
                ensureTileRendered(ti)
            }
        }

        // Search highlights
        if (searchResults.isNotEmpty()) {
            val pad = 3f
            for (i in searchResults.indices) {
                val res = searchResults[i]
                highlightTempRect.set(
                    res.docRect.left - pad,
                    res.docRect.top - pad,
                    res.docRect.right + pad,
                    res.docRect.bottom + pad
                )
                if (i == activeSearchIndex) {
                    canvas.drawRoundRect(highlightTempRect, 4f, 4f, activeHighlightFillPaint)
                    canvas.drawRoundRect(highlightTempRect, 4f, 4f, activeHighlightBorderPaint)
                } else {
                    canvas.drawRoundRect(highlightTempRect, 4f, 4f, highlightFillPaint)
                    canvas.drawRoundRect(highlightTempRect, 4f, 4f, highlightBorderPaint)
                }
            }
        }

        // Bordo pagina
        canvas.drawRect(pageRect, borderPaint)
        canvas.restore()

        // Pre-fetch ±1 tile fuori dal viewport (senza espandersi ulteriormente)
        if (firstTile > 0)                ensureTileRendered(firstTile - 1)
        if (lastTile  < cache.tileCount - 1) ensureTileRendered(lastTile  + 1)
    }

    // ──────────────────────────────────────────
    //  Tile management
    // ──────────────────────────────────────────

    private fun ensureTileRendered(tileIndex: Int) {
        if (pendingRenders.containsKey(tileIndex)) return
        val cache = tileCache ?: return

        val job = coroutineScope.launch {
            withContext(renderDispatcher) { cache.renderSync(tileIndex) }
        }
        pendingRenders[tileIndex] = job
        job.invokeOnCompletion { pendingRenders.remove(tileIndex) }
    }

    private fun invalidateTiles() {
        cancelAllRenders()
        tileCache?.let { cache ->
            val newScale = zoom * resources.displayMetrics.density
            if (newScale == lastInvalidatedZoom) return   // scala invariata, non riallocare
            lastInvalidatedZoom = newScale
            cache.scale = newScale
            cache.invalidateAll()
        }
        invalidate()
    }

    private fun cancelAllRenders() {
        pendingRenders.values.forEach { it.cancel() }
        pendingRenders.clear()
    }

    // ──────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────

    private val _vpPts = FloatArray(4)   // riutilizzato per evitare allocazioni in onDraw
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

    // ──────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacks(invalidateTilesRunnable)
        coroutineScope.cancel()
        tileCache?.invalidateAll()
    }
}
