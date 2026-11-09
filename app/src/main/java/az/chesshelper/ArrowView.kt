package az.chesshelper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Ekranın üstündə gedişi göstərir: çıxış xanasında halqa, ox və hədəf xanada
 * parıldayan (nəbz vuran) fiqur. Fiqurlar şriftdən asılı olmayaraq vektor şəkildə çəkilir.
 * Toxunuşları keçirir.
 */
class ArrowView(c: Context) : View(c) {

    private val neon = Color.rgb(0, 255, 190)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 255, 190)
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 190)
        style = Paint.Style.FILL
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = neon
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = neon
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = neon
        style = Paint.Style.FILL
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    private val pieceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pieceEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val weakRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 60, 60)
        style = Paint.Style.STROKE
    }
    private val boardGuide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 255, 190)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // "Yoxla" rejimi: oxunmuş taxtanı üstünə çəkir
    private var boardRect: BoardRect? = null
    private var screenGrid: Array<CharArray>? = null
    private var weakSquares: Set<Int> = emptySet()
    private var sourceWidth = 0
    private var sourceHeight = 0

    private val shapes = HashMap<Char, Path>()
    private val tmp = Path()
    private val mtx = Matrix()

    private var pts: FloatArray? = null
    private var piece: Char = 'p'
    private var sq = 100f
    private var pulse = 0f

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }
    private val autoClear = Runnable { clear() }

    /** [piece]: FEN simvolu (məsələn 'N', 'p'), [squareSize]: bir xananın piksel ölçüsü. */
    fun show(x1: Float, y1: Float, x2: Float, y2: Float, piece: Char, squareSize: Float, frameWidth: Int, frameHeight: Int) {
        boardRect = null
        screenGrid = null
        sourceWidth = frameWidth
        sourceHeight = frameHeight
        pts = floatArrayOf(x1, y1, x2, y2)
        this.piece = piece
        sq = squareSize
        if (!anim.isRunning) anim.start()
        invalidate()
        removeCallbacks(autoClear)
        postDelayed(autoClear, 7000)
    }

    /** Oxunmuş taxtanı göstərir: hər xanada tanınmış fiqur, şübhəli xanalarda qırmızı halqa. */
    fun showBoard(r: BoardRect, grid: Array<CharArray>, weak: Set<Int>, frameWidth: Int, frameHeight: Int) {
        pts = null
        sourceWidth = frameWidth
        sourceHeight = frameHeight
        anim.cancel()
        boardRect = r
        screenGrid = grid
        weakSquares = weak
        invalidate()
        removeCallbacks(autoClear)
        postDelayed(autoClear, 6000)
    }

    fun clear() {
        pts = null
        boardRect = null
        screenGrid = null
        sourceWidth = 0
        sourceHeight = 0
        anim.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(autoClear)
        anim.cancel()
        super.onDetachedFromWindow()
    }

    // ---- vektor fiqurlar (100x100 vahid qutuda) ----

    private fun poly(vararg v: Float): Path = Path().apply {
        moveTo(v[0], v[1])
        var i = 2
        while (i + 1 < v.size) {
            lineTo(v[i], v[i + 1])
            i += 2
        }
        close()
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float): Path =
        Path().apply { addRect(l, t, r, b, Path.Direction.CW) }

    private fun disc(cx: Float, cy: Float, r: Float): Path =
        Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }

    private fun oval(l: Float, t: Float, r: Float, b: Float): Path =
        Path().apply { addOval(l, t, r, b, Path.Direction.CW) }

    private fun buildShape(p: Char): Path {
        val parts = ArrayList<Path>()
        parts.add(rect(26f, 80f, 74f, 90f)) // taban
        when (p) {
            'p' -> {
                parts.add(disc(50f, 30f, 12f))
                parts.add(poly(40f, 42f, 60f, 42f, 68f, 80f, 32f, 80f))
            }
            'r' -> {
                parts.add(rect(28f, 22f, 38f, 38f))
                parts.add(rect(45f, 22f, 55f, 38f))
                parts.add(rect(62f, 22f, 72f, 38f))
                parts.add(rect(28f, 34f, 72f, 46f))
                parts.add(rect(34f, 46f, 66f, 80f))
            }
            'b' -> {
                parts.add(disc(50f, 20f, 6f))
                parts.add(oval(36f, 26f, 64f, 70f))
                parts.add(poly(38f, 64f, 62f, 64f, 68f, 80f, 32f, 80f))
            }
            'n' -> {
                parts.add(
                    poly(
                        30f, 80f, 34f, 58f, 44f, 44f, 36f, 38f, 40f, 26f,
                        52f, 12f, 56f, 20f, 66f, 24f, 72f, 44f, 70f, 80f
                    )
                )
            }
            'q' -> {
                parts.add(
                    poly(
                        30f, 80f, 22f, 30f, 29f, 52f, 36f, 24f, 43f, 52f, 50f, 20f,
                        57f, 52f, 64f, 24f, 71f, 52f, 78f, 30f, 70f, 80f
                    )
                )
                parts.add(disc(22f, 28f, 4f))
                parts.add(disc(36f, 22f, 4f))
                parts.add(disc(50f, 18f, 4f))
                parts.add(disc(64f, 22f, 4f))
                parts.add(disc(78f, 28f, 4f))
            }
            'k' -> {
                parts.add(poly(30f, 80f, 36f, 52f, 64f, 52f, 70f, 80f))
                parts.add(oval(34f, 32f, 66f, 58f))
                parts.add(rect(46f, 8f, 54f, 34f))
                parts.add(rect(38f, 16f, 62f, 24f))
            }
            else -> parts.add(disc(50f, 50f, 20f))
        }
        val out = Path(parts[0])
        for (i in 1 until parts.size) out.op(parts[i], Path.Op.UNION)
        return out
    }

    private fun shapeOf(p: Char): Path {
        val key = p.lowercaseChar()
        return shapes.getOrPut(key) { buildShape(key) }
    }

    private fun drawBoardView(canvas: Canvas, r: BoardRect, grid: Array<CharArray>) {
        val sqs = r.size / 8f
        val sc = sqs * 0.62f / 100f
        pieceEdge.strokeWidth = sqs * 0.03f
        weakRing.strokeWidth = sqs * 0.05f
        boardGuide.strokeWidth = (sqs * 0.018f).coerceAtLeast(1.2f)
        // Show the detected 8x8 geometry so every square can be checked against the
        // original board, not only the recognized piece silhouettes.
        for (i in 0..8) {
            val vx = r.x + i * sqs
            val hy = r.y + i * sqs
            canvas.drawLine(vx, r.y, vx, r.y + r.size, boardGuide)
            canvas.drawLine(r.x, hy, r.x + r.size, hy, boardGuide)
        }
        canvas.drawRect(r.x, r.y, r.x + r.size, r.y + r.size, boardGuide)
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val ch = grid[row][col]
                val cx = r.x + (col + 0.5f) * sqs
                val cy = r.y + (row + 0.5f) * sqs
                if (ch != '.') {
                    val white = ch.isUpperCase()
                    pieceFill.color = if (white) Color.argb(235, 255, 255, 255) else Color.argb(235, 40, 40, 40)
                    pieceEdge.color = if (white) Color.rgb(20, 20, 20) else Color.rgb(240, 240, 240)
                    mtx.reset()
                    mtx.setScale(sc, sc)
                    mtx.postTranslate(cx - 50f * sc, cy - 50f * sc)
                    tmp.reset()
                    shapeOf(ch).transform(mtx, tmp)
                    canvas.drawPath(tmp, pieceFill)
                    canvas.drawPath(tmp, pieceEdge)
                }
                if (weakSquares.contains(row * 8 + col)) {
                    canvas.drawRect(
                        r.x + col * sqs + 3f, r.y + row * sqs + 3f,
                        r.x + (col + 1) * sqs - 3f, r.y + (row + 1) * sqs - 3f, weakRing
                    )
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        // MediaProjection coordinates are physical pixels; the overlay View may have
        // different dimensions because of Android insets or OEM window sizing.
        val sx = if (sourceWidth > 0 && width > 0) width.toFloat() / sourceWidth else 1f
        val sy = if (sourceHeight > 0 && height > 0) height.toFloat() / sourceHeight else 1f
        canvas.save()
        canvas.scale(sx, sy)
        val br = boardRect
        val sg = screenGrid
        if (br != null && sg != null) {
            drawBoardView(canvas, br, sg)
            canvas.restore()
            return
        }
        val p = pts
        if (p == null) {
            canvas.restore()
            return
        }
        val x1 = p[0]
        val y1 = p[1]
        val x2 = p[2]
        val y2 = p[3]
        val d = hypot(x2 - x1, y2 - y1)
        if (d < 1f) {
            canvas.restore()
            return
        }
        val ux = (x2 - x1) / d
        val uy = (y2 - y1) / d

        // çıxış xanası: halqa
        ring.alpha = 140 + (pulse * 100).toInt()
        canvas.drawCircle(x1, y1, sq * 0.42f, ring)

        // ox: hədəf fiqurun kənarında bitir
        val tipX = x2 - ux * sq * 0.55f
        val tipY = y2 - uy * sq * 0.55f
        val ang = atan2(uy, ux)
        val head = sq * 0.3f
        canvas.drawLine(
            x1 + ux * sq * 0.42f, y1 + uy * sq * 0.42f,
            tipX - cos(ang) * head * 0.6f, tipY - sin(ang) * head * 0.6f, line
        )
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tipX - head * cos(ang - 0.45f), tipY - head * sin(ang - 0.45f))
            lineTo(tipX - head * cos(ang + 0.45f), tipY - head * sin(ang + 0.45f))
            close()
        }
        canvas.drawPath(path, fill)

        // hədəf xana: parıltı
        val r = sq * (0.75f + 0.2f * pulse)
        halo.shader = RadialGradient(
            x2, y2, r,
            intArrayOf(
                Color.argb(230, 0, 255, 190),
                Color.argb(90, 0, 255, 190),
                Color.argb(0, 0, 255, 190)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x2, y2, r, halo)

        // fiqur: vektor şəkil, parıltılı kontur və ağ haşiyə
        val s = sq * 0.85f / 100f
        mtx.reset()
        mtx.setScale(s, s)
        mtx.postTranslate(x2 - 50f * s, y2 - 50f * s)
        tmp.reset()
        shapeOf(piece).transform(mtx, tmp)
        glow.strokeWidth = sq * 0.16f
        glow.alpha = 70 + (pulse * 110).toInt()
        edge.strokeWidth = sq * 0.035f
        canvas.drawPath(tmp, glow)
        canvas.drawPath(tmp, body)
        canvas.drawPath(tmp, edge)
        canvas.restore()
    }
}
