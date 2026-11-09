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
        val items = arrayOf("Mühərrik ELO-su", "Analiz müddəti", "Mövzu")
        AlertDialog.Builder(this)
            .setTitle("Chess Helper parametrləri")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val values = intArrayOf(1320, 1500, 1800, 2000, 2200, 2500, 2800, 3000)
                        val labels = values.map { "$it ELO" }.toTypedArray() + "Maksimum güc"
                        AlertDialog.Builder(this)
                            .setTitle("Mühərrik gücü")
                            .setItems(labels) { _, selected ->
                                val edit = prefs.edit()
                                if (selected == values.size) {
                                    edit.putBoolean("max_engine_strength", true)
                                } else {
                                    edit.putInt("target_elo", values[selected])
                                        .putBoolean("max_engine_strength", false)
                                }
                                edit.apply()
                            }.show()
                    }
                    1 -> {
                        val values = intArrayOf(1, 3, 5, 10, 15, 20, 30)
                        AlertDialog.Builder(this)
                            .setTitle("Analiz müddəti")
                            .setItems(values.map { "$it saniyə" }.toTypedArray()) { _, selected ->
                                prefs.edit().putInt("analysis_time_seconds", values[selected]).apply()
                            }.show()
                    }
                    2 -> {
                        val themes = arrayOf("Dark", "Midnight Blue", "AMOLED")
                        val keys = arrayOf("dark", "midnight", "amoled")
                        AlertDialog.Builder(this)
                            .setTitle("Mövzu")
                            .setItems(themes) { _, selected ->
                                prefs.edit().putString("theme", keys[selected]).apply()
                            }.show()
                    }
                }
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
