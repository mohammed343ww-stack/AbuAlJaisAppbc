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
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
                pickVideos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
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
        val M = MediaStore.Video.Media
        val proj = arrayOf(M._ID, M.DISPLAY_NAME, M.SIZE, M.DURATION, M.WIDTH, M.HEIGHT, M.DATE_MODIFIED)
        val arr = JSONArray()
        contentResolver.query(col, proj, null, null, "${M.DATE_ADDED} DESC")?.use { c ->
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
        val M = MediaStore.Audio.Media
        val proj = arrayOf(M._ID, M.DISPLAY_NAME, M.TITLE, M.ARTIST, M.DURATION, M.SIZE)
        val sel = "${M.IS_RINGTONE}=0 AND ${M.IS_NOTIFICATION}=0 AND ${M.IS_ALARM}=0 AND ${M.DURATION}>0"
        val arr = JSONArray()
        contentResolver.query(col, proj, sel, null, "${M.DATE_ADDED} DESC")?.use { c ->
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
                    .put("dur", dur).put("url", "https://$host/media/file/$id")
                if (kind == "video") o.put("w", w).put("h", h).put("thumb", "https://$host/media/thumb/$id")
                else o.put("title", title).put("artist", artist).put("art", "https://$host/media/art/$id")
                arr.put(o)
            } catch (_: Exception) {}
        }
        send(kind, "limited", arr.toString())
    }

    // ---------- يخدم الملفات للـ WebView (مع Range حتى يشتغل التقديم/التأخير) ----------
    private val cors = mapOf("Access-Control-Allow-Origin" to "*")

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", cors,
        ByteArrayInputStream(ByteArray(0)))

    private fun serveMedia(u: Uri, h: Map<String, String>): WebResourceResponse {
        val seg = u.pathSegments
        if (seg.size < 3) return notFound()
        val uri = uris[seg[2]] ?: return notFound()
        return when (seg[1]) {
            "file" -> serveFile(uri, h)
            "thumb" -> imageResponse(thumbBytes(uri))
            "art" -> imageResponse(artBytes(uri))
            else -> notFound()
        }
    }

    private class Limited(private val i: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = i.read(); if (b >= 0) left--; return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = i.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() = i.close()
    }

    private fun serveFile(uri: Uri, h: Map<String, String>): WebResourceResponse {
        val afd = try { contentResolver.openAssetFileDescriptor(uri, "r") } catch (e: Exception) { null }
            ?: return notFound()
        val total = afd.length
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val base = cors + mapOf("Accept-Ranges" to "bytes")
        val rng = h.entries.firstOrNull { it.key.equals("Range", true) }?.value
        if (rng != null && total > 0) {
            val m = Regex("bytes=(\\d*)-(\\d*)").find(rng)
            var s = m?.groupValues?.get(1)?.toLongOrNull()
            var e = m?.groupValues?.get(2)?.toLongOrNull()
            if (s == null && e != null) { s = maxOf(0L, total - e); e = total - 1 }
            if (s == null) s = 0L
            if (e == null || e >= total) e = total - 1
            if (s > e) return WebResourceResponse(mime, null, 416, "Range Not Satisfiable",
                base + mapOf("Content-Range" to "bytes */$total"), ByteArrayInputStream(ByteArray(0)))
            val ins = afd.createInputStream()
            var skip = s
            while (skip > 0) { val k = ins.skip(skip); if (k <= 0) break; skip -= k }
            val len = e - s + 1
            return WebResourceResponse(mime, null, 206, "Partial Content",
                base + mapOf("Content-Range" to "bytes $s-$e/$total", "Content-Length" to len.toString()),
                Limited(ins, len))
        }
        val hdr = if (total > 0) base + mapOf("Content-Length" to total.toString()) else base
        return WebResourceResponse(mime, null, 200, "OK", hdr, afd.createInputStream())
    }

    private fun jpeg(b: Bitmap): ByteArray {
        val o = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 80, o); return o.toByteArray()
    }

    private fun thumbBytes(uri: Uri): ByteArray? {
        if (Build.VERSION.SDK_INT >= 29) try {
            return jpeg(contentResolver.loadThumbnail(uri, Size(480, 270), null))
        } catch (_: Exception) {}
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            (r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0))?.let { jpeg(it) }
        } catch (_: Exception) { null } finally { r.release() }
    }

    private fun artBytes(uri: Uri): ByteArray? {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(this, uri); r.embeddedPicture?.let { return it } }
        catch (_: Exception) {} finally { r.release() }
        if (Build.VERSION.SDK_INT >= 29) try {
            return jpeg(contentResolver.loadThumbnail(uri, Size(512, 512), null))
        } catch (_: Exception) {}
        return null
    }

    private fun imageResponse(b: ByteArray?): WebResourceResponse =
        if (b == null) notFound()
        else WebResourceResponse("image/jpeg", null, 200, "OK",
            cors + mapOf("Cache-Control" to "max-age=86400"), ByteArrayInputStream(b))

    // ---------- WebView ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .setDomain(host)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
        }
        web.addJavascriptInterface(Bridge(), "AbuNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url
                if (u.host == host && u.path?.startsWith("/media/") == true)
                    return serveMedia(u, r.requestHeaders)
                return loader.shouldInterceptRequest(u)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            private var custom: View? = null
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                custom = v
                addContentView(v, ViewGroup.LayoutParams(-1, -1))
                web.visibility = View.GONE
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
            override fun onHideCustomView() {
                custom?.let { (it.parent as? ViewGroup)?.removeView(it) }
                custom = null
                web.visibility = View.VISIBLE
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
        // الصفحة لازم تنفتح من نفس الدومين حتى المعدّل (EQ) والصور تشتغل بدون CORS
        web.loadUrl("https://$host/assets/index.html")
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}
