package az.chesshelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var held: Image? = null
    @Volatile private var lastFrameAt = 0L

    private var panel: LinearLayout? = null
    private var arrow: ArrowView? = null
    private var status: TextView? = null

    private var engine: Engine? = null
    private var templates: Templates? = null
    private var whiteBottom = true
    private var castling = "KQkq"
    private var castlingKnown = false
    private var lastBoard: BoardRect? = null
    private var lastPieces = 0
    private var busy = false
    private var analysisTimeSeconds = 3
    private var targetElo = 2000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (intent == null || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        try {
            setup(intent.getIntExtra("code", 0), data)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("chess", "Chess Helper", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "chess")
            .setContentTitle("Chess Helper işləyir")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun setup(code: Int, data: Intent) {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        // Android 14+: kadr almazdan əvvəl callback qeyd olunmalıdır
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        val r = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            held?.close()
            held = img
            lastFrameAt = SystemClock.uptimeMillis()
        }, Handler(Looper.getMainLooper()))
        reader = r
        display = proj.createVirtualDisplay(
            "chess", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )

        // əvvəlki oyunlardan saxlanmış şablonlar (mövzu/fiqur dəsti dəyişməyibsə yenidən kalibrə lazım deyil)
        templates = getSharedPreferences("chess", MODE_PRIVATE).getString("tpl2", null)
            ?.let { Templates.parse(it) }

        analysisTimeSeconds = getSharedPreferences("chess", MODE_PRIVATE).getInt("analysis_time_seconds", 3).coerceIn(1, 30)
        targetElo = getSharedPreferences("chess", MODE_PRIVATE).getInt("target_elo", 2000).coerceIn(1320, 3000)
        buildUi()
        say(if (templates != null) "≡ Hazırdır" else "≡ Yeni oyunun əvvəlində Başlat bas")

        scope.launch(Dispatchers.IO) {
            try {
                engine = Engine(this@OverlayService)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { say("Motor xətası: ${e.message}") }
            }
        }
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density

        val a = ArrowView(this)
        arrow = a
        wm.addView(
            a,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
        )

        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (6 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 20, 20, 20))
                cornerRadius = 12 * dp
            }
        }
        val st = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        status = st
        p.addView(st)

        fun btn(label: String, onClick: () -> Unit) {
            p.addView(Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                minHeight = 0
                minimumHeight = 0
                setOnClickListener { onClick() }
            })
        }

        btn("Başlat") { if (!busy) scope.launch { guarded { analyze() } } }
        btn("Yoxla") { if (!busy) scope.launch { guarded { inspect() } } }
        btn("Şablonu yenilə") { if (!busy) scope.launch { guarded { calibrate() } } }
        val timeButton = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            text = "Təhlil vaxtı: ${analysisTimeSeconds}s"
            setOnClickListener {
                val values = listOf(1, 2, 3, 5, 10, 15, 30)
                analysisTimeSeconds = values[(values.indexOf(analysisTimeSeconds).coerceAtLeast(0) + 1) % values.size]
                getSharedPreferences("chess", MODE_PRIVATE).edit().putInt("analysis_time_seconds", analysisTimeSeconds).apply()
                text = "Təhlil vaxtı: ${analysisTimeSeconds}s"
            }
        }
        p.addView(timeButton)
        val eloButton = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            text = "Mühərrik ELO-su: $targetElo"
            setOnClickListener {
                targetElo = if (targetElo >= 3000) 1320 else (targetElo + 100).coerceAtMost(3000)
                getSharedPreferences("chess", MODE_PRIVATE).edit().putInt("target_elo", targetElo).apply()
                text = "Mühərrik ELO-su: $targetElo"
            }
        }
        p.addView(eloButton)
        btn("Bağla") { stopSelf() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (120 * dp).toInt()
        }

        // yazıdan tutub sürüşdürmək
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        st.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = lp.x
                    oy = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt()
                    lp.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(p, lp)
                }
            }
            true
        }

        panel = p
        wm.addView(p, lp)
    }

    private fun say(s: String) {
        status?.text = s
    }

    /** Eyni anda yalnız bir əməliyyat; gözlənilməz xəta servisi çökdürmür. */
    private inline fun guarded(block: () -> Unit) {
        busy = true
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            panel?.visibility = View.VISIBLE
            say("Xəta: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    /**
     * Pəncərələri gizlədib ekran sakitləşəndən sonra (animasiya bitəndə) son kadrı
     * bitmap kimi qaytarır.
     */
    private suspend fun capture(): Bitmap? {
        arrow?.clear()
        panel?.visibility = View.INVISIBLE
        val t0 = SystemClock.uptimeMillis()
        delay(250)
        var waited = 0
        while (
            (held == null || lastFrameAt < t0 || SystemClock.uptimeMillis() - lastFrameAt < 300) &&
            waited < 3000
        ) {
            delay(50)
            waited += 50
        }
        val img = held
        val bmp = try {
            if (img == null) {
                null
            } else {
                val pl = img.planes[0]
                val rowPx = pl.rowStride / pl.pixelStride
                val full = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
                pl.buffer.rewind() // eyni kadr təkrar oxunanda buffer sonda qalmasın
                full.copyPixelsFromBuffer(pl.buffer)
                if (rowPx != img.width) Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full
            }
        } catch (e: Exception) {
            null
        }
        panel?.visibility = View.VISIBLE
        return bmp
    }

    /** Kiçik fərqlərdə əvvəlki çərçivəni saxlayır ki, kəsim şablonlarla eyni olsun. */
    private fun stable(found: BoardRect): BoardRect {
        val l = lastBoard
        val b = if (l != null && BoardReader.sameBoard(found, l)) l else found
        lastBoard = b
        return b
    }

    private fun applyCalibration(r: CalibResult) {
        templates = r.templates
        whiteBottom = r.whiteBottom
        castling = "KQkq"
        castlingKnown = true
        getSharedPreferences("chess", MODE_PRIVATE).edit()
            .putString("tpl2", r.templates.serialize())
            .apply()
    }

    private suspend fun calibrate() {
        say("Şablon yenilənir…")
        val bmp = capture() ?: return say("Ekran tutulmadı")
        val found = withContext(Dispatchers.Default) { BoardReader.findBoard(bmp) }
            ?: return say("Taxta tapılmadı")
        val scan = withContext(Dispatchers.Default) { BoardReader.scan(bmp, stable(found)) }
        val r = withContext(Dispatchers.Default) { BoardReader.calibrate(scan) }
            ?: return say("Yalnız oyunun əvvəlində (ilk 1-2 gediş) yenilənir")
        applyCalibration(r)
        say(if (r.whiteBottom) "Hazır: ağ aşağıda" else "Hazır: qara aşağıda")
    }

    private class Snap(val board: BoardRect, val reading: Reading)

    /** Ekranı tutur, taxtanı tapır və oxuyur. Oyunun əvvəlindəyiksə şablonlar avtomatik öyrənilir. */
    private suspend fun snapshot(): Snap? {
        val bmp = capture()
        if (bmp == null) {
            say("Ekran tutulmadı")
            return null
        }
        val found = withContext(Dispatchers.Default) { BoardReader.findBoard(bmp) }
        if (found == null) {
            say("Taxta tapılmadı")
            return null
        }
        val board = stable(found)
        val scan = withContext(Dispatchers.Default) { BoardReader.scan(bmp, board) }

        // oyunun əvvəlindəyiksə şablonlar, istiqamət və rokirovka avtomatik yenilənir
        val fresh = withContext(Dispatchers.Default) { BoardReader.calibrate(scan) }
        if (fresh != null) applyCalibration(fresh)

        val t = templates
        if (t == null) {
            say("Şablon yoxdur: yeni oyunun ilk 1-2 gedişində bir dəfə Başlat bas")
            return null
        }
        val reading = withContext(Dispatchers.Default) { BoardReader.read(scan, t, whiteBottom) }
        if (reading == null) {
            say("Taxta oxunmadı (fiqur dəsti dəyişibsə yeni oyunun əvvəlində şablonu yenilə)")
            return null
        }
        whiteBottom = reading.whiteBottom

        // fiqur sayı artıbsa yeni oyundur: rokirovka tarixçəsi bilinmir
        val pieces = reading.placement.count { it.isLetter() }
        if (fresh == null && pieces > lastPieces) castlingKnown = false
        lastPieces = pieces
        return Snap(board, reading)
    }

    /** Oxunmuş taxtanı ekranın üstündə göstərir ki, tanımanın düzgünlüyü yoxlanılsın. */
    private suspend fun inspect() {
        say("Yoxlayıram…")
        val snap = snapshot() ?: return
        arrow?.showBoard(snap.board, snap.reading.screen, snap.reading.weak)
        val n = snap.reading.weak.size
        say(if (n == 0) "Hamısı oxundu" else "$n xana şübhəlidir (qırmızı çərçivə)")
    }

    private suspend fun analyze() {
        say("Oxuyuram…")
        val eng = engine ?: return say("Motor hazır deyil")
        val snap = snapshot() ?: return
        val board = snap.board
        val reading = snap.reading

        val castField = if (castlingKnown) {
            castling = BoardReader.updateCastling(reading.placement, castling)
            castling
        } else {
            "-"
        }
        val stm = reading.sideToMove ?: (if (whiteBottom) 'w' else 'b')
        val fen = "${reading.placement} $stm $castField ${reading.enPassant} 0 1"

        val move = withContext(Dispatchers.IO) {
            try {
                eng.configureStrength(targetElo)\n            eng.best(fen, analysisTimeSeconds * 1000)
            } catch (e: Exception) {
                null
            }
        } ?: return say("Gediş tapılmadı")

        val from = move.substring(0, 2)
        val to = move.substring(2, 4)
        val n = reading.weak.size
        say(if (n == 0) "$from → $to" else "$from → $to ($n xana şübhəli, Yoxla bas)")
        val (x1, y1) = BoardReader.center(board, from, whiteBottom)
        val (x2, y2) = BoardReader.center(board, to, whiteBottom)
        // piyada son sıraya çatırsa (məs. e7e8q) yeni fiqur göstərilir
        val piece = if (move.length >= 5) move[4] else BoardReader.pieceAt(fen, from) ?: 'p'
        arrow?.show(x1, y1, x2, y2, piece, board.size / 8f)
    }

    override fun onDestroy() {
        scope.cancel()
        try {
            panel?.let { wm.removeView(it) }
            arrow?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        held?.close()
        display?.release()
        reader?.close()
        projection?.stop()
        engine?.close()
        super.onDestroy()
    }
}
