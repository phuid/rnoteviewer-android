package com.example.rnoteviewer

import android.graphics.Bitmap
import android.graphics.Color
import com.caverock.androidsvg.SVG

data class RnoteColor(val r: Float, val g: Float, val b: Float, val a: Float) {
    fun toAndroidColor(): Int = Color.argb(
        (a * 255f).toInt().coerceIn(0, 255),
        (r * 255f).toInt().coerceIn(0, 255),
        (g * 255f).toInt().coerceIn(0, 255),
        (b * 255f).toInt().coerceIn(0, 255)
    )
    companion object {
        val BLACK = RnoteColor(0f, 0f, 0f, 1f)
        val WHITE = RnoteColor(1f, 1f, 1f, 1f)
        val TRANSPARENT = RnoteColor(0f, 0f, 0f, 0f)
    }
}

enum class PatternType { BLANK, GRID, RULED, DOTS }

data class BackgroundConfig(
    val color: RnoteColor,
    val pattern: PatternType,
    val patternWidth: Float,
    val patternHeight: Float,
    val patternColor: RnoteColor
)

// ── Canvas element sealed hierarchy ──────────────

sealed class CanvasElement {
    abstract val minX: Float; abstract val minY: Float
    abstract val maxX: Float; abstract val maxY: Float
}

data class StrokePoint(val x: Float, val y: Float, val pressure: Float)

data class StrokeElement(
    val points: List<StrokePoint>,
    val strokeWidth: Float, val color: RnoteColor, val isHighlighter: Boolean,
    override val minX: Float, override val minY: Float,
    override val maxX: Float, override val maxY: Float
) : CanvasElement()

data class TextElement(
    val text: String, val x: Float, val y: Float,
    val fontSize: Float, val color: RnoteColor, val maxWidth: Float,
    val fontFamily: String = "", val fontStyle: String = "regular",
    override val minX: Float, override val minY: Float,
    override val maxX: Float, override val maxY: Float
) : CanvasElement()

data class BitmapElement(
    val bitmap: Bitmap,
    val opacity: Float = 1f,
    /** Half-extents in local (pre-transform) space */
    val halfW: Float, val halfH: Float,
    // Full affine 2×2 + translation from the rectangle's transform
    // Column-major order from the JSON [m00, m10, m01, m11, tx, ty]
    val m00: Float = 1f, val m10: Float = 0f,
    val m01: Float = 0f, val m11: Float = 1f,
    val tx: Float = 0f,  val ty: Float = 0f,
    override val minX: Float, override val minY: Float,
    override val maxX: Float, override val maxY: Float
) : CanvasElement()

data class VectorElement(
    val svgData: String,
    val svg: SVG? = null,
    val opacity: Float = 1f,
    /** Half-extents in local (pre-transform) space */
    val halfW: Float, val halfH: Float,
    val intrinsicW: Float = 0f, val intrinsicH: Float = 0f,
    val m00: Float = 1f, val m10: Float = 0f,
    val m01: Float = 0f, val m11: Float = 1f,
    val tx: Float = 0f,  val ty: Float = 0f,
    override val minX: Float, override val minY: Float,
    override val maxX: Float, override val maxY: Float
) : CanvasElement()

// ── Shape styles ──────────────────────────────────

data class ShapeStyle(
    val strokeColor: RnoteColor,
    val strokeWidth: Float,
    val fillColor: RnoteColor,       // alpha=0 → no fill
    val lineDash: Boolean = false    // true = dashed stroke
)

// ── Shape geometry variants ───────────────────────

sealed class ShapeGeometry

data class LineShape(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : ShapeGeometry()

/** Arrow: segment with an arrowhead at [tipX,tipY]. */
data class ArrowShape(val startX: Float, val startY: Float, val tipX: Float, val tipY: Float) : ShapeGeometry()

/** Axis-aligned (or rotated) rectangle via cuboid + affine transform. */
data class RectShape(
    val tx: Float, val ty: Float,   // translation from affine
    val halfW: Float, val halfH: Float,
    val rotCos: Float = 1f, val rotSin: Float = 0f  // from affine m00,m10
) : ShapeGeometry()

/** Ellipse with semi-axes [rx, ry] and an affine transform (supports rotation/skew). */
data class EllipseShape(
    val rx: Float, val ry: Float,
    val tx: Float, val ty: Float,
    val m00: Float = 1f, val m10: Float = 0f,
    val m01: Float = 0f, val m11: Float = 1f
) : ShapeGeometry()

/** Quadratic bezier: start → cp → end. */
data class QuadBezShape(
    val x0: Float, val y0: Float,
    val cpX: Float, val cpY: Float,
    val x1: Float, val y1: Float
) : ShapeGeometry()

/** Cubic bezier: start → cp1 → cp2 → end. */
data class CubBezShape(
    val x0: Float, val y0: Float,
    val cp1X: Float, val cp1Y: Float,
    val cp2X: Float, val cp2Y: Float,
    val x1: Float, val y1: Float
) : ShapeGeometry()

/** Closed polygon. */
data class PolygonShape(val points: List<Pair<Float, Float>>) : ShapeGeometry()

/** Open polyline. */
data class PolylineShape(val points: List<Pair<Float, Float>>) : ShapeGeometry()

// ── ShapeElement ──────────────────────────────────

data class ShapeElement(
    val geometry: ShapeGeometry,
    val style: ShapeStyle,
    override val minX: Float, override val minY: Float,
    override val maxX: Float, override val maxY: Float
) : CanvasElement()

// ── Document root ─────────────────────────────────

data class RnoteDocument(
    val pageWidth: Float, val pageHeight: Float, val totalHeight: Float,
    val background: BackgroundConfig,
    val elements: List<CanvasElement>
)
