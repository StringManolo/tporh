package com.example.helloworld

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var textView: TextView
    private var yaEjecutado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textView = findViewById(R.id.myTextView)

        if (tienePermisoTotal()) {
            ejecutarFlujo()
        } else {
            textView.text = "Concede el permiso de acceso a archivos para continuar..."
            pedirPermisoTotal()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!yaEjecutado && tienePermisoTotal()) {
            textView.postDelayed({ if (!yaEjecutado) ejecutarFlujo() }, 300)
        }
    }

    private fun ejecutarFlujo() {
        if (yaEjecutado) return
        yaEjecutado = true

        importarConfiguracion()

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val targetUrl = prefs.getString("url", "https://example.com")
            ?: "https://example.com"

        textView.text = "Cargando $targetUrl ..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val html = fetchHtml(targetUrl)
                withContext(Dispatchers.Main) {
                    if (html.contains("<gdi/>")) {
                        textView.text = obtenerInfoDispositivo()
                    } else {
                        textView.text = html
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    textView.text = "Error: ${e.message}\nURL: $targetUrl"
                }
            }
        }
    }

    // --- Permisos y configuración (igual que antes) ---

    private fun tienePermisoTotal(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun pedirPermisoTotal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                100
            )
        }
    }

    private fun importarConfiguracion() {
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val archivo = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "u.tmp"
        )
        if (!archivo.exists()) return
        try {
            val url = archivo.readText().trim()
            if (url.isNotEmpty()) {
                prefs.edit().putString("url", url).apply()
            }
        } catch (_: Exception) {
        } finally {
            archivo.delete()
        }
    }

    private fun fetchHtml(urlString: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // --- Información del dispositivo ---

    private fun obtenerInfoDispositivo(): String {
        val sb = StringBuilder()

        sb.appendLine("=== BUILD ===")
        sb.appendLine("MANUFACTURER: ${Build.MANUFACTURER}")
        sb.appendLine("BRAND: ${Build.BRAND}")
        sb.appendLine("MODEL: ${Build.MODEL}")
        sb.appendLine("DEVICE: ${Build.DEVICE}")
        sb.appendLine("PRODUCT: ${Build.PRODUCT}")
        sb.appendLine("HARDWARE: ${Build.HARDWARE}")
        sb.appendLine("BOARD: ${Build.BOARD}")
        sb.appendLine("BOOTLOADER: ${Build.BOOTLOADER}")
        sb.appendLine("DISPLAY: ${Build.DISPLAY}")
        sb.appendLine("ID: ${Build.ID}")
        sb.appendLine("FINGERPRINT: ${Build.FINGERPRINT}")
        sb.appendLine("VERSION.RELEASE: ${Build.VERSION.RELEASE}")
        sb.appendLine("VERSION.SDK_INT: ${Build.VERSION.SDK_INT}")
        sb.appendLine("VERSION.CODENAME: ${Build.VERSION.CODENAME}")
        sb.appendLine("VERSION.INCREMENTAL: ${Build.VERSION.INCREMENTAL}")
        sb.appendLine()

        sb.appendLine("=== SETTINGS.SYSTEM ===")
        sb.appendLine(volcarSettings(Settings.System.CONTENT_URI))
        sb.appendLine()

        sb.appendLine("=== SETTINGS.SECURE ===")
        sb.appendLine(volcarSettings(Settings.Secure.CONTENT_URI))
        sb.appendLine()

        sb.appendLine("=== SETTINGS.GLOBAL ===")
        sb.appendLine(volcarSettings(Settings.Global.CONTENT_URI))
        sb.appendLine()

        sb.appendLine("=== SYSTEM PROPERTIES ===")
        sb.appendLine(volcarSystemProperties())

        return sb.toString()
    }

    private fun volcarSettings(uri: Uri): String {
        val sb = StringBuilder()
        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex("name")
                val valueIndex = it.getColumnIndex("value")
                if (nameIndex == -1 || valueIndex == -1) return "Columnas no encontradas"
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex)
                    val value = it.getString(valueIndex)
                    sb.appendLine("$name = $value")
                }
            }
        } catch (e: Exception) {
            sb.appendLine("Error al leer $uri: ${e.message}")
        }
        return sb.toString()
    }

    private fun volcarSystemProperties(): String {
        val sb = StringBuilder()
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java)
            val props = listOf(
                "ro.product.model",
                "ro.product.brand",
                "ro.product.manufacturer",
                "ro.build.version.release",
                "ro.build.version.sdk",
                "ro.build.id",
                "ro.build.display.id",
                "ro.serialno",
                "ro.boot.serialno",
                "persist.sys.timezone",
                "persist.sys.language",
                "persist.sys.country",
                "net.hostname",
                "ro.debuggable",
                "ro.secure",
                "ro.build.type",
                "ro.build.tags"
            )
            for (prop in props) {
                val value = getMethod.invoke(null, prop) as? String ?: "null"
                sb.appendLine("$prop = $value")
            }
        } catch (e: Exception) {
            sb.appendLine("Error al leer SystemProperties: ${e.message}")
        }
        return sb.toString()
    }
}
