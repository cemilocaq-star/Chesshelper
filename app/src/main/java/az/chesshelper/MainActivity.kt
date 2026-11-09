package az.chesshelper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val reqCapture = 1
    private val reqNotif = 2
    private var askedNotif = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Chess Helper\n\n" +
                "1) Başlat düyməsinə bas və ekran/overlay icazələrini ver.\n" +
                "2) Şahmat taxtası ekranda tam görünsün. Yeni oyunun əvvəlində üzən paneldə Avtomatik başlat seçərək fiqur şablonlarını öyrət.\n" +
                "3) Sonrakı mövqe dəyişiklikləri avtomatik oxunur; Dayandır düyməsi prosesi dayandırır. Yeni matça keçəndə Yeni oyun düyməsini bas.\n" +
                "4) Növbə avtomatik bilinmirsə Növbə düyməsindən Ağ və ya Qara seç. Bu seçim yalnız növbəti uğurlu analiz üçün keçərlidir. Yoxla düyməsi şübhəli xanaları göstərir.\n" +
                "5) Analiz nəticələri tarixçədə saxlanır; PGN/UCI ixracı hər FEN mövqeyi üçün ayrıca mühərrik tövsiyəsidir, real oynanmış oyun tarixçəsi deyil.\n\n" +
                "Yalnız şəxsi təhlil, bot oyunları və bütün iştirakçıların razı olduğu oyunlar üçün istifadə et. Platforma və turnir qaydalarına əməl et."
            textSize = 16f
        })
        root.addView(Button(this).apply {
            text = "Parametrlər"
            setOnClickListener { showSettings() }
        })
        root.addView(Button(this).apply {
            text = "Başlat"
            setOnClickListener { start() }
        })
        setContentView(root)
    }

    private fun showSettings() {
        val prefs = getSharedPreferences("chess", MODE_PRIVATE)
        val dp = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }
        val summary = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        fun refreshSummary() {
            val max = prefs.getBoolean("max_engine_strength", false)
            val elo = if (max) "Maksimum" else "${prefs.getInt("target_elo", 2000)} ELO"
            val mode = when (prefs.getString("thinking_mode", "balanced")) {
                "fast" -> "Sürətli"
                "deep" -> "Dərin"
                "custom" -> "Xüsusi"
                else -> "Balanslı"
            }
            val seconds = prefs.getInt("analysis_time_seconds", 3)
            val theme = when (prefs.getString("theme", "dark")) {
                "midnight" -> "Midnight Blue"
                "amoled" -> "AMOLED"
                else -> "Dark"
            }
            summary.text = "Cari konfiqurasiya\nMühərrik: $elo\nDüşünmə rejimi: $mode (${seconds}s)\nMövzu: $theme"
        }
        fun addSettingButton(label: String, action: () -> Unit) {
            content.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action(); refreshSummary() }
            })
        }
        content.addView(summary)
        addSettingButton("Mühərrik ELO-sunu seç") {
            val values = intArrayOf(1320, 1500, 1800, 2000, 2200, 2500, 2800, 3000)
            val labels = values.map { "$it ELO" }.toTypedArray() + "Maksimum güc" + "Xüsusi ELO daxil et"
            AlertDialog.Builder(this)
                .setTitle("Mühərrik gücü")
                .setItems(labels) { _, selected ->
                    when {
                        selected < values.size -> prefs.edit()
                            .putInt("target_elo", values[selected])
                            .putBoolean("max_engine_strength", false)
                            .apply()
                        selected == values.size -> prefs.edit()
                            .putBoolean("max_engine_strength", true)
                            .apply()
                        else -> showNumberDialog(
                            title = "Xüsusi ELO",
                            hint = "1320–3000",
                            initial = prefs.getInt("target_elo", 2000).toString(),
                            min = 1320,
                            max = 3000
                        ) { value ->
                            prefs.edit().putInt("target_elo", value)
                                .putBoolean("max_engine_strength", false).apply()
                            refreshSummary()
                        }
                    }
                    refreshSummary()
                }.show()
        }
        addSettingButton("Düşünmə rejimi") {
            val modes = arrayOf("Sürətli · 1 saniyə", "Balanslı · 3 saniyə", "Dərin · 10 saniyə", "Xüsusi")
            AlertDialog.Builder(this)
                .setTitle("Analiz düşünmə rejimi")
                .setItems(modes) { _, selected ->
                    when (selected) {
                        0 -> saveThinkingMode("fast", 1)
                        1 -> saveThinkingMode("balanced", 3)
                        2 -> saveThinkingMode("deep", 10)
                        3 -> showCustomThinkingDialog(prefs.getInt("analysis_time_seconds", 3)) { refreshSummary() }
                    }
                    refreshSummary()
                }.show()
        }
        addSettingButton("Xüsusi rejimi tənzimlə") {
            showCustomThinkingDialog(prefs.getInt("analysis_time_seconds", 3)) { refreshSummary() }
        }
        addSettingButton("Mövzu") {
            val themes = arrayOf("Dark", "Midnight Blue", "AMOLED")
            val keys = arrayOf("dark", "midnight", "amoled")
            AlertDialog.Builder(this)
                .setTitle("Mövzu")
                .setItems(themes) { _, selected ->
                    prefs.edit().putString("theme", keys[selected]).apply()
                    refreshSummary()
                }.show()
        }
        refreshSummary()
        AlertDialog.Builder(this)
            .setTitle("Chess Helper · Parametrlər")
            .setView(content)
            .setPositiveButton("Bağla", null)
            .show()
    }

    private fun saveThinkingMode(mode: String, seconds: Int) {
        getSharedPreferences("chess", MODE_PRIVATE).edit()
            .putString("thinking_mode", mode)
            .putInt("analysis_time_seconds", seconds.coerceIn(1, 30))
            .apply()
    }

    private fun showCustomThinkingDialog(initialSeconds: Int, onSaved: () -> Unit = {}) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "1–30 saniyə"
            setText(initialSeconds.coerceIn(1, 30).toString())
            setSelection(text.length)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(this@MainActivity).apply {
                text = "Stockfish-in hər mövqe üçün düşünmə vaxtını seç (1–30 saniyə)."
                textSize = 14f
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Xüsusi düşünmə rejimi")
            .setView(container)
            .setNegativeButton("Ləğv et", null)
            .setPositiveButton("Yadda saxla") { _, _ ->
                val seconds = input.text.toString().toIntOrNull()
                if (seconds == null || seconds !in 1..30) {
                    android.widget.Toast.makeText(this, "1–30 saniyə arası dəyər daxil et", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    saveThinkingMode("custom", seconds)
                    onSaved()
                    android.widget.Toast.makeText(this, "Xüsusi rejim yadda saxlanıldı: ${seconds}s", android.widget.Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun showNumberDialog(
        title: String,
        hint: String,
        initial: String,
        min: Int,
        max: Int,
        onSaved: (Int) -> Unit
    ) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            this.hint = hint
            setText(initial)
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrapper = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrapper)
            .setNegativeButton("Ləğv et", null)
            .setPositiveButton("Yadda saxla") { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value == null || value !in min..max) {
                    android.widget.Toast.makeText(this, "$min–$max arası dəyər daxil et", android.widget.Toast.LENGTH_LONG).show()
                } else onSaved(value)
            }.show()
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotif = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == reqNotif) start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            try {
                startForegroundService(i)
            } catch (e: Exception) {
                android.util.Log.e("ChessHelper", "Could not start overlay service", e)
                android.widget.Toast.makeText(
                    this,
                    "Xidmət başladılmadı: ${e.localizedMessage ?: e.javaClass.simpleName}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
