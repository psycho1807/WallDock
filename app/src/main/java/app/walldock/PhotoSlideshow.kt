package app.walldock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Écran de veille photos : diaporama d'un album **Immich** (adresse du serveur, clé d'API,
 * lien ou identifiant de l'album) ou d'un **dossier** de la tablette, avec l'heure dans un coin.
 * Les images sont chargées à la taille de l'écran, une à la fois, en fondu.
 */
class PhotoSlideshow(private val context: Context, private val container: FrameLayout) {

    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)
    private var root: FrameLayout? = null
    private lateinit var front: ImageView
    private lateinit var back: ImageView
    private var sources: List<Source> = emptyList()
    private var index = 0
    @Volatile private var running = false
    private var current: Bitmap? = null

    private sealed class Source {
        class Immich(val id: String) : Source()
        class Local(val uri: Uri) : Source()
    }

    /** Vrai si un album Immich ou un dossier de photos est réglé. */
    fun isConfigured(): Boolean =
        (prefs.immichUrl.isNotBlank() && prefs.immichKey.isNotBlank() && prefs.immichAlbum.isNotBlank()) ||
            prefs.photoFolder.isNotBlank()

    fun start() {
        if (running) return
        running = true
        build()
        Thread({
            val list = try {
                loadSources()
            } catch (e: Exception) {
                emptyList()
            }
            main.post {
                if (!running) return@post
                sources = list.shuffled()
                index = 0
                if (sources.isEmpty()) {
                    context.toast(tr(R.string.photos_none))
                } else {
                    next()
                }
            }
        }, "photos-list").start()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        root?.let { container.removeView(it) }
        root = null
        current?.recycle()
        current = null
    }

    private fun build() {
        val layer = FrameLayout(context)
        layer.setBackgroundColor(Color.BLACK)
        back = ImageView(context).also { it.scaleType = ImageView.ScaleType.CENTER_CROP; it.alpha = 0f }
        front = ImageView(context).also { it.scaleType = ImageView.ScaleType.CENTER_CROP }
        layer.addView(back, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        layer.addView(front, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val clock = TextClock(context)
        clock.format24Hour = "HH:mm"
        clock.format12Hour = "HH:mm"
        clock.textSize = 54f
        clock.setTextColor(Color.WHITE)
        clock.setShadowLayer(12f, 0f, 2f, Color.BLACK)
        val params = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        params.setMargins(0, 0, context.dp(32), context.dp(24))
        layer.addView(clock, params)
        container.addView(layer, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root = layer
    }

    private fun next() {
        if (!running || sources.isEmpty()) return
        val source = sources[index % sources.size]
        index++
        val width = container.width.coerceAtLeast(800)
        val height = container.height.coerceAtLeast(600)
        Thread({
            val bitmap = try {
                decode(source, width, height)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (!running) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap != null) show(bitmap)
                main.postDelayed({ next() }, if (bitmap != null) prefs.photoSeconds * 1000L else 1_000L)
            }
        }, "photos-load").start()
    }

    /** Fondu de l'ancienne photo vers la nouvelle. */
    private fun show(bitmap: Bitmap) {
        back.setImageBitmap(bitmap)
        back.alpha = 0f
        back.animate().alpha(1f).setDuration(1_200).withEndAction {
            front.setImageBitmap(bitmap)
            back.alpha = 0f
            back.setImageDrawable(null)
            current?.recycle()
            current = bitmap
        }.start()
    }

    // ---- Sources ----

    private fun loadSources(): List<Source> {
        val list = ArrayList<Source>()
        if (prefs.immichUrl.isNotBlank() && prefs.immichKey.isNotBlank() && prefs.immichAlbum.isNotBlank()) {
            list.addAll(immichAssets())
        }
        if (prefs.photoFolder.isNotBlank()) list.addAll(localImages(Uri.parse(prefs.photoFolder)))
        return list
    }

    private fun immichBase() = prefs.immichUrl.trim().trimEnd('/')

    /** Identifiant de l'album : accepte le lien complet de l'album ou l'identifiant seul. */
    private fun albumId(): String {
        val raw = prefs.immichAlbum.trim()
        return Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").find(raw)?.value ?: raw
    }

    private fun immichAssets(): List<Source> {
        val album = albumId()
        val result = ArrayList<Source>()
        // Album avec ses photos
        val json = request("${immichBase()}/api/albums/$album", null)?.let { JSONObject(it) }
        val assets = json?.optJSONArray("assets")
        if (assets != null && assets.length() > 0) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("type", "IMAGE") == "IMAGE") result.add(Source.Immich(a.getString("id")))
            }
            return result
        }
        // Versions récentes d'Immich : recherche des photos de l'album
        var page = 1
        while (page <= 10) {
            val body = JSONObject().put("albumIds", JSONArray().put(album)).put("type", "IMAGE").put("size", 250).put("page", page)
            val text = request("${immichBase()}/api/search/metadata", body.toString()) ?: break
            val items = JSONObject(text).optJSONObject("assets")?.optJSONArray("items") ?: break
            for (i in 0 until items.length()) result.add(Source.Immich(items.getJSONObject(i).getString("id")))
            if (items.length() < 250) break
            page++
        }
        return result
    }

    private fun request(address: String, postBody: String?): String? {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("x-api-key", prefs.immichKey.trim())
        connection.setRequestProperty("Accept", "application/json")
        if (postBody != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(postBody.toByteArray()) }
        }
        if (connection.responseCode !in 200..299) return null
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    /** Images d'un dossier choisi dans les réglages (et de ses sous-dossiers, sur 2 niveaux). */
    private fun localImages(tree: Uri, depth: Int = 0, parentId: String? = null): List<Source> {
        val result = ArrayList<Source>()
        val docId = parentId ?: DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val mime = cursor.getString(1) ?: ""
                when {
                    mime.startsWith("image/") -> result.add(Source.Local(DocumentsContract.buildDocumentUriUsingTree(tree, id)))
                    mime == DocumentsContract.Document.MIME_TYPE_DIR && depth < 2 -> result.addAll(localImages(tree, depth + 1, id))
                }
                if (result.size > 2_000) break
            }
        }
        return result
    }

    private fun decode(source: Source, width: Int, height: Int): Bitmap? {
        val bytes = when (source) {
            is Source.Immich -> {
                val connection = URL("${immichBase()}/api/assets/${source.id}/thumbnail?size=preview").openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("x-api-key", prefs.immichKey.trim())
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.use { it.readBytes() }
            }
            is Source.Local -> context.contentResolver.openInputStream(source.uri)?.use { it.readBytes() } ?: return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
