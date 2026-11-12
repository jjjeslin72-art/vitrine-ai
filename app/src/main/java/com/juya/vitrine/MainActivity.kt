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
            .addPathHandler("/music/", WebViewAssetLoader.InternalStoragePathHandler(this, File(filesDir, "music").apply { mkdirs() }))
            .build()
    }

    // 사진첩에서 고르기 (안드로이드 사진 선택기 — 권한 필요 없음)
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> deliver(uri) }

    // 사진첩에서 여러 장 한꺼번에 고르기 (최대 10장)
    private var multiMax = 1
    private val pickMany = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> deliverMany(uris) }

    // 폰에 있는 음악 고르기 → 앱 안 music 폴더에 복사 → window.__audio(id, 주소, 이름, 오류)
    private var audioId: String? = null
    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> deliverAudio(uri) }
    private fun deliverAudio(uri: Uri?) {
        val id = audioId ?: return; audioId = null
        fun send(url: String, name: String, err: String) = runOnUiThread {
            web.evaluateJavascript("window.__audio && window.__audio(${JSONObject.quote(id)}, ${JSONObject.quote(url)}, ${JSONObject.quote(name)}, ${JSONObject.quote(err)})", null)
        }
        if (uri == null) { send("", "", "cancel"); return }
        Thread {
            try {
                var name = "music"
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: name
                        if (!c.isNull(1) && c.getLong(1) > 60L * 1024 * 1024) { send("", name, "big"); return@Thread }
                    }
                }
                val ext = name.substringAfterLast('.', "mp3").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "mp3" }
                val dir = File(filesDir, "music").apply { mkdirs() }
                // 오래된 음악은 5개만 남기고 지움 (용량 아끼기)
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
                val f = File(dir, "m${System.currentTimeMillis()}.$ext")
                contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } ?: throw Exception("read")
                send("https://appassets.androidplatform.net/music/${f.name}", name.substringBeforeLast('.'), "")
            } catch (e: Exception) { send("", "", "fail") }
        }.start()
    }

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

    // 🛍 판매: 글 파일 고르기 (왓츠앱 대화 내보내기 .txt/.zip, 연락처 .vcf, 백업 .json) → window.__textFile(id, 글, 오류)
    private var textId: String? = null
    private val pickTextLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> deliverText(uri) }
    private fun deliverText(uri: Uri?) {
        val id = textId ?: return; textId = null
        fun send(text: String, err: String) = runOnUiThread {
            web.evaluateJavascript("window.__textFile && window.__textFile(${JSONObject.quote(id)}, ${JSONObject.quote(text)}, ${JSONObject.quote(err)})", null)
        }
        if (uri == null) { send("", "cancel"); return }
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("read")
                if (bytes.size > 30 * 1024 * 1024) { send("", "big"); return@Thread }
                val text = if (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                    // 왓츠앱 '대화 내보내기'가 zip으로 올 때: 안의 .txt / .vcf만 읽음
                    val sb = StringBuilder()
                    java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            val n = e.name.lowercase()
                            if (!e.isDirectory && (n.endsWith(".txt") || n.endsWith(".vcf"))) sb.append(String(z.readBytes(), Charsets.UTF_8)).append('\n')
                            e = z.nextEntry
                        }
                    }
                    sb.toString()
                } else String(bytes, Charsets.UTF_8)
                send(text, if (text.isBlank()) "empty" else "")
            } catch (e: Exception) { send("", "fail") }
        }.start()
    }

    // 🛍 판매: 휴대폰 연락처 읽기 (권한 필요) → window.__contacts(id, JSON [{name, phone}], 오류)
    private var contactsId: String? = null
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) readContacts() else sendContacts("[]", "perm")
    }
    private fun sendContacts(json: String, err: String) {
        val id = contactsId ?: return; contactsId = null
        runOnUiThread { web.evaluateJavascript("window.__contacts && window.__contacts(${JSONObject.quote(id)}, ${JSONObject.quote(json)}, ${JSONObject.quote(err)})", null) }
    }
    private fun readContacts() {
        Thread {
            val arr = org.json.JSONArray()
            try {
                contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )?.use { c ->
                    while (c.moveToNext() && arr.length() < 5000) {
                        arr.put(JSONObject().put("name", c.getString(0) ?: "").put("phone", c.getString(1) ?: ""))
                    }
                }
                sendContacts(arr.toString(), "")
            } catch (e: Exception) { sendContacts("[]", "fail") }
        }.start()
    }
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
            mediaPlaybackRequiresUserGesture = false   // 영상에 음악 넣을 때 자동 재생 필요
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

    // 갤러리에서 '공유 → ClickModa'로 보낸 옷 사진
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

        /** 사진첩 'ClickModa' 앨범에 저장. 결과: "ok:위치" 또는 "err:이유" */
        @JavascriptInterface
        fun saveFile(name: String, b64: String, mime: String, sub: String): String {
            return try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val isVideo = mime.startsWith("video")
                val isDoc = !isVideo && !mime.startsWith("image")   // PDF 등은 '다운로드' 폴더로
                val rel = (if (isVideo) Environment.DIRECTORY_MOVIES else if (isDoc) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_PICTURES) + "/Click.moda AI" + (if (sub.isNotBlank()) "/" + sub.replace("/", "_") else "")
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(when {
                        isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        isDoc -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }, cv)
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
                "ok:" + rel
            } catch (e: Exception) {
                "err:" + (e.message ?: "erro")
            }
        }

        /** 공유 창 */
        @JavascriptInterface
        fun shareFile(name: String, b64: String, mime: String): String = try {
            val send = shareIntent(name, b64, mime)
            runOnUiThread { startActivity(Intent.createChooser(send, "Click.moda AI")) }
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
                    try { startActivity(send) } catch (_: ActivityNotFoundException) { send.setPackage(null); startActivity(Intent.createChooser(send, "Click.moda AI")) }
                } else startActivity(Intent.createChooser(send, "Click.moda AI"))
            }
            "ok"
        } catch (e: Exception) { "err:" + (e.message ?: "erro") }

        /** 사진 + 글을 같이 보내기 (왓츠앱은 글이 사진 설명으로 붙어요). pkg 비우면 공유 창 */
        @JavascriptInterface
        fun shareImageText(name: String, b64: String, mime: String, text: String, pkg: String): String = try {
            val send = shareIntent(name, b64, mime).apply { putExtra(Intent.EXTRA_TEXT, text) }
            val target = if (pkg.isEmpty()) null else listOf(pkg, "com.whatsapp.w4b").firstOrNull {
                try { packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
            }
            runOnUiThread {
                if (target != null) {
                    send.setPackage(target)
                    try { startActivity(send) } catch (_: ActivityNotFoundException) { send.setPackage(null); startActivity(Intent.createChooser(send, "Click.moda AI")) }
                } else startActivity(Intent.createChooser(send, "Click.moda AI"))
            }
            "ok"
        } catch (e: Exception) { "err:" + (e.message ?: "erro") }

        /** 인스타그램 피드·스토리·릴스로 바로 보내기. where = feed | story | reels. 결과: ok / noapp / err */
        @JavascriptInterface
        fun shareInsta(name: String, b64: String, mime: String, where: String): String = try {
            val ig = "com.instagram.android"
            val has = try { packageManager.getPackageInfo(ig, 0); true } catch (_: Exception) { false }
            val send = shareIntent(name, b64, mime)
            if (!has) {
                runOnUiThread { startActivity(Intent.createChooser(send, "Click.moda AI")) }
                "noapp"
            } else {
                val probe = Intent(Intent.ACTION_SEND).apply { type = mime; setPackage(ig) }
                val acts = packageManager.queryIntentActivities(probe, 0).map { it.activityInfo.name }
                val pick = when (where) {
                    "story" -> acts.firstOrNull { it.contains("Story", true) }
                    "reels" -> acts.firstOrNull { it.contains("Clips", true) || it.contains("Reel", true) }
                    else -> acts.firstOrNull { it.endsWith("ShareHandlerActivity") && !it.contains("Story", true) && !it.contains("Clips", true) && !it.contains("Direct", true) }
                        ?: acts.firstOrNull { it.contains("Feed", true) }
                }
                if (where == "story") send.putExtra("source_application", packageName)
                runOnUiThread {
                    try {
                        if (pick != null) send.setClassName(ig, pick) else send.setPackage(ig)
                        startActivity(send)
                    } catch (_: Exception) {
                        // 정해진 곳이 안 열리면 인스타그램 자체 선택 화면으로
                        try { send.component = null; send.setPackage(ig); startActivity(send) }
                        catch (_: Exception) { send.setPackage(null); startActivity(Intent.createChooser(send, "Click.moda AI")) }
                    }
                }
                "ok"
            }
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

        /** 폰 음악 고르기. 결과는 window.__audio(id, 주소, 이름, 오류) */
        @JavascriptInterface
        fun pickAudio(id: String) {
            runOnUiThread {
                audioId = id
                try { pickAudioLauncher.launch("audio/*") } catch (_: ActivityNotFoundException) { deliverAudio(null) }
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

        /** 글 복사 */
        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Click.moda AI", text))
            }
        }

        /** 글을 왓츠앱 등으로 보내기 (pkg가 비어 있으면 공유 창) */
        @JavascriptInterface
        fun shareText(text: String, pkg: String) {
            runOnUiThread {
                val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                val target = listOf(pkg, "com.whatsapp.w4b").firstOrNull { it.isNotBlank() &&
                    try { packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false } }
                try {
                    if (target != null) { i.setPackage(target); startActivity(i) }
                    else startActivity(Intent.createChooser(i, "Click.moda AI"))
                } catch (_: ActivityNotFoundException) { startActivity(Intent.createChooser(i, "Click.moda AI")) }
            }
        }

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_CODE.toString()

        /** 🛍 사진 여러 장 + 글을 한 번에 (왓츠앱에서 그룹 여러 개 체크해서 보내기). files = [{name, b64}] */
        @JavascriptInterface
        fun shareImagesText(files: String, text: String, pkg: String): String = try {
            val arr = org.json.JSONArray(files)
            val dir = File(cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.filter { it.name.startsWith("loja_") }?.forEach { it.delete() }
            val uris = ArrayList<Uri>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val f = File(dir, "loja_" + o.getString("name").replace(Regex("[^A-Za-z0-9._-]"), "_"))
                f.writeBytes(Base64.decode(o.getString("b64"), Base64.DEFAULT))
                uris.add(FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f))
            }
            val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
                       else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
            send.type = if (uris.isEmpty()) "text/plain" else "image/jpeg"
            send.putExtra(Intent.EXTRA_TEXT, text)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val target = if (pkg.isEmpty()) null else listOf(pkg, "com.whatsapp.w4b").firstOrNull {
                try { packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
            }
            runOnUiThread {
                if (target != null) {
                    send.setPackage(target)
                    try { startActivity(send) } catch (_: ActivityNotFoundException) { send.setPackage(null); startActivity(Intent.createChooser(send, "Click.moda AI")) }
                } else startActivity(Intent.createChooser(send, "Click.moda AI"))
            }
            "ok"
        } catch (e: Exception) { "err:" + (e.message ?: "erro") }

        /** 🛍 글 파일 고르기. 결과는 window.__textFile(id, 글, 오류) */
        @JavascriptInterface
        fun pickTextFile(id: String) {
            runOnUiThread {
                textId = id
                try { pickTextLauncher.launch("*/*") } catch (_: ActivityNotFoundException) { deliverText(null) }
            }
        }

        /** 🛍 연락처 목록. 처음엔 권한을 물어봄. 결과는 window.__contacts(id, JSON, 오류) */
        @JavascriptInterface
        fun pickContacts(id: String) {
            runOnUiThread {
                contactsId = id
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) readContacts()
                else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }
}
