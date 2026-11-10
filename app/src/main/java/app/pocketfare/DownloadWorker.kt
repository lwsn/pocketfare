package app.pocketfare

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import pocketfare.core.ChallengeException
import pocketfare.core.Downloader
import pocketfare.core.LoginRequiredException
import pocketfare.core.sites.Sites
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val notifId = id.hashCode()
    /** Custom per-download settings, carried through to output so Retry/Update reuse them. */
    private val custom: String? = inputData.getString(Jobs.KEY_SETTINGS)

    private fun foreground(title: String, text: String, done: Int, total: Int) =
        ForegroundInfo(notifId, Notifier.progress(applicationContext, title, text, done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override suspend fun getForegroundInfo() = foreground("Downloading", "Starting…", 0, 0)

    override suspend fun doWork(): Result {
        val url = inputData.getString(Jobs.KEY_URL) ?: return Result.failure()
        try { setForeground(foreground("Downloading", url, 0, 0)) } catch (e: Exception) { /* run in background anyway */ }
        return withContext(Dispatchers.IO) { run(url) }
    }

    private suspend fun run(url: String): Result {
        val ctx = applicationContext
        var solved = false
        while (true) {
            try {
                return attempt(url)
            } catch (e: ChallengeException) {
                if (!solved && Net.solveHeadless(ctx, e.url)) { solved = true; continue }
                val pi = PendingIntent.getActivity(
                    ctx, notifId, WebActivity.challengeIntent(ctx, e.url, url, custom),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val msg = "${e.kind} check needs you. Tap to open it; the download restarts once it passes."
                Notifier.result(ctx, notifId + 1, "Verification needed", msg, pi, actionNeeded = true)
                return failure(url, msg, Jobs.ACTION_CHALLENGE, e.url)
            } catch (e: LoginRequiredException) {
                val host = Regex("^https?://([^/]+)").find(e.loginUrl)?.groupValues?.get(1) ?: ""
                Sites.loginSiteForHost(host)?.let { if (e.expired) App.sessions.remove(it.domain) }
                val pi = PendingIntent.getActivity(
                    ctx, notifId, WebActivity.loginIntent(ctx, e.loginUrl, url, custom),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                Notifier.result(ctx, notifId + 1, "Login needed", "${e.message} Tap to log in.", pi, actionNeeded = true)
                return failure(url, e.message ?: "Login needed", Jobs.ACTION_LOGIN, e.loginUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                Notifier.result(ctx, notifId + 1, "Download failed", "$url\n$msg")
                return failure(url, msg)
            }
        }
    }

    private suspend fun attempt(url: String): Result {
        val ctx = applicationContext
        val settings = custom?.let { runCatching { Settings.fromJson(it) }.getOrNull() } ?: Settings.load(ctx)
        val d = Downloader(Net.fetcher(ctx, settings.requestDelayMs), settings.runtimeOptions()) { App.sessions.has(it) }
        val adapter = d.adapter(url)
        setProgress(workDataOf(Jobs.KEY_STEP to "Reading index"))
        val story = adapter.loadMetadata()
        val tmp = File(ctx.cacheDir, "$id.epub")
        try {
            val res = tmp.outputStream().buffered().use { out ->
                d.download(adapter, story, out) { step, done, total ->
                    if (isStopped) throw CancellationException("Cancelled")
                    setProgressAsync(workDataOf(Jobs.KEY_TITLE to story.title, Jobs.KEY_STEP to step, Jobs.KEY_DONE to done, Jobs.KEY_TOTAL to total))
                    setForegroundAsync(foreground(story.title, "$done/$total  $step", done, total))
                }
            }
            val saved = Storage.save(ctx, tmp, Downloader.fileName(story, settings.fileNameStyle))
            val warn = if (res.failedChapters.isNotEmpty()) " (${res.failedChapters.size} chapters failed)" else ""
            Notifier.result(ctx, notifId + 1, story.title, "Saved ${story.chapters.size} chapters to $saved$warn")
            return Result.success(
                workDataOf(
                    Jobs.KEY_URL to url, Jobs.KEY_TITLE to story.title, Jobs.KEY_SAVED to saved,
                    Jobs.KEY_FAILED to res.failedChapters.size, Jobs.KEY_TOTAL to story.chapters.size,
                    Jobs.KEY_SETTINGS to custom,
                )
            )
        } finally {
            tmp.delete()
        }
    }

    private fun failure(url: String, msg: String, action: String? = null, actionUrl: String? = null) = Result.failure(
        workDataOf(
            Jobs.KEY_URL to url, Jobs.KEY_ERROR to msg, Jobs.KEY_ACTION to action, Jobs.KEY_ACTION_URL to actionUrl,
            Jobs.KEY_SETTINGS to custom,
        )
    )
}
