package pocketfare.core

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class FetchResult(val url: String, val code: Int, val body: String)

open class FetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A bot check (Cloudflare, Anubis) blocked the request. A real browser (WebView) must pass it first. */
class ChallengeException(val url: String, val kind: String) : FetchException("$kind bot check blocked $url")

class LoginRequiredException(val siteName: String, val loginUrl: String, val expired: Boolean) :
    FetchException(
        if (expired) "Your $siteName login has expired. Log in again."
        else "$siteName requires login for this story."
    )

class HttpStatusException(val url: String, val code: Int) : FetchException("HTTP $code for $url")

class UnsupportedSiteException(url: String) : FetchException("Unsupported site: $url")

/** Returns the bot-check kind if [body] is a challenge page, else null. */
fun detectChallenge(code: Int, body: String): String? {
    // Anubis markers are specific script/element ids and paths, so plain story text won't trigger them.
    if (body.contains("id=\"anubis_challenge\"") || body.contains("/.within.website/x/cmd/anubis/") ||
        body.contains("id=\"anubis_version\"")
    ) return "Anubis"
    if ((code == 403 || code == 503 || code == 429) &&
        (body.contains("cf-chl") || body.contains("challenge-platform") || body.contains("<title>Just a moment"))
    ) return "Cloudflare"
    return null
}

/**
 * Blocking HTTP helper shared by all adapters: per-host throttling, retries, and bot-check detection.
 * The cookie jar and user agent are supplied by the host (the Android app shares cookies with
 * WebView so challenge/login cookies apply here too).
 */
class Fetcher(
    private val client: OkHttpClient,
    val userAgent: String,
    private val minDelayMs: Long = 1500,
) {
    private val lastRequest = HashMap<String, Long>()

    private fun throttle(host: String, delay: Long) {
        val wait: Long
        synchronized(lastRequest) {
            val now = System.currentTimeMillis()
            val last = lastRequest[host] ?: 0L
            wait = (last + delay - now).coerceAtLeast(0)
            lastRequest[host] = now + wait
        }
        if (wait > 0) Thread.sleep(wait)
    }

    private fun request(url: String, accept: String, referer: String? = null): Request {
        val b = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
        if (referer != null) b.header("Referer", referer)
        return b.build()
    }

    /** GET an HTML page. Throws [ChallengeException], [HttpStatusException] or [FetchException]. */
    fun get(url: String): FetchResult {
        val req = request(url, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        var attempt = 0
        while (true) {
            attempt++
            throttle(req.url.host, minDelayMs)
            try {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    detectChallenge(resp.code, body)?.let { throw ChallengeException(url, it) }
                    if ((resp.code == 429 || resp.code in 500..599) && attempt < 4) {
                        val retryAfter = resp.header("Retry-After")?.toLongOrNull()?.coerceAtMost(60)
                        Thread.sleep((retryAfter ?: (5L * attempt)) * 1000)
                        return@use null
                    }
                    if (!resp.isSuccessful) throw HttpStatusException(url, resp.code)
                    return FetchResult(resp.request.url.toString(), resp.code, body)
                }
            } catch (e: IOException) {
                if (attempt >= 3) throw FetchException("Network error for $url: ${e.message}", e)
                Thread.sleep(2000L * attempt)
            }
        }
    }

    /** GET binary content (images). Returns null on any failure; images are best-effort. */
    fun getBytes(url: String, referer: String?, maxBytes: Long = 10L * 1024 * 1024): ByteArray? {
        val req = try {
            request(url, "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.5", referer)
        } catch (e: IllegalArgumentException) {
            return null
        }
        repeat(2) { attempt ->
            throttle(req.url.host, 300)
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val body = resp.body
                    if (body.contentLength() > maxBytes) return null
                    val bytes = body.source().let { src ->
                        src.request(maxBytes + 1)
                        src.buffer.readByteArray(minOf(src.buffer.size, maxBytes + 1))
                    }
                    return if (bytes.size > maxBytes) null else bytes
                }
            } catch (e: IOException) {
                if (attempt == 1) return null
            }
        }
        return null
    }

    companion object {
        fun defaultClient(cookieJar: CookieJar, vararg interceptors: okhttp3.Interceptor): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .apply { interceptors.forEach { addInterceptor(it) } }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/** Simple in-memory cookie jar (CLI / tests). */
class MemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            store.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
}
