package com.juya.vitrine

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var pendingShared: Uri? = null
    private var pageReady = false

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    // 사진첩에서 고르기 (안드로이드 사진 선택기 — 권한 필요 없음)
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> deliver(uri) }

    // 카메라로 찍기
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        deliver(if (ok) cameraUri else null)
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#FAF7F4")
        web = WebView(this)
        web.setBackgroundColor(bg)
        val root = FrameLayout(this)
        root.setBackgroundColor(bg)
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // 상태바·내비게이션바·키보드에 화면이 가리지 않게
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            setSupportMultipleWindows(false)
            textZoom = 100
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: ActivityNotFoundException) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingShared?.let { sendSharedImage(it); pendingShared = null }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                askSource()
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__appBack ? window.__appBack() : false") { r ->
                    if (r != "true") finish()
                }
            }
        })

        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        handleShareIntent(intent)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    // 갤러리에서 '공유 → Vitrine AI'로 보낸 옷 사진
    private fun handleShareIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        uri ?: return
        if (pageReady) sendSharedImage(uri) else pendingShared = uri
    }

    private fun sendSharedImage(uri: Uri) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@Thread
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                runOnUiThread {
                    web.evaluateJavascript("window.__receiveImage && window.__receiveImage('data:$mime;base64,$b64')", null)
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun askSource() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("📷  Galeria / 사진첩", "📸  Câmera / 카메라")) { _, which ->
                if (which == 0) {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else {
                    val dir = File(cacheDir, "camera").apply { mkdirs() }
                    val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
                    cameraUri = FileProvider.getUriForFile(this, "$packageName.files", f)
                    try { takePhoto.launch(cameraUri!!) } catch (_: ActivityNotFoundException) {
                        toast("Câmera não encontrada"); deliver(null)
                    }
                }
            }
            .setOnCancelListener { deliver(null) }
            .show()
    }

    private fun deliver(uri: Uri?) {
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun shareIntent(name: String, b64: String, mime: String): Intent {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** 웹 화면(index.html)에서 부르는 기능들 */
    inner class Bridge {

        /** 사진첩 'Vitrine AI' 앨범에 저장. 결과: "ok:위치" 또는 "err:이유" */
        @JavascriptInterface
        fun saveFile(name: String, b64: String, mime: String, sub: String): String {
            return try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val rel = Environment.DIRECTORY_PICTURES + "/Vitrine AI" + (if (sub.isNotBlank()) "/" + sub.replace("/", "_") else "")
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
                        ?: return "err:Não foi possível salvar"
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, cv, null, null)
                } else {
                    val dir = File(Environment.getExternalStorageDirectory(), rel).apply { mkdirs() }
                    val f = File(dir, name)
                    f.writeBytes(bytes)
                    MediaScannerConnection.scanFile(this@MainActivity, arrayOf(f.absolutePath), arrayOf(mime), null)
                }
                "ok:Vitrine AI"
            } catch (e: Exception) {
                "err:" + (e.message ?: "erro")
            }
        }

        /** 공유 창 */
        @JavascriptInterface
        fun shareFile(name: String, b64: String, mime: String): String = try {
            val send = shareIntent(name, b64, mime)
            runOnUiThread { startActivity(Intent.createChooser(send, "Vitrine AI")) }
            "ok"
        } catch (e: Exception) { "err:" + (e.message ?: "erro") }

        /** 특정 앱(왓츠앱 등)으로 바로 보내기. 없으면 공유 창 */
        @JavascriptInterface
        fun shareTo(name: String, b64: String, mime: String, pkg: String): String = try {
            val send = shareIntent(name, b64, mime)
            val target = listOf(pkg, "com.whatsapp.w4b").firstOrNull {
                try { packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
            }
            runOnUiThread {
                if (target != null) {
                    send.setPackage(target)
                    try { startActivity(send) } catch (_: ActivityNotFoundException) { send.setPackage(null); startActivity(Intent.createChooser(send, "Vitrine AI")) }
                } else startActivity(Intent.createChooser(send, "Vitrine AI"))
            }
            "ok"
        } catch (e: Exception) { "err:" + (e.message ?: "erro") }

        /** AI 서버 정보 (테스트 빌드는 gemini 키를 직접 가짐) */
        @JavascriptInterface
        fun aiConfig(): String {
            val prefs = getSharedPreferences("app", MODE_PRIVATE)
            var dev = prefs.getString("device", null)
            if (dev == null) { dev = java.util.UUID.randomUUID().toString(); prefs.edit().putString("device", dev).apply() }
            return JSONObject().put("url", BuildConfig.AI_URL).put("key", BuildConfig.APP_KEY)
                .put("gemini", BuildConfig.GEMINI_KEY).put("device", dev).toString()
        }

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_CODE.toString()
    }
}
