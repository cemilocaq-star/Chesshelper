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
import android.content.res.ColorStateList
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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var latestImage: Image? = null
    private var frameThread: HandlerThread? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var engine: Engine? = null
    @Volatile private var destroyed = false

    private var panel: View? = null
    private var panelLayoutParams: WindowManager.LayoutParams? = null
    private var panelExpandedHeight = 0
    private var bubble: TextView? = null
    private var sideButtonControl: Button? = null
    private var arrow: ArrowView? = null
    private var status: TextView? = null
    private var activeDialog: AlertDialog? = null

    private var templates: Templates? = null
    private var whiteBottom = true
    private var orientationKnown = false
    private var castling = "-"
    private var castlingKnown = false
    private var previousPlacement: String? = null
    private var sideOverride: Char? = null
    private var lastAnalyzedPositionKey: String? = null
    private var candidatePositionKey: String? = null
    private var lastBoard: BoardRect? = null
    private var busy = false
    private var analysisTimeSeconds = 3
    private var targetElo = 2000
    private var maxEngineStrength = false
    @Volatile private var autoRunning = false
    private var autoJob: Job? = null
    private var autoGeneration = 0
    private var stableFrameCount = 0
    private var lastRecognitionMs = 0L
    private var lastEngineMs = 0L
    private var lastPieceCoverage = 0
    private val recognitionSamples = ArrayList<Long>()
    private val engineSamples = ArrayList<Long>()
    private var recognitionFailures = 0
    private var engineFailures = 0
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            "analysis_time_ms", "analysis_time_seconds" -> analysisTimeSeconds =
                (prefs.getInt("analysis_time_ms", prefs.getInt("analysis_time_seconds", 3) * 1000))
                    .coerceIn(500, 30_000)
            "target_elo" -> targetElo =
                prefs.getInt("target_elo", 2000).coerceIn(1320, 3000)
            "max_engine_strength" -> maxEngineStrength =
                prefs.getBoolean("max_engine_strength", false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (intent == null || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        startAsForeground()
        try {
            setup(intent.getIntExtra("code", 0), data)
        } catch (e: Exception) {
            android.util.Log.e("ChessHelper", "Overlay service setup failed", e)
            android.widget.Toast.makeText(
                this,
                "Chess Helper başlaya bilmədi: ${e.localizedMessage ?: e.javaClass.simpleName}",
                android.widget.Toast.LENGTH_LONG
            ).show()
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
            val img = try {
                rd.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            synchronized(frameLock) {
                if (destroyed) {
                    img.close()
                } else {
                    // Keep only the newest hardware frame. capture() copies it to a Bitmap
                    // while holding the same lock, so this Image cannot be closed mid-read.
                    val old = latestImage
                    latestImage = img
                    lastFrameAt = SystemClock.uptimeMillis()
                    old?.close()
                }
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
        val prefs = getSharedPreferences("chess", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(settingsListener)
        whiteBottom = prefs.getBoolean("white_bottom", true)
        orientationKnown = prefs.contains("white_bottom")
        // Manual side selection is deliberately session-local and one-shot.
        sideOverride = null

        val settingsPrefs = getSharedPreferences("chess", MODE_PRIVATE)
        analysisTimeSeconds = settingsPrefs.getInt(
            "analysis_time_ms", settingsPrefs.getInt("analysis_time_seconds", 3) * 1000
        ).coerceIn(500, 30_000)
        targetElo = getSharedPreferences("chess", MODE_PRIVATE).getInt("target_elo", 2000).coerceIn(1320, 3000)
        maxEngineStrength = getSharedPreferences("chess", MODE_PRIVATE).getBoolean("max_engine_strength", false)
        buildUi()
        say(if (templates != null) "≡ Hazırdır" else "≡ Yeni oyunun əvvəlində Başlat bas")

        scope.launch(Dispatchers.IO) {
            try {
                val created = Engine(this@OverlayService)
                var transferred = false
                try {
                    withContext(Dispatchers.Main) {
                        if (!destroyed) {
                            engine = created
                            transferred = true
                        }
                    }
                } finally {
                    if (!transferred) created.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
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
        btn("Dayandır") { stopAutomaticAnalysis() }
        btn("Yeni oyun") {
            if (busy) say("Cari analiz bitdikdən sonra yeni oyun rejimini seç")
            else resetGameSession()
        }
        val sideButton = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            setSideButtonLabel(this)
            setOnClickListener {
                sideOverride = when (sideOverride) { null -> 'w'; 'w' -> 'b'; else -> null }
                setSideButtonLabel(this)
            }
        }
        sideButtonControl = sideButton
        p.addView(sideButton)
        btn("Yoxla") { if (!busy) scope.launch { guarded { inspect() } } }
        btn("Şablonu yenilə") { if (!busy) scope.launch { guarded { calibrate() } } }
        btn("Daha çox") {
            val options = arrayOf("Analiz tarixçəsi", "PGN tövsiyə ixracı", "UCI ixracı", "Performans", "Bağla")
            val dialog = AlertDialog.Builder(this)
                .setTitle("Əlavə funksiyalar")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> showHistory()
                        1 -> shareExport(SessionStore.toPgn(this), "application/x-chess-pgn")
                        2 -> shareExport(SessionStore.toUci(this), "text/plain")
                        3 -> showPerformance()
                        4 -> stopSelf()
                    }
                }.create()
            showServiceDialog(dialog)
        }


        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(p, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        applyPanelTheme(p)

        panelExpandedHeight = (resources.displayMetrics.heightPixels * 0.46f).toInt()
        val lp = WindowManager.LayoutParams(
            (220 * dp).toInt(),
            panelExpandedHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * dp).toInt()
            y = (90 * dp).toInt()
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
                    wm.updateViewLayout(scroll, lp)
                }
            }
            true
        }

        panel = scroll
        panelLayoutParams = lp
        wm.addView(scroll, lp)

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
                    val maxX = (resources.displayMetrics.widthPixels - (48 * dp).toInt()).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - (48 * dp).toInt()).coerceAtLeast(0)
                    blp.x = (startX + dx).coerceIn(0, maxX)
                    blp.y = (startY + dy).coerceIn(0, maxY)
                    wm.updateViewLayout(view, blp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val params = panelLayoutParams
                        if (params != null) {
                            val showing = params.height > 0
                            params.height = if (showing) 0 else panelExpandedHeight
                            scroll.visibility = if (showing) View.GONE else View.VISIBLE
                            wm.updateViewLayout(scroll, params)
                            bubbleView.alpha = if (showing) 1f else 0.75f
                        }
                    }
                    true
                }
                else -> true
            }
        }
        wm.addView(bubbleView, blp)
    }

    private fun showServiceDialog(dialog: AlertDialog) {
        if (autoRunning) stopAutomaticAnalysis()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.show()
    }

    private fun showAnalysisTimeDialog(button: Button) {
        val choices = arrayOf("Fast — 1 saniyə", "Normal — 3 saniyə", "Deep — 10 saniyə", "Custom — 1–30 saniyə")
        val dialog = AlertDialog.Builder(this)
            .setTitle("Təhlil müddəti")
            .setItems(choices) { parentDialog, which ->
                when (which) {
                    0 -> setAnalysisTime(1, button)
                    1 -> setAnalysisTime(3, button)
                    2 -> setAnalysisTime(10, button)
                    3 -> {
                        parentDialog.dismiss()
                        val input = EditText(this).apply {
                            inputType = InputType.TYPE_CLASS_NUMBER
                            hint = "1–30 saniyə"
                            setText(analysisTimeSeconds.toString())
                            selectAll()
                        }
                        val customDialog = AlertDialog.Builder(this)
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
                            .create()
                        showServiceDialog(customDialog)
                    }
                }
            }
            .create()
        showServiceDialog(dialog)
    }

    private fun setAnalysisTime(seconds: Int, button: Button) {
        analysisTimeSeconds = seconds.coerceIn(500, 30_000)
        getSharedPreferences("chess", MODE_PRIVATE).edit()
            .putInt("analysis_time_ms", analysisTimeSeconds)
            .putInt("analysis_time_seconds", (analysisTimeSeconds / 1000f).toInt().coerceAtLeast(1))
            .apply()
        button.text = "Təhlil vaxtı: ${analysisTimeSeconds / 1000f}s"
        say("Təhlil müddəti: ${analysisTimeSeconds / 1000f} saniyə")
    }

    private fun say(s: String) {
        status?.text = s
    }

    private fun addMetric(samples: ArrayList<Long>, value: Long) {
        samples.add(value)
        if (samples.size > 100) samples.removeAt(0)
    }

    private fun averageMetric(samples: List<Long>): Long =
        if (samples.isEmpty()) 0L else samples.average().toLong()

    private fun setSideButtonLabel(button: Button) {
        button.text = "Növbə: ${when (sideOverride) { 'w' -> "Ağ"; 'b' -> "Qara"; else -> "Avtomatik" }}"
    }

    private fun stopAutomaticAnalysis() {
        autoRunning = false
        autoGeneration++
        engine?.stopSearch()
        autoJob?.cancel()
        autoJob = null
        say("Avtomatik analiz dayandırıldı")
    }

    private fun resetGameSession() {
        stopAutomaticAnalysis()
        previousPlacement = null
        castling = "-"
        castlingKnown = false
        orientationKnown = false
        sideOverride = null
        getSharedPreferences("chess", MODE_PRIVATE).edit()
            .remove("white_bottom")
            .apply()
        sideButtonControl?.let { setSideButtonLabel(it) }
        lastBoard = null
        lastAnalyzedPositionKey = null
        candidatePositionKey = null
        stableFrameCount = 0
        say("Yeni oyun rejimi hazırdır. Taxta başlanğıc mövqeyində olanda Avtomatik başlat seç.")
    }

    private fun startAutomaticAnalysis() {
        if (autoJob?.isActive == true) return
        autoGeneration++
        val myGeneration = autoGeneration
        autoRunning = true
        lastAnalyzedPositionKey = null
        candidatePositionKey = null
        stableFrameCount = 0
        say("Avtomatik tanıma başladıldı")
        autoJob = scope.launch {
            while (autoRunning && myGeneration == autoGeneration) {
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

    private fun applyPanelTheme(target: LinearLayout) {
        val dp = resources.displayMetrics.density
        val theme = getSharedPreferences("chess", MODE_PRIVATE).getString("theme", "dark")
        val color = when (theme) {
            "midnight" -> Color.rgb(10, 22, 48)
            "amoled" -> Color.BLACK
            else -> Color.rgb(20, 20, 20)
        }
        target.background = GradientDrawable().apply {
            setColor(Color.argb(235, Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = 12 * dp
        }
    }

    private fun cycleTheme(target: LinearLayout) {
        val prefs = getSharedPreferences("chess", MODE_PRIVATE)
        val themes = listOf("dark", "midnight", "amoled")
        val current = prefs.getString("theme", "dark") ?: "dark"
        val next = themes[(themes.indexOf(current).coerceAtLeast(0) + 1) % themes.size]
        prefs.edit().putString("theme", next).apply()
        applyPanelTheme(target)
        say("Mövzu: ${when (next) { "midnight" -> "Midnight Blue"; "amoled" -> "AMOLED"; else -> "Dark" }}")
    }

    private fun showHistory() {
        val items = SessionStore.read(this).takeLast(20).asReversed()
        val message = if (items.isEmpty()) "Hələ analiz tarixçəsi yoxdur" else items.mapIndexed { i, item ->
            "${i + 1}. ${item.uci} · güclü fiqur tanınması ${item.pieceCoverage}% · ${item.engineMs} ms"
        }.joinToString("\n")
        val dialog = AlertDialog.Builder(this).setTitle("Son analiz təklifləri").setMessage(message)
            .setPositiveButton("Bağla", null).create()
        showServiceDialog(dialog)
    }

    private fun showPerformance() {
        val dialog = AlertDialog.Builder(this).setTitle("Performance Monitor")
            .setMessage(
                "Son tanınma: ${lastRecognitionMs} ms\n" +
                    "Orta tanınma (son ${recognitionSamples.size}): ${averageMetric(recognitionSamples)} ms\n" +
                    "Son Stockfish: ${lastEngineMs} ms\n" +
                    "Orta Stockfish (son ${engineSamples.size}): ${averageMetric(engineSamples)} ms\n" +
                    "Güclü fiqur tanınması (heuristik): ${lastPieceCoverage}%\n" +
                    "Tanınma xətaları: $recognitionFailures · Mühərrik xətaları: $engineFailures\n" +
                    "Analiz tarixçəsi: ${SessionStore.read(this).size}/100\n" +
                    "Analiz müddəti: ${analysisTimeSeconds} s\n" +
                    "Mühərrik: ${if (maxEngineStrength) "Maksimum" else targetElo}\n" +
                    "Düşünmə rejimi: ${when (getSharedPreferences("chess", MODE_PRIVATE).getString("thinking_mode", "balanced")) { "fast" -> "Sürətli"; "deep" -> "Dərin"; "custom" -> "Xüsusi"; else -> "Balanslı" }}"
            )
            .setPositiveButton("Bağla", null).create()
        showServiceDialog(dialog)
    }

    private fun shareExport(content: String, mime: String) {
        if (content.isBlank()) return say("İxrac üçün tarixçə boşdur")
        if (autoRunning) stopAutomaticAnalysis()
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
        val dialog = activeDialog?.takeIf { it.isShowing }
        val dialogDecor = dialog?.window?.decorView
        val previousDialogVisibility = dialogDecor?.visibility
        val params = panelLayoutParams
        val previousPanelHeight = params?.height
        panel?.visibility = View.INVISIBLE
        if (params != null && params.height > 0) {
            params.height = 0
            panel?.let { wm.updateViewLayout(it, params) }
        }
        bubble?.visibility = View.INVISIBLE
        dialogDecor?.visibility = View.INVISIBLE
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
            if (lastFrameAt < start) return null
            return synchronized(frameLock) {
                val img = latestImage ?: return@synchronized null
                latestImage = null
                try {
                    val plane = img.planes[0]
                    val rowPixels = plane.rowStride / plane.pixelStride
                    val padded = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
                    try {
                        plane.buffer.rewind()
                        padded.copyPixelsFromBuffer(plane.buffer)
                        if (rowPixels == img.width) padded
                        else Bitmap.createBitmap(padded, 0, 0, img.width, img.height).also { padded.recycle() }
                    } catch (e: Exception) {
                        if (!padded.isRecycled) padded.recycle()
                        throw e
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    img.close()
                }
            }
        } finally {
            if (!destroyed) {
                try {
                    if (params != null && previousPanelHeight != null) {
                        params.height = previousPanelHeight
                        panel?.let { wm.updateViewLayout(it, params) }
                    }
                } catch (_: Exception) {
                    // The service may be closing while this capture is cancelled.
                }
                panel?.visibility = previousPanelVisibility ?: View.VISIBLE
                bubble?.visibility = previousBubbleVisibility ?: View.VISIBLE
                if (dialog === activeDialog && dialog?.isShowing == true && dialogDecor != null) {
                    dialogDecor.visibility = previousDialogVisibility ?: View.VISIBLE
                }
            }
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
        orientationKnown = true
        getSharedPreferences("chess", MODE_PRIVATE).edit()
            .putString("tpl2", r.templates.serialize())
            .putBoolean("white_bottom", r.whiteBottom)
            .apply()
    }

    private suspend fun calibrate() {
        say("Şablon yenilənir…")
        val bmp = capture() ?: return say("Ekran tutulmadı")
        val found = try {
            withContext(Dispatchers.Default) { BoardReader.findBoard(bmp) }
        } catch (e: Exception) {
            if (!bmp.isRecycled) bmp.recycle()
            throw e
        } ?: run {
            if (!bmp.isRecycled) bmp.recycle()
            return say("Taxta tapılmadı")
        }
        val scan = try {
            withContext(Dispatchers.Default) { BoardReader.scan(bmp, stable(found)) }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
        val r = withContext(Dispatchers.Default) { BoardReader.calibrate(scan) }
            ?: return say("Yalnız oyunun əvvəlində (ilk 1-2 gediş) yenilənir")
        applyCalibration(r)
        say(if (r.whiteBottom) "Hazır: ağ aşağıda" else "Hazır: qara aşağıda")
    }

    private class Snap(
        val board: BoardRect,
        val reading: Reading,
        val recognitionMs: Long,
        val frameWidth: Int,
        val frameHeight: Int
    )

    /** Ekranı tutur, taxtanı tapır və oxuyur. Oyunun əvvəlindəyiksə şablonlar avtomatik öyrənilir. */
    private suspend fun snapshot(): Snap? {
        val recognitionStarted = SystemClock.elapsedRealtime()
        val bmp = capture()
        if (bmp == null) {
            recognitionFailures++
            say("Ekran tutulmadı")
            return null
        }
        val frameWidth = bmp.width
        val frameHeight = bmp.height
        val found = try {
            withContext(Dispatchers.Default) { BoardReader.findBoard(bmp) }
        } catch (e: Exception) {
            if (!bmp.isRecycled) bmp.recycle()
            throw e
        }
        if (found == null) {
            if (!bmp.isRecycled) bmp.recycle()
            recognitionFailures++
            say("Taxta tapılmadı")
            return null
        }
        val board = stable(found)
        val scan = try {
            withContext(Dispatchers.Default) { BoardReader.scan(bmp, board) }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }

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
        val reading = withContext(Dispatchers.Default) {
            BoardReader.read(scan, t, whiteBottom, detectOrientation = !orientationKnown)
        }
        if (reading == null) {
            recognitionFailures++
            say("Taxta oxunmadı (fiqur dəsti dəyişibsə yeni oyunun əvvəlində şablonu yenilə)")
            return null
        }
        whiteBottom = reading.whiteBottom
        if (!orientationKnown) {
            orientationKnown = true
            getSharedPreferences("chess", MODE_PRIVATE).edit()
                .putBoolean("white_bottom", whiteBottom)
                .apply()
        }

        // Castling rights are known only when this session observed the initial position.
        // Thereafter they can only be removed when the original king/rook leaves its home square.
        val previous = previousPlacement
        if (previous == null) {
            if (BoardReader.isInitialPosition(reading.placement)) {
                castling = "KQkq"
                castlingKnown = true
            } else {
                castling = "-"
                castlingKnown = false
            }
        } else if (castlingKnown) {
            castling = BoardReader.updateCastling(castling, previous, reading.placement)
        }
        previousPlacement = reading.placement
        lastRecognitionMs = SystemClock.elapsedRealtime() - recognitionStarted
        addMetric(recognitionSamples, lastRecognitionMs)
        lastPieceCoverage = if (reading.occupiedCount == 0) 0 else (((reading.occupiedCount - reading.weak.size).coerceAtLeast(0) / reading.occupiedCount.toFloat()) * 100f).toInt()
        return Snap(board, reading, lastRecognitionMs, frameWidth, frameHeight)
    }

    /** Oxunmuş taxtanı ekranın üstündə göstərir ki, tanımanın düzgünlüyü yoxlanılsın. */
    private suspend fun inspect() {
        say("Yoxlayıram…")
        val snap = snapshot() ?: return
        arrow?.showBoard(
            snap.board, snap.reading.screen, snap.reading.weak,
            snap.frameWidth, snap.frameHeight
        )
        val n = snap.reading.weak.size
        say(
            "Taxta: x=${snap.board.x}, y=${snap.board.y}, " +
                "${snap.board.size}×${snap.board.size}px · " +
                "${snap.frameWidth}×${snap.frameHeight}px ekran · " +
                "${snap.reading.occupiedCount} fiqur · $n şübhəli xana"
        )
    }

    private suspend fun analyze() = analyzeOnce()

    private suspend fun analyzeOnce() {
        say("Oxuyuram…")
        val eng = engine ?: return say("Motor hazır deyil")
        // A previous stop request is scoped to the previous analysis. Clear it before
        // starting a new snapshot/engine cycle so a fresh Start works after Stop.
        eng.resetCancellation()
        val snap = snapshot() ?: return
        val board = snap.board
        val reading = snap.reading
        val castField = if (castlingKnown) castling else "-"
        val usedManualSide = sideOverride
        val stm = usedManualSide ?: reading.sideToMove
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
                say("Mövqe dəyişməyib · Tanınma: $lastPieceCoverage%")
                return
            }
        }

        // Snapshot UI settings before crossing to the IO dispatcher.
        val requestedElo = targetElo
        val useMaximumStrength = maxEngineStrength
        val requestedMoveTimeMs = analysisTimeSeconds
        val engineStarted = SystemClock.elapsedRealtime()
        val move = try {
            withContext(Dispatchers.IO) {
                eng.configureStrength(requestedElo, useMaximumStrength)
                eng.best(fen, requestedMoveTimeMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: run {
            engineFailures++
            return say("Mühərrik gediş qaytarmadı")
        }

        val san = ChessNotation.sanFor(fen, move)
        if (san == null) {
            engineFailures++
            return say("Mühərrikin qaytardığı gediş bu FEN üçün qanuni deyil; nəticə saxlanmadı")
        }
        lastEngineMs = SystemClock.elapsedRealtime() - engineStarted
        addMetric(engineSamples, lastEngineMs)
        lastAnalyzedPositionKey = positionKey
        if (usedManualSide != null) {
            sideOverride = null
            sideButtonControl?.let { setSideButtonLabel(it) }
        }
        SessionStore.append(this, MoveRecord(fen, move, System.currentTimeMillis(), lastPieceCoverage, snap.recognitionMs, lastEngineMs))
        val from = move.substring(0, 2)
        val to = move.substring(2, 4)
        val n = reading.weak.size
        say(if (n == 0) "$from → $to · $san" else "$from → $to · $san ($n xana şübhəli, Yoxla bas)")
        val (x1, y1) = BoardReader.center(board, from, whiteBottom)
        val (x2, y2) = BoardReader.center(board, to, whiteBottom)
        // piyada son sıraya çatırsa (məs. e7e8q) yeni fiqur göstərilir
        val piece = if (move.length >= 5) move[4] else BoardReader.pieceAt(fen, from) ?: 'p'
        arrow?.show(
            x1, y1, x2, y2, piece, board.size / 8f,
            snap.frameWidth, snap.frameHeight
        )
    }

    override fun onDestroy() {
        destroyed = true
        autoRunning = false
        autoGeneration++
        autoJob?.cancel()
        autoJob = null
        scope.cancel()
        getSharedPreferences("chess", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(settingsListener)
        try {
            panel?.let { wm.removeView(it) }
            bubble?.let { wm.removeView(it) }
            arrow?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        display?.release()
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        frameThread?.quitSafely()
        try { frameThread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        frameThread = null
        synchronized(frameLock) {
            latestImage?.close()
            latestImage = null
        }
        projection?.stop()
        engine?.close()
        engine = null
        super.onDestroy()
    }
}
