package az.chesshelper

import android.Manifest
import android.app.Activity
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
                "3) Sonrakı mövqe dəyişiklikləri avtomatik oxunur; Dayandır düyməsi prosesi dayandırır.\n" +
                "4) Növbə avtomatik bilinmirsə Növbə düyməsindən Ağ və ya Qara seç. Yoxla düyməsi şübhəli xanaları göstərir.\n" +
                "5) Analiz nəticələri tarixçədə saxlanır; PGN/UCI ixracı hər FEN mövqeyi üçün ayrıca mühərrik tövsiyəsidir, real oynanmış oyun tarixçəsi deyil.\n\n" +
                "Yalnız şəxsi təhlil, bot oyunları və bütün iştirakçıların razı olduğu oyunlar üçün istifadə et. Platforma və turnir qaydalarına əməl et."
            textSize = 16f
        })
        root.addView(Button(this).apply {
            text = "Başlat"
            setOnClickListener { start() }
        })
        setContentView(root)
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
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
