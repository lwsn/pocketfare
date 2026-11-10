package app.pocketfare

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import pocketfare.core.Fetcher
import pocketfare.core.FileNameStyle
import pocketfare.core.Options
import pocketfare.core.SpoilerMode
import java.io.ByteArrayOutputStream
import pocketfare.core.detectChallenge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        sessions = SessionStore(this)
        Notifier.createChannels(this)
    }

    companion object {
        lateinit var sessions: SessionStore
    }
}

object Prefs {
    fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun outputTree(ctx: Context): Uri? = p(ctx).getString("tree", null)?.let(Uri::parse)
    fun setOutputTree(ctx: Context, uri: Uri?) = p(ctx).edit().putString("tree", uri?.toString()).apply()
}

/** All user settings, persisted as one SharedPreferences group. Defaults mirror [Options]. */
data class Settings(
    val options: Options = Options(),
    /** Longest image side in px; 0 = keep original (FFF image_max_size). */
    val imageMaxSize: Int = 0,
    val grayscaleImages: Boolean = false,              // grayscale_images
    val requestDelayMs: Long = 1500,                   // slow_down_sleep_time
    val fileNameStyle: FileNameStyle = FileNameStyle.TITLE_AUTHOR,
) {
    /** Options with the Android image pipeline attached. */
    fun runtimeOptions(): Options =
        if (imageMaxSize > 0 || grayscaleImages) options.copy(imageTransform = { Images.process(it, imageMaxSize, grayscaleImages) })
        else options

    /** Flat key/value form, shared by SharedPreferences storage and per-download JSON overrides. */
    fun toMap(): Map<String, Any> {
        val o = options
        return mapOf(
            "includeImages" to o.includeImages,
            "includeCover" to o.includeCover,
            "includeTitlePage" to o.includeTitlePage,
            "addChapterNumbers" to o.addChapterNumbers,
            "spoilers" to o.spoilers.name,
            "includeAuthorNotes" to o.includeAuthorNotes,
            "skipCategories" to o.skipThreadmarkCategories,
            "orderByDate" to o.orderThreadmarksByDate,
            "firstPost" to o.alwaysIncludeFirstPost,
            "apocryphaToOmake" to o.apocryphaToOmake,
            "revealInvisible" to o.revealInvisibleText,
            "nonAuthorPoster" to o.includeNonAuthorPoster,
            "imageMaxSize" to imageMaxSize,
            "grayscale" to grayscaleImages,
            "delayMs" to requestDelayMs,
            "fileName" to fileNameStyle.name,
        )
    }

    fun toJson(): String = JSONObject(toMap().mapValues { (_, v) -> if (v is Set<*>) JSONArray(v.toList()) else v }).toString()

    companion object {
        fun fromMap(m: Map<String, Any?>): Settings {
            val d = Settings()
            val o = d.options
            fun b(k: String, def: Boolean) = m[k] as? Boolean ?: def
            fun str(k: String) = m[k] as? String
            return Settings(
                options = Options(
                    includeImages = b("includeImages", o.includeImages),
                    includeCover = b("includeCover", o.includeCover),
                    includeTitlePage = b("includeTitlePage", o.includeTitlePage),
                    addChapterNumbers = b("addChapterNumbers", o.addChapterNumbers),
                    spoilers = runCatching { SpoilerMode.valueOf(str("spoilers")!!) }.getOrDefault(o.spoilers),
                    includeAuthorNotes = b("includeAuthorNotes", o.includeAuthorNotes),
                    skipThreadmarkCategories = (m["skipCategories"] as? Collection<*>)?.map { it.toString() }?.toSet()
                        ?: o.skipThreadmarkCategories,
                    orderThreadmarksByDate = b("orderByDate", o.orderThreadmarksByDate),
                    alwaysIncludeFirstPost = b("firstPost", o.alwaysIncludeFirstPost),
                    apocryphaToOmake = b("apocryphaToOmake", o.apocryphaToOmake),
                    revealInvisibleText = b("revealInvisible", o.revealInvisibleText),
                    includeNonAuthorPoster = b("nonAuthorPoster", o.includeNonAuthorPoster),
                ),
                imageMaxSize = (m["imageMaxSize"] as? Number)?.toInt() ?: d.imageMaxSize,
                grayscaleImages = b("grayscale", d.grayscaleImages),
                requestDelayMs = (m["delayMs"] as? Number)?.toLong() ?: d.requestDelayMs,
                fileNameStyle = runCatching { FileNameStyle.valueOf(str("fileName")!!) }.getOrDefault(d.fileNameStyle),
            )
        }

        fun fromJson(json: String): Settings {
            val j = JSONObject(json)
            return fromMap(j.keys().asSequence().associateWith { k ->
                when (val v = j.get(k)) {
                    is JSONArray -> (0 until v.length()).map { v.getString(it) }
                    else -> v
                }
            })
        }

        fun load(ctx: Context): Settings = fromMap(Prefs.p(ctx).all)

        fun save(ctx: Context, s: Settings) {
            val e = Prefs.p(ctx).edit()
            for ((k, v) in s.toMap()) when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is String -> e.putString(k, v)
                is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
            }
            e.apply()
        }
    }
}

