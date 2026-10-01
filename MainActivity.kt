package com.abu.player

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val host = "appassets.androidplatform.net"
    private val io = Executors.newFixedThreadPool(3)
    private val uris = ConcurrentHashMap<String, Uri>()   // id -> content uri
    private val counter = AtomicInteger(0)
    private var pendingKind = ""

    // ---------- launchers ----------
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAccess(pendingKind)) scan(pendingKind) else denied(pendingKind)
        }

    // "السماح بالوصول المحدود" للفيديو: منتقي الصور/الفيديو الرسمي (بدون إذن)
    private val pickVideos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { l ->
            if (l.isNotEmpty()) importPicked("video", l)
        }

    // "السماح بالوصول المحدود" للأغاني: اختيار ملفات صوت محددة
    private val pickAudios =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l ->
            if (l.isNotEmpty()) importPicked("audio", l)
        }

    // ---------- permissions ----------
    private fun perms(kind: String): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 && kind == "video" -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            if (kind == "video") Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_MEDIA_AUDIO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasAccess(kind: String) = perms(kind).any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun request(kind: String, mode: String) {
        if (mode == "limited") {
            if (kind == "video")
                pickVideos.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly))
            else pickAudios.launch(arrayOf("audio/*"))
            return
        }
        if (hasAccess(kind)) { scan(kind); return }
        pendingKind = kind
        permLauncher.launch(perms(kind))
    }

    // ---------- JS bridge ----------
    inner class Bridge {
        @JavascriptInterface fun sdk(): Int = Build.VERSION.SDK_INT
        @JavascriptInterface fun has(kind: String): Boolean = hasAccess(kind)
        @JavascriptInterface fun requestMedia(kind: String, mode: String) {
            runOnUiThread { request(kind, mode) }
        }
        @JavascriptInterface fun openSettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            }
        }
    }

    private fun send(kind: String, mode: String, json: String) = runOnUiThread {
        web.evaluateJavascript(
            "window.__abuNative&&window.__abuNative.onMedia(" +
                "${JSONObject.quote(kind)},${JSONObject.quote(mode)},${JSONObject.quote(json)})", null)
    }

    private fun denied(kind: String) = runOnUiThread {
        val permanent = perms(kind).none { shouldShowRequestPermissionRationale(it) }
        web.evaluateJavascript(
            "window.__abuNative&&window.__abuNative.onDenied(${JSONObject.quote(kind)},$permanent)", null)
    }

    // ---------- "السماح بالكل": كل الفيديوهات / كل الأغاني من MediaStore ----------
    private fun scan(kind: String) = io.execute {
        try {
            send(kind, "all", if (kind == "video") queryVideos() else queryAudios())
        } catch (e: Exception) { send(kind, "all", "[]") }
    }

    private fun queryVideos(): String {
        val col = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        val arr = JSONArray()
        contentResolver.query(
            col, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val mid = c.getLong(0)
                val id = "v$mid"
                uris[id] = ContentUris.withAppendedId(col, mid)
                arr.put(JSONObject()
                    .put("id", id).put("name", c.getString(1) ?: "video")
                    .put("size", c.getLong(2)).put("dur", c.getLong(3) / 1000.0)
                    .put("w", c.getInt(4)).put("h", c.getInt(5)).put("mod", c.getLong(6))
                    .put("url", "https://$host/media/file/$id")
                    .put("thumb", "https://$host/media/thumb/$id"))
            }
        }
        return arr.toString()
    }

    private fun queryAudios(): String {
        val col = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        val sel = "${MediaStore.Audio.Media.IS_RINGTONE}=0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION}=0 AND " +
            "${MediaStore.Audio.Media.IS_ALARM}=0 AND " +
            "${MediaStore.Audio.Media.DURATION}>0"
        val arr = JSONArray()
        contentResolver.query(
            col, proj, sel, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val mid = c.getLong(0)
                val id = "a$mid"
                uris[id] = ContentUris.withAppendedId(col, mid)
                val artist = c.getString(3)?.takeIf { it != "<unknown>" } ?: ""
                arr.put(JSONObject()
                    .put("id", id).put("name", c.getString(1) ?: "audio")
                    .put("title", c.getString(2) ?: "").put("artist", artist)
                    .put("dur", c.getLong(4) / 1000.0).put("size", c.getLong(5))
                    .put("url", "https://$host/media/file/$id")
                    .put("art", "https://$host/media/art/$id"))
            }
        }
        return arr.toString()
    }

    // ---------- "المحدود": الملفات اللي اختارها المستخدم فقط ----------
    private fun importPicked(kind: String, list: List<Uri>) = io.execute {
        val arr = JSONArray()
        for (u in list) {
            try {
                if (kind == "audio") try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                var name = "file"; var size = 0L
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val z = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0) name = c.getString(n) ?: name
                        if (z >= 0) size = c.getLong(z)
                    }
                }
                val r = MediaMetadataRetriever()
                var dur = 0.0; var w = 0; var h = 0; var title = ""; var artist = ""
                try {
                    r.setDataSource(this, u)
                    fun m(k: Int) = r.extractMetadata(k)
                    dur = (m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
                    w = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    title = m(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                    artist = m(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                } catch (_: Exception) {} finally { r.release() }

                val id = "p${counter.incrementAndGet()}"
                uris[id] = u
                val o = JSONObject().put("id", id).put("name", name).put("size", size)
