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
    private var photoId: String? = null   // JS가 부른 사진 고르기 (휴대폰이 직접 읽어서 넘김)

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    // 사진첩에서 고르기 (안드로이드 사진 선택기 — 권한 필요 없음)
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> deliver(uri) }

    // 사진첩에서 여러 장 한꺼번에 고르기 (최대 10장)
    private var multiMax = 1
    private val pickMany = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> deliverMany(uris) }

    // 카메라로 찍기
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        deliver(if (ok) cameraUri else null)
    }

    // 말로 지시하기 (구글 음성 입력 창 — 마이크 권한 필요 없음)
    private var voiceId: String? = null
    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val id = voiceId; voiceId = null
        if (id == null) return@registerForActivityResult
        val text = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
        web.evaluateJavascript("window.__voice && window.__voice(${JSONObject.quote(id)}, ${JSONObject.quote(text)})", null)
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

        WorkService.channels(this)
        Premium.init(this)
        Premium.onChange { web.evaluateJavascript("window.__billingChanged && window.__billingChanged()", null) }
        handleShareIntent(intent)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onResume() {
        super.onResume()
        Premium.refresh()
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
                    if (photoId != null && multiMax > 1) pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    else pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
        val pid = photoId
        if (pid != null) {
            photoId = null
            if (uri == null) {
                if (multiMax > 1) { multiMax = 1; runOnUiThread { web.evaluateJavascript("window.__photos && window.__photos(${JSONObject.quote(pid)}, [], 'cancel')", null) } }
                else sendPhoto(pid, null, "cancel")
                return
            }
            Thread {
                var err = ""
                val b64 = try {
                    val u = toJpeg(uri)
                    if (u == null) { err = "decode"; null }
                    else contentResolver.openInputStream(u)?.use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
                } catch (t: Throwable) { err = t.javaClass.simpleName + ": " + (t.message ?: ""); null }
                if (multiMax > 1) {
                    multiMax = 1
                    val arr = org.json.JSONArray(); if (b64 != null) arr.put("data:image/jpeg;base64,$b64")
                    runOnUiThread { web.evaluateJavascript("window.__photos && window.__photos(${JSONObject.quote(pid)}, $arr, ${JSONObject.quote(err)})", null) }
                } else sendPhoto(pid, b64, err)
            }.start()
            return
        }
        val cb = fileCallback
        fileCallback = null
        if (cb == null) return
        if (uri == null) { cb.onReceiveValue(null); return }
        // 삼성 고효율(HEIF) 사진 등도 화면에서 읽을 수 있게 휴대폰 안에서 JPG로 바꿔서 넘김
        Thread {
            val out = try { toJpeg(uri) } catch (_: Throwable) { null }
            runOnUiThread { cb.onReceiveValue(arrayOf(out ?: uri)) }
        }.start()
    }

    private fun deliverMany(uris: List<Uri>) {
        val pid = photoId ?: return
        photoId = null
        val max = multiMax; multiMax = 1
        if (uris.isEmpty()) { sendPhoto(pid, null, "cancel"); return }
        Thread {
            val arr = org.json.JSONArray()
            var err = ""
            for (u in uris.take(max)) {
                try {
                    val j = toJpeg(u)
                    val b = j?.let { x -> contentResolver.openInputStream(x)?.use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) } }
                    if (b != null) arr.put("data:image/jpeg;base64,$b") else err = "decode"
                } catch (t: Throwable) { err = t.javaClass.simpleName }
            }
            runOnUiThread { web.evaluateJavascript("window.__photos && window.__photos(${JSONObject.quote(pid)}, $arr, ${JSONObject.quote(err)})", null) }
        }.start()
    }

    private fun sendPhoto(id: String, b64: String?, err: String) {
        val arg = if (b64 == null) "null" else "'data:image/jpeg;base64,$b64'"
        runOnUiThread { web.evaluateJavascript("window.__photo && window.__photo(${JSONObject.quote(id)}, $arg, ${JSONObject.quote(err)})", null) }
    }

    private fun toJpeg(uri: Uri): Uri? {
        val max = 1600
        val bmp: android.graphics.Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                val w = info.size.width; val h = info.size.height
                val sc = minOf(1f, max.toFloat() / maxOf(w, h))
                if (sc < 1f) dec.setTargetSize((w * sc).toInt().coerceAtLeast(1), (h * sc).toInt().coerceAtLeast(1))
                dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opt) }
            var sample = 1
            while (maxOf(opt.outWidth, opt.outHeight) / (sample * 2) >= max) sample *= 2
            val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o2) } ?: return null
        }
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "pick_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
        return FileProvider.getUriForFile(this, "$packageName.files", f)
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
                val isVideo = mime.startsWith("video")
                val rel = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Vitrine AI" + (if (sub.isNotBlank()) "/" + sub.replace("/", "_") else "")
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
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

        /** 사진 고르기 (사진첩/카메라). 결과는 window.__photo(id, dataURL 또는 null, 오류) */
        @JavascriptInterface
        fun pickPhoto(id: String) = pickPhotos(id, 1)

        /** 여러 장 고르기. 사진첩이면 한꺼번에 max장까지 → window.__photos(id, [dataURL...], 오류) */
        @JavascriptInterface
        fun pickPhotos(id: String, max: Int) {
            runOnUiThread {
                fileCallback?.onReceiveValue(null); fileCallback = null
                multiMax = max.coerceIn(1, 10)
                photoId = id
                askSource()
            }
        }

        /** 말로 입력. 결과는 window.__voice(id, 글자) — 실패/취소면 빈 글자 */
        @JavascriptInterface
        fun listen(id: String, lang: String) {
            runOnUiThread {
                val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, if (lang.startsWith("ko")) "말씀하세요" else "Fale agora")
                }
                voiceId = id
                try { voiceLauncher.launch(i) } catch (_: ActivityNotFoundException) {
                    voiceId = null
                    web.evaluateJavascript("window.__voice && window.__voice(${JSONObject.quote(id)}, '', 'none')", null)
                }
            }
        }

        /** 만들기 시작: 알림을 띄우고 앱에서 나가도 계속 돌게 함 */
        @JavascriptInterface
        fun workStart(text: String) {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                try {
                    val i = Intent(this@MainActivity, WorkService::class.java).putExtra("text", text)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun workUpdate(text: String) {
            try { androidx.core.app.NotificationManagerCompat.from(this@MainActivity).notify(WorkService.ID, WorkService.progress(this@MainActivity, text)) } catch (_: SecurityException) {}
        }

        @JavascriptInterface
        fun workStop() {
            try { stopService(Intent(this@MainActivity, WorkService::class.java)) } catch (_: Exception) {}
        }

        /** 완성 알림 */
        @JavascriptInterface
        fun notifyDone(title: String, text: String) {
            try { androidx.core.app.NotificationManagerCompat.from(this@MainActivity).notify(42, WorkService.done(this@MainActivity, title, text)) } catch (_: SecurityException) {}
        }

        /** 구독 상태 {ready, plan, token, prices} */
        @JavascriptInterface
        fun billing(): String = Premium.state()

        @JavascriptInterface
        fun buy(which: String): Boolean {
            var ok = false
            val latch = java.util.concurrent.CountDownLatch(1)
            runOnUiThread { ok = Premium.buy(this@MainActivity, which); latch.countDown() }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            return ok
        }

        @JavascriptInterface
        fun restore() = Premium.refresh()

        @JavascriptInterface
        fun reviewCode(code: String): Boolean = Premium.tryReviewCode(this@MainActivity, code)

        /** 테스트용 APK인지 (테스트에서만 '프리미엄 켜기' 스위치를 보여줌) */
        @JavascriptInterface
        fun isTestBuild(): Boolean = BuildConfig.DEBUG

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_CODE.toString()
    }
}
