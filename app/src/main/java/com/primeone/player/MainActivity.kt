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
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import android.util.Base64
import android.os.Bundle
import android.os.Environment
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
        val permissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = permissions.filter {
            Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        activity.runOnUiThread { ActivityCompat.requestPermissions(activity, missing.toTypedArray(), 42) }
        return false
    }

    @JavascriptInterface fun listMusic(): String {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().put("permissionRequired", true).put("songs", JSONArray()).toString()
        }

        val songs = JSONArray()
        val seen = HashSet<String>()
        var queriedVolumes = 0
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.ALBUM_ID
        )
        try {
            // Query each mounted MediaStore volume. Some Android devices do not
            // return internal-storage audio reliably from the aggregate EXTERNAL URI.
            val volumes: Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.getExternalVolumeNames(activity)
            } else {
                setOf("external")
            }
            for (volume in volumes) {
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Audio.Media.getContentUri(volume)
                else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                // The phone's file modification time is the source of truth:
                // newest files first, independent of title or scan order.
                val sort = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                activity.contentResolver.query(collection, projection, null, null, sort)?.use { c ->
                    queriedVolumes++
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val modifiedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                    val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        if (!seen.add("$volume:$id")) continue
                        val displayName = c.getString(nameCol) ?: "Unknown audio"
                        val rawPath = if (dataCol >= 0 && !c.isNull(dataCol)) c.getString(dataCol) else null
                        val path = rawPath?.takeIf { it.isNotBlank() }
                            ?: "/storage/emulated/0/Music/$displayName"
                        val fileName = path.substringAfterLast('/')
                        val folder = path.substringBeforeLast('/', "/storage/emulated/0/Music")
                        val contentUri = android.content.ContentUris.withAppendedId(collection, id)
                        val rawTitle = c.getString(titleCol)
                        val title = rawTitle?.takeIf { it.isNotBlank() && it != "<unknown>" }
                            ?: fileName.substringBeforeLast('.', fileName)
                        val artist = c.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        val album = c.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        val modified = if (modifiedCol >= 0 && !c.isNull(modifiedCol)) c.getLong(modifiedCol) else 0L
                        val albumId = if (albumIdCol >= 0 && !c.isNull(albumIdCol)) c.getLong(albumIdCol) else 0L
                        val artUri = if (albumId > 0L) "content://media/$volume/audio/albumart/$albumId" else ""
                        val item = JSONObject()
                            .put("id", "$volume:$id")
                            .put("title", title)
                            .put("name", fileName)
                            .put("path", path)
                            .put("folder", folder)
                            .put("uri", contentUri.toString())
                            .put("duration", (c.getLong(durationCol) / 1000.0))
                            .put("modified", modified)
                            .put("artUri", artUri)
                        if (artist != null) item.put("artist", artist)
                        if (album != null) item.put("album", album)
                        songs.put(item)
                    }
                }
            }
            return JSONObject()
                .put("permissionRequired", false)
                .put("queriedVolumes", queriedVolumes)
                .put("count", songs.length())
                .put("songs", songs).toString()
        } catch (e: Exception) {
            return JSONObject().put("permissionRequired", false)
                .put("queriedVolumes", queriedVolumes)
                .put("error", e.message ?: "Media scan failed")
                .put("songs", songs).toString()
        }
    }

    private fun storageAllowed(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun safeStoragePath(raw: String): File {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val target = File(raw).canonicalFile
        if (target.path != root.path && !target.path.startsWith(root.path + File.separator)) {
            throw SecurityException("Only shared internal storage is available")
        }
        return target
    }

    @JavascriptInterface fun hasAllFiles(): String = JSONObject()
        .put("value", storageAllowed()).toString()

    @JavascriptInterface fun requestAllFiles(): String {
        if (Build.VERSION.SDK_INT >= 30 && !storageAllowed()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.packageName))
                activity.startActivity(intent)
            } catch (_: Exception) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
        return "{}"
    }

    @JavascriptInterface fun openAppSettings(): String {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + activity.packageName)))
        return "{}"
    }

    @JavascriptInterface fun listStorage(path: String): String {
        if (!storageAllowed()) return JSONObject().put("permissionRequired", true).put("entries", JSONArray()).toString()
        return try {
            val dir = safeStoragePath(path.ifBlank { Environment.getExternalStorageDirectory().absolutePath })
            if (!dir.exists() || !dir.isDirectory) throw IllegalArgumentException("Folder not found")
            val all = dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
            val arr = JSONArray()
            all.take(1000).forEach { f ->
                arr.put(JSONObject().put("name", f.name).put("path", f.absolutePath)
                    .put("directory", f.isDirectory).put("size", if (f.isFile) f.length() else 0L)
                    .put("modified", f.lastModified()))
            }
            JSONObject().put("path", dir.absolutePath).put("truncated", all.size > 1000).put("entries", arr).toString()
        } catch (e: Exception) { JSONObject().put("error", e.message ?: "Cannot list folder").put("entries", JSONArray()).toString() }
    }

    @JavascriptInterface fun listDir(path: String): String = listStorage(path)

    @JavascriptInterface fun deleteFile(path: String): String {
        if (!storageAllowed()) throw SecurityException("Enable All files access first")
        val f = safeStoragePath(path)
        if (!f.exists()) throw IllegalArgumentException("File no longer exists")
        if (f.isDirectory) throw IllegalArgumentException("Folder deletion is disabled for safety")
        if (!f.delete()) throw IllegalStateException("Android could not delete this file")
        return "{}"
    }

    @JavascriptInterface fun renameFile(path: String, newName: String): String {
        if (!storageAllowed()) throw SecurityException("Enable All files access first")
        if (newName.isBlank() || newName.contains("/") || newName.contains("\\")) throw IllegalArgumentException("Invalid file name")
        val src = safeStoragePath(path)
        val dst = safeStoragePath(File(src.parentFile, newName).absolutePath)
        if (!src.exists() || dst.exists()) throw IllegalArgumentException("Source missing or destination already exists")
        if (!src.renameTo(dst)) throw IllegalStateException("Rename failed")
        return JSONObject().put("path", dst.absolutePath).toString()
    }

    @JavascriptInterface fun copyFile(path: String, destination: String): String {
        if (!storageAllowed()) throw SecurityException("Enable All files access first")
        val src = safeStoragePath(path); val dst = safeStoragePath(destination)
        if (!src.isFile || dst.exists()) throw IllegalArgumentException("Source missing or destination already exists")
        dst.parentFile?.mkdirs()
        FileInputStream(src).use { input -> FileOutputStream(dst).use { output -> input.copyTo(output) } }
        return JSONObject().put("path", dst.absolutePath).toString()
    }

    @JavascriptInterface fun moveFile(path: String, destination: String): String {
        if (!storageAllowed()) throw SecurityException("Enable All files access first")
        val src = safeStoragePath(path); val dst = safeStoragePath(destination)
        if (!src.exists() || dst.exists()) throw IllegalArgumentException("Source missing or destination already exists")
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            if (!src.isFile) throw IllegalStateException("Move failed")
            FileInputStream(src).use { input -> FileOutputStream(dst).use { output -> input.copyTo(output) } }
            if (!src.delete()) { dst.delete(); throw IllegalStateException("Could not remove original after copy") }
        }
        return JSONObject().put("path", dst.absolutePath).toString()
    }

    @JavascriptInterface fun writeFile(path: String, data: String, keepTime: Boolean): String {
        if (!storageAllowed()) throw SecurityException("Enable All files access first")
        val f = safeStoragePath(path)
        f.parentFile?.mkdirs()
        val modified = f.lastModified()
        val bytes = Base64.decode(data, Base64.DEFAULT)
        FileOutputStream(f, false).use { it.write(bytes) }
        if (keepTime && modified > 0) f.setLastModified(modified)
        return "{}"
    }

    @JavascriptInterface fun openUrl(url: String): String {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        return "{}"
    }

    @JavascriptInterface fun requestOverlay(): String = "{}"
    @JavascriptInterface fun canOverlay(): String = JSONObject().put("value", false).toString()
    @JavascriptInterface fun setLockEnabled(enabled: Boolean): String = "{}"
    @JavascriptInterface fun setNowPlaying(value: String): String = "{}"
    @JavascriptInterface fun addListener(name: String): String = "{}"

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
