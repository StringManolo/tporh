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
import android.widget.Toast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textView = findViewById<TextView>(R.id.myTextView)

        // 1) Sin permiso total, lo pedimos y salimos
        if (!tienePermisoTotal()) {
            pedirPermisoTotal()
            return
        }

        // 2) Importar u.tmp si existe (silencioso, sin diálogos)
        importarConfiguracion()

        // 3) Obtener URL guardada
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val targetUrl = prefs.getString("url", "https://example.com")
            ?: "https://example.com"

        // 4) Petición HTTP en hilo secundario
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val html = fetchHtml(targetUrl)
                withContext(Dispatchers.Main) {
                    textView.text = html
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    textView.text = "Error: ${e.message}\nURL: $targetUrl"
                }
            }
        }
    }

    private fun tienePermisoTotal(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
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
        Toast.makeText(
            this,
            "Concede 'Acceso a todos los archivos' y vuelve a abrir la app",
            Toast.LENGTH_LONG
        ).show()
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
}