object Images {
    /** Downscale to [maxSize] (longest side) and/or convert to grayscale. GIFs pass through (animation). */
    fun process(bytes: ByteArray, maxSize: Int, grayscale: Boolean): ByteArray {
        if (bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()) return bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return bytes
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val tooBig = maxSize in 1 until longest
        if (!tooBig && !grayscale) return bytes
        var sample = 1
        if (tooBig) while (longest / (sample * 2) >= maxSize) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return bytes
        if (tooBig && maxOf(bmp.width, bmp.height) > maxSize) {
            val scale = maxSize.toFloat() / maxOf(bmp.width, bmp.height)
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        }
        if (grayscale) {
            val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(bmp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
            bmp = out
        }
        val png = bounds.outMimeType == "image/png"
        return ByteArrayOutputStream().also {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, it)
        }.toByteArray()
    }
}

object Net {
    /** OkHttp must present the same UA as the WebView, or bot-check cookies are rejected. */
    fun fetcher(ctx: Context, delayMs: Long) =
        Fetcher(Fetcher.defaultClient(AppCookieJar(App.sessions)), WebSettings.getDefaultUserAgent(ctx), delayMs)

    fun isChallenge(html: String) = detectChallenge(403, html) != null

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(wv: WebView) {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    }

    suspend fun html(wv: WebView): String = suspendCancellableCoroutine { c ->
        wv.evaluateJavascript("document.documentElement.outerHTML") { r ->
            c.resume(if (r == null || r == "null") "" else JSONArray("[$r]").getString(0))
        }
    }

    /**
     * Try to pass a bot check in an invisible WebView. Anubis (proof-of-work) and most Cloudflare
     * checks complete on their own in a real browser engine. Returns true when the real page loads.
     */
    suspend fun solveHeadless(ctx: Context, url: String, timeoutMs: Long = 30_000): Boolean =
        withContext(Dispatchers.Main) {
            val wv = WebView(ctx.applicationContext)
            configure(wv)
            try {
                wv.loadUrl(url)
                withTimeoutOrNull(timeoutMs) {
                    var ok = false
                    while (!ok) {
                        delay(1000)
                        if (wv.progress == 100) {
                            val h = html(wv)
                            ok = h.length > 1000 && !isChallenge(h)
                        }
                    }
                    true
                } ?: false
            } finally {
                CookieManager.getInstance().flush()
                wv.destroy()
            }
        }
}

object Jobs {
    const val TAG = "download"
    const val KEY_URL = "url"
    const val KEY_TITLE = "title"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_STEP = "step"
    const val KEY_SAVED = "saved"
    const val KEY_ERROR = "error"
    const val KEY_FAILED = "failed"
    /** On failure: "challenge" or "login" when the user can fix it in [WebActivity]. */
    const val KEY_ACTION = "action"
    const val KEY_ACTION_URL = "actionUrl"
    const val ACTION_CHALLENGE = "challenge"
    const val ACTION_LOGIN = "login"

    /** Per-download settings JSON (from "EPUB with options…"); absent = use saved defaults. */
    const val KEY_SETTINGS = "settings"

    fun enqueue(ctx: Context, url: String, settingsJson: String? = null) {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(KEY_URL to url, KEY_SETTINGS to settingsJson))
            .addTag(TAG)
            .addTag("url:$url")
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // Re-sharing the same URL while it's still running is a no-op; afterwards it re-downloads (= update).
        WorkManager.getInstance(ctx).enqueueUniqueWork("dl:$url", ExistingWorkPolicy.KEEP, req)
    }
}

object Storage {
    const val DEFAULT_DIR = "Download/PocketFare"

    /** Copy [src] to the chosen folder (or Downloads/PocketFare), replacing a same-named file. Returns a display path. */
    fun save(ctx: Context, src: File, name: String): String {
        Prefs.outputTree(ctx)?.let { tree ->
            val dir = DocumentFile.fromTreeUri(ctx, tree)
            if (dir != null && dir.canWrite()) {
                dir.findFile(name)?.delete()
                val f = dir.createFile("application/epub+zip", name.removeSuffix(".epub"))
                    ?: error("Couldn't create $name in ${dir.name}")
                ctx.contentResolver.openOutputStream(f.uri, "w")!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                return "${dir.name}/${f.name}"
            }
        }
        val resolver = ctx.contentResolver
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.delete(
            coll, "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, "$DEFAULT_DIR/")
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(coll, values) ?: error("Couldn't create $name in Downloads")
        resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "$DEFAULT_DIR/$name"
    }
}

object Notifier {
    const val PROGRESS = "progress"
    // Channel settings are immutable once created, so changing sound/importance needs a new id.
    const val RESULTS = "results_silent"
    const val ACTION = "action_needed"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("results") // v0.1 channel, had the default sound
        nm.deleteNotificationChannel("action_silent") // briefly-used silent variant
        nm.createNotificationChannel(NotificationChannel(PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW))
        // Silent but still in the shade/status bar. Users can enable sound per channel in system settings.
        fun silent(id: String, name: String) = NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(silent(RESULTS, "Finished downloads"))
        // Needs the user, so it plays the system's default notification sound (once, see setOnlyAlertOnce).
        nm.createNotificationChannel(NotificationChannel(ACTION, "Login / verification needed", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun progress(ctx: Context, title: String, text: String, done: Int, total: Int) =
        NotificationCompat.Builder(ctx, PROGRESS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .setContentIntent(openApp(ctx))
            .build()

    fun result(ctx: Context, id: Int, title: String, text: String, intent: PendingIntent? = null, actionNeeded: Boolean = false) {
        val n = NotificationCompat.Builder(ctx, if (actionNeeded) ACTION else RESULTS)
            .setSilent(!actionNeeded)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(intent ?: openApp(ctx))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: SecurityException) {
            // Notification permission denied; the in-app list still shows the result.
        }
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
