package com.primeone.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.database.Cursor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }

        web = WebView(this)
        web.setBackgroundColor(android.graphics.Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString + " PRIMEONEAndroid"
        }

        // Android WebView does not open HTML <input type=file> controls unless
        // the host Activity implements onShowFileChooser. This enables Add Songs,
        // Add Videos, artwork and other file-selection controls in the player.
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                if (callback == null || params == null) return false

                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                return try {
                    val pickerIntent = params.createIntent().apply {
                        putExtra(
                            Intent.EXTRA_ALLOW_MULTIPLE,
                            params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                        )
                    }
                    startActivityForResult(pickerIntent, FILE_CHOOSER_REQUEST)
                    true
                } catch (_: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    false
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            // Stream MediaStore content:// audio URIs into the HTML audio element.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                if (uri.scheme != "content") return super.shouldInterceptRequest(view, request)
                return try {
                    val stream = contentResolver.openInputStream(uri) ?: return null
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    WebResourceResponse(mime, null, stream)
                } catch (_: Exception) { null }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript("window.__PRIME_ONE_ANDROID__=true;", null)
            }
        }
        web.addJavascriptInterface(KD6Bridge(this), "KD6Native")
        setContentView(web)
        activeWeb = WeakReference(web)
        startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_INIT))
        web.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val callback = filePathCallback
            filePathCallback = null
            val results = if (resultCode == RESULT_OK && data != null) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            }
            callback?.onReceiveValue(results)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        if (::web.isInitialized) {
            if (isFinishing) {
                web.stopLoading()
                web.destroy()
            }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("if(typeof goBack==='function'){goBack()}else{history.back()}", null)
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST = 4207
        @Volatile var activeWeb: WeakReference<WebView>? = null

        fun sendAction(action: String, position: Long = 0L) {
            val js = when (action) {
                "seekto" -> "window.__kd6act&&window.__kd6act('seekto',{seekTime:$position/1000})"
                else -> "window.__kd6act&&window.__kd6act('$action')"
            }
            activeWeb?.get()?.post { activeWeb?.get()?.evaluateJavascript(js, null) }
        }
    }
}

class KD6Bridge(private val activity: Activity) {
    @JavascriptInterface fun hello() { /* bridge handshake */ }

    // MediaStore scan used by the player's Scan and Browse by folder features.
    @JavascriptInterface fun requestMusicPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT < 23 || ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) return true
        activity.runOnUiThread {
            ActivityCompat.requestPermissions(activity, arrayOf(permission), 42)
        }
        return false
    }

    @JavascriptInterface fun listMusic(): String {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().put("permissionRequired", true).put("songs", JSONArray()).toString()
        }
        val songs = JSONArray()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        var cursor: Cursor? = null
        try {
            cursor = activity.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, sort
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val displayName = c.getString(nameCol) ?: "Unknown audio"
                    val rawPath = if (dataCol >= 0) c.getString(dataCol) else null
                    val path = rawPath?.takeIf { it.isNotBlank() } ?: "/storage/emulated/0/Music/$displayName"
                    val fileName = path.substringAfterLast('/')
                    val folder = path.substringBeforeLast('/', "/storage/emulated/0/Music")
                    val contentUri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val title = c.getString(titleCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: fileName.substringBeforeLast('.', fileName)
                    val artist = c.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    val album = c.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    val item = JSONObject()
                        .put("id", id)
                        .put("title", title)
                        .put("name", fileName)
                        .put("path", path)
                        .put("folder", folder)
                        .put("uri", contentUri.toString())
                        .put("duration", (c.getLong(durationCol) / 1000.0))
                    if (artist != null) item.put("artist", artist)
                    if (album != null) item.put("album", album)
                    songs.put(item)
                }
            }
            return JSONObject().put("permissionRequired", false).put("songs", songs).toString()
        } catch (e: Exception) {
            return JSONObject().put("permissionRequired", false).put("error", e.message ?: "Media scan failed").put("songs", songs).toString()
        }
    }

    @JavascriptInterface fun meta(title: String, artist: String, album: String, artwork: String) {
        val i = Intent(activity, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_META
            putExtra("title", title)
            putExtra("artist", artist)
            putExtra("album", album)
            putExtra("artwork", artwork)
        }
        if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(i) else activity.startService(i)
    }

    @JavascriptInterface fun state(value: String) {
        activity.startService(Intent(activity, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_STATE).putExtra("state", value))
    }

    @JavascriptInterface fun pos(duration: Double, position: Double, rate: Double) {
        activity.startService(Intent(activity, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_POS)
            .putExtra("duration", duration)
            .putExtra("position", position)
            .putExtra("rate", rate))
    }
}
