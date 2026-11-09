package az.chesshelper

import android.app.AlertDialog
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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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
    private val frameLock = Any()
    @Volatile private var latestFrame: Bitmap? = null
    private var frameThread: HandlerThread? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var engine: Engine? = null
    @Volatile private var destroyed = false

    private var panel: LinearLayout? = null
    private var bubble: TextView? = null
    private var arrow: ArrowView? = null
    private var status: TextView? = null

    private var templates: Templates? = null
    private var whiteBottom = true
    private var castling = "-"
    private var castlingKnown = false
    private var previousPlacement: String? = null
    private var sideOverride: Char? = null
    private var lastAnalyzedPositionKey: String? = null
    private var candidatePositionKey: String? = null
    private var lastBoard: BoardRect? = null
    private var lastPieces = 0
    private var busy = false
    private var analysisTimeSeconds = 3
    private var targetElo = 2000
    private var maxEngineStrength = false
    @Volatile private var autoRunning = false
    private var lastAnalyzedPlacement: String? = null
    private var candidatePlacement: String? = null
    private var stableFrameCount = 0
    private var lastRecognitionMs = 0L
    private var lastEngineMs = 0L
    private var lastConfidence = 0

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

        val callbackThread = HandlerThread("ChessHelperFrameCopy").apply { start() }
        frameThread = callbackThread
        val r = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val rowPixels = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
                plane.buffer.rewind()
                padded.copyPixelsFromBuffer(plane.buffer)
                val frame = if (rowPixels == img.width) padded else
                    Bitmap.createBitmap(padded, 0, 0, img.width, img.height).also { padded.recycle() }
                synchronized(frameLock) {
                    val old = latestFrame
                    latestFrame = frame
                    if (old != null && old !== frame && !old.isRecycled) old.recycle()
                    lastFrameAt = SystemClock.uptimeMillis()
                }
            } catch (_: Exception) {
                // A damaged capture is ignored; the last complete frame remains available.
            } finally {
                img.close()
            }
        }, Handler(callbackThread.looper))
        reader = r
        display = proj.createVirtualDisplay(
            "chess", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )

        // əvvəlki oyunlardan saxlanmış şablonlar (mövzu/fiqur dəsti dəyişməyibsə yenidən kalibrə lazım deyil)
        templates = getSharedPreferences("chess", MODE_PRIVATE).getString("tpl2", null)
            ?.let { Templates.parse(it) }
        whiteBottom = getSharedPreferences("chess", MODE_PRIVATE).getBoolean("white_bottom", true)
        sideOverride = when (getSharedPreferences("chess", MODE_PRIVATE).getString("side_override", "auto")) {
            "white" -> 'w'
            "black" -> 'b'
            else -> null
        }

        analysisTimeSeconds = getSharedPreferences("chess", MODE_PRIVATE).getInt("analysis_time_seconds", 3).coerceIn(1, 30)
        targetElo = getSharedPreferences("chess", MODE_PRIVATE).getInt("target_elo", 2000).coerceIn(1320, 3000)
        maxEngineStrength = getSharedPreferences("chess", MODE_PRIVATE).getBoolean("max_engine_strength", false)
        buildUi()
        say(if (templates != null) "≡ Hazırdır" else "≡ Yeni oyunun əvvəlində Başlat bas")

        scope.launch(Dispatchers.IO) {
            var created: Engine? = null
            try {
                created = Engine(this@OverlayService)
                withContext(Dispatchers.Main) {
                    if (destroyed) created.close() else engine = created
                }
            } catch (e: Exception) {
                created?.close()
                withContext(Dispatchers.Main) {
                    if (!destroyed) say("Motor xətası: ${e.message}")
                }
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
                val theme = getSharedPreferences("chess", MODE_PRIVATE).getString("theme", "dark")
                val color = when (theme) {
                    "midnight" -> Color.rgb(10, 22, 48)
                    "amoled" -> Color.BLACK
                    else -> Color.rgb(20, 20, 20)
                }
                setColor(Color.argb(225, Color.red(color), Color.green(color), Color.blue(color)))
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

        btn("Avtomatik başlat") { startAutomaticAnalysis() }
        btn("Dayandır") { autoRunning = false; say("Avtomatik analiz dayandırıldı") }
        val sideButton = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            setSideButtonLabel(this)
            setOnClickListener {
                sideOverride = when (sideOverride) { null -> 'w'; 'w' -> 'b'; else -> null }
                getSharedPreferences("chess", MODE_PRIVATE).edit()
                    .putString("side_override", when (sideOverride) { 'w' -> "white"; 'b' -> "black"; else -> "auto" }).apply()
                setSideButtonLabel(this)
            }
        }
        p.addView(sideButton)
        btn("Yoxla") { if (!busy) scope.launch { guarded { inspect() } } }
        btn("Şablonu yenilə") { if (!busy) scope.launch { guarded { calibrate() } } }
        val timeButton = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            text = "Təhlil vaxtı: ${analysisTimeSeconds}s"
            setOnClickListener { showAnalysisTimeDialog(this) }
        }
        p.addView(timeButton)
        val eloButton = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            text = "Mühərrik ELO-su: ${if (maxEngineStrength) "Maksimum" else targetElo}"
            setOnClickListener {
                maxEngineStrength = false
                targetElo = if (targetElo >= 3000) 1320 else (targetElo + 20).coerceAtMost(3000)
                getSharedPreferences("chess", MODE_PRIVATE).edit()
                    .putInt("target_elo", targetElo)
                    .putBoolean("max_engine_strength", false)
                    .apply()
                text = "Mühərrik ELO-su: $targetElo"
                say("Mühərrik hədəfi: $targetElo ELO")
            }
        }
        p.addView(eloButton)
        val maxButton = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            text = if (maxEngineStrength) "Maksimum güc: Aktiv" else "Maksimum gücü seç"
            setOnClickListener {
                maxEngineStrength = !maxEngineStrength
                getSharedPreferences("chess", MODE_PRIVATE).edit()
                    .putBoolean("max_engine_strength", maxEngineStrength)
                    .apply()
                text = if (maxEngineStrength) "Maksimum güc: Aktiv" else "Maksimum gücü seç"
                eloButton.text = "Mühərrik ELO-su: ${if (maxEngineStrength) "Maksimum" else targetElo}"
                say(if (maxEngineStrength) "Mühərrik maksimum gücdə işləyəcək" else "ELO məhdudiyyəti aktivdir")
            }
        }
        p.addView(maxButton)
        btn("Tarixçə (son 100)") { showHistory() }
        btn("PGN ixrac et") { shareExport(SessionStore.toPgn(this), "application/x-chess-pgn") }
        btn("UCI ixrac et") { shareExport(SessionStore.toUci(this), "text/plain") }
        btn("Performans") { showPerformance() }
        btn("Mövzu: dəyiş") { cycleTheme(p) }
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

        // 48dp circular quick-toggle bubble; dragging moves it, tapping opens/closes the full panel.
        val bubbleView = TextView(this).apply {
            text = "♟"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(0, 150, 120))
                setStroke((1 * dp).toInt(), Color.argb(220, 180, 255, 240))
            }
        }
        bubble = bubbleView
        val blp = WindowManager.LayoutParams(
            (48 * dp).toInt(), (48 * dp).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * dp).toInt()
            y = (220 * dp).toInt()
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        bubbleView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = blp.x; startY = blp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 6 || kotlin.math.abs(dy) > 6) moved = true
                    blp.x = startX + dx; blp.y = startY + dy
                    wm.updateViewLayout(view, blp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val showing = p.visibility == View.VISIBLE
                        p.visibility = if (showing) View.GONE else View.VISIBLE
                        bubbleView.alpha = if (showing) 1f else 0.75f
                    }
                    true
                }
                else -> true
            }
        }
        wm.addView(bubbleView, blp)
    }

    private fun showAnalysisTimeDialog(button: Button) {
        val choices = arrayOf("Fast — 1 saniyə", "Normal — 3 saniyə", "Deep — 10 saniyə", "Custom — 1–30 saniyə")
        AlertDialog.Builder(this)
            .setTitle("Təhlil müddəti")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> setAnalysisTime(1, button)
                    1 -> setAnalysisTime(3, button)
                    2 -> setAnalysisTime(10, button)
                    3 -> {
                        val input = EditText(this).apply {
                            inputType = InputType.TYPE_CLASS_NUMBER
                            hint = "1–30 saniyə"
                            setText(analysisTimeSeconds.toString())
                            selectAll()
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Fərdi təhlil müddəti")
                            .setView(input)
                            .setNegativeButton("Ləğv et", null)
                            .setPositiveButton("Yadda saxla") { _, _ ->
                                val seconds = input.text.toString().toIntOrNull()
                                if (seconds == null || seconds !in 1..30) {
                                    say("Vaxt 1–30 saniyə arasında olmalıdır")
                                } else {
                                    setAnalysisTime(seconds, button)
                                }
                            }
                            .show()
                    }
                }
            }
            .show()
    }

    private fun setAnalysisTime(seconds: Int, button: Button) {
        analysisTimeSeconds = seconds.coerceIn(1, 30)
        getSharedPreferences("chess", MODE_PRIVATE).edit()
            .putInt("analysis_time_seconds", analysisTimeSeconds)
            .apply()
        button.text = "Təhlil vaxtı: ${analysisTimeSeconds}s"
        say("Təhlil müddəti: ${analysisTimeSeconds} saniyə")
    }

    private fun say(s: String) {
        status?.text = s
    }

    private fun setSideButtonLabel(button: Button) {
        button.text = "Növbə: ${when (sideOverride) { 'w' -> "Ağ"; 'b' -> "Qara"; else -> "Avtomatik" }}"
    }

    private fun startAutomaticAnalysis() {
        if (autoRunning) return
        autoRunning = true
        lastAnalyzedPlacement = null
        say("Avtomatik tanıma başladıldı")
        scope.launch {
            while (autoRunning) {
                if (!busy) {
                    try {
                        busy = true
                        analyzeOnce()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        say("Xəta: ${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        busy = false
                    }
                }
                delay(900)
            }
        }
    }

    private fun cycleTheme(target: LinearLayout) {
        val prefs = getSharedPreferences("chess", MODE_PRIVATE)
        val themes = listOf("dark", "midnight", "amoled")
        val current = prefs.getString("theme", "dark") ?: "dark"
        val next = themes[(themes.indexOf(current).coerceAtLeast(0) + 1) % themes.size]
        prefs.edit().putString("theme", next).apply()
        val bg = when (next) {
            "midnight" -> Color.rgb(10, 22, 48)
            "amoled" -> Color.BLACK
            else -> Color.rgb(20, 20, 20)
        }
        (target.background as? GradientDrawable)?.setColor(Color.argb(225, Color.red(bg), Color.green(bg), Color.blue(bg)))
        say("Mövzu: ${when (next) { "midnight" -> "Midnight Blue"; "amoled" -> "AMOLED"; else -> "Dark" }}")
    }

    private fun showHistory() {
        val items = SessionStore.read(this).takeLast(20).asReversed()
        val message = if (items.isEmpty()) "Hələ tarixçə yoxdur" else items.mapIndexed { i, item ->
            "${i + 1}. ${item.uci} · ${item.confidence}% · ${item.engineMs} ms"
        }.joinToString("\n")
        AlertDialog.Builder(this).setTitle("Son gedişlər").setMessage(message)
            .setPositiveButton("Bağla", null).show()
    }

    private fun showPerformance() {
        AlertDialog.Builder(this).setTitle("Performance Monitor")
            .setMessage("Tanınma: ${lastRecognitionMs} ms\nStockfish: ${lastEngineMs} ms\nConfidence: ${lastConfidence}%\nTarixçə: ${SessionStore.read(this).size}/100\nAnaliz: ${analysisTimeSeconds} s\nMühərrik: ${if (maxEngineStrength) "Maksimum" else targetElo}")
            .setPositiveButton("Bağla", null).show()
    }

    private fun shareExport(content: String, mime: String) {
        if (content.isBlank()) return say("İxrac üçün tarixçə boşdur")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_TEXT, content)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(Intent.createChooser(send, "ChessHelper ixracı").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        val previousPanelVisibility = panel?.visibility
        val previousBubbleVisibility = bubble?.visibility
        panel?.visibility = View.INVISIBLE
        bubble?.visibility = View.INVISIBLE
        try {
            // Wait for one fresh frame after hiding the controls. Do not wait for a "quiet"
            // 300ms period: screen capture is continuous, so that condition never occurs.
            val start = SystemClock.uptimeMillis()
            delay(350)
            var waited = 0
            while (lastFrameAt < start && waited < 1000) {
                delay(25)
                waited += 25
            }
            return synchronized(frameLock) {
                val frame = latestFrame
                if (frame == null || frame.isRecycled) null
                else frame.copy(Bitmap.Config.ARGB_8888, false)
            }
        } finally {
            panel?.visibility = previousPanelVisibility ?: View.VISIBLE
            bubble?.visibility = previousBubbleVisibility ?: View.VISIBLE
        }
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
        getSharedPreferences("chess", MODE_PRIVATE).edit()
            .putString("tpl2", r.templates.serialize())
            .putBoolean("white_bottom", r.whiteBottom)
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

    private class Snap(val board: BoardRect, val reading: Reading, val recognitionMs: Long)

    /** Ekranı tutur, taxtanı tapır və oxuyur. Oyunun əvvəlindəyiksə şablonlar avtomatik öyrənilir. */
    private suspend fun snapshot(): Snap? {
        val recognitionStarted = SystemClock.elapsedRealtime()
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

        // Learn templates automatically only once; do not overwrite them on every early move.
        if (templates == null) {
            val fresh = withContext(Dispatchers.Default) { BoardReader.calibrate(scan) }
            if (fresh != null) applyCalibration(fresh)
        }

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

        // Castling rights are known only when this session observed the initial position.
        // Thereafter they can only be removed when the original king/rook leaves its home square.
        val previous = previousPlacement
        if (BoardReader.isInitialPosition(reading.placement)) {
            castling = "KQkq"
            castlingKnown = true
        } else if (previous != null && castlingKnown) {
            castling = BoardReader.updateCastling(castling, previous, reading.placement)
        } else if (previous == null) {
            castling = "-"
            castlingKnown = false
        }
        previousPlacement = reading.placement
        lastPieces = reading.placement.count { it.isLetter() }
        lastRecognitionMs = SystemClock.elapsedRealtime() - recognitionStarted
        lastConfidence = (((64 - reading.weak.size).coerceAtLeast(0) / 64f) * 100f).toInt()
        return Snap(board, reading, lastRecognitionMs)
    }

    /** Oxunmuş taxtanı ekranın üstündə göstərir ki, tanımanın düzgünlüyü yoxlanılsın. */
    private suspend fun inspect() {
        say("Yoxlayıram…")
        val snap = snapshot() ?: return
        arrow?.showBoard(snap.board, snap.reading.screen, snap.reading.weak)
        val n = snap.reading.weak.size
        say(if (n == 0) "Hamısı oxundu" else "$n xana şübhəlidir (qırmızı çərçivə)")
    }

    private suspend fun analyze() = analyzeOnce()

    private suspend fun analyzeOnce() {
        say("Oxuyuram…")
        val eng = engine ?: return say("Motor hazır deyil")
        val snap = snapshot() ?: return
        val board = snap.board
        val reading = snap.reading
        val castField = if (castlingKnown) castling else "-"
        val stm = sideOverride ?: reading.sideToMove
        if (stm == null) {
            say("Növbə müəyyən edilmədi. Son gediş işarəsini yoxla və ya Növbə düyməsindən Ağ/Qara seç.")
            return
        }
        val fen = "${reading.placement} $stm $castField ${reading.enPassant} 0 1"
        val positionKey = "${reading.placement}|$stm|$castField|${reading.enPassant}"

        if (autoRunning) {
            if (candidatePositionKey == positionKey) stableFrameCount++ else {
                candidatePositionKey = positionKey
                stableFrameCount = 1
            }
            if (stableFrameCount < 2) {
                say("Mövqe ikinci kadrla təsdiqlənir…")
                return
            }
            if (lastAnalyzedPositionKey == positionKey) {
                say("Mövqe dəyişməyib · Tanınma: $lastConfidence%")
                return
            }
        }

        val engineStarted = SystemClock.elapsedRealtime()
        val move = withContext(Dispatchers.IO) {
            try {
                eng.configureStrength(targetElo, maxEngineStrength)
                eng.best(fen, analysisTimeSeconds * 1000)
            } catch (e: Exception) {
                null
            }
        } ?: return say("Gediş tapılmadı")

        lastEngineMs = SystemClock.elapsedRealtime() - engineStarted
        lastAnalyzedPositionKey = positionKey
        SessionStore.append(this, MoveRecord(fen, move, System.currentTimeMillis(), lastConfidence, snap.recognitionMs, lastEngineMs))
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
        destroyed = true
        autoRunning = false
        scope.cancel()
        try {
            panel?.let { wm.removeView(it) }
            bubble?.let { wm.removeView(it) }
            arrow?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        display?.release()
        reader?.close()
        frameThread?.quitSafely()
        frameThread = null
        synchronized(frameLock) {
            latestFrame?.recycle()
            latestFrame = null
        }
        projection?.stop()
        engine?.close()
        engine = null
        super.onDestroy()
    }
}
