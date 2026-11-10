package pocketfare.core

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.security.MessageDigest

/**
 * Records live HTML responses to disk, or replays them with no network access, so adapters can be
 * re-tested offline after one live run. Only text/html responses are stored; in replay mode,
 * anything not on disk (e.g. images) gets a 404, so images fall back to alt text.
 *
 * File layout: <dir>/<host>/<slug>-<hash>.html, first lines are headers:
 *   URL: <final url after redirects>
 *   CODE: <status>
 *   <blank line>
 *   <body>
 */
class FixtureInterceptor(private val dir: File, private val replay: Boolean) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val file = fileFor(req)
        if (replay) {
            if (!file.exists()) {
                return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not recorded")
                    .body("".toResponseBody(null)).build()
            }
            val text = file.readText()
            val finalUrl = text.substringAfter("URL: ").substringBefore('\n')
            val code = text.substringAfter("CODE: ").substringBefore('\n').toInt()
            val body = text.substringAfter("\n\n")
            val finalReq = req.newBuilder().url(finalUrl).build()
            return Response.Builder().request(finalReq).protocol(Protocol.HTTP_1_1).code(code).message("Replayed")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }
        val resp = chain.proceed(req)
        val type = resp.body.contentType()
        if (type?.subtype != "html") return resp
        val body = resp.body.string()
        file.parentFile.mkdirs()
        file.writeText("URL: ${resp.request.url}\nCODE: ${resp.code}\n\n$body")
        return resp.newBuilder().body(body.toResponseBody(type)).build()
    }

    private fun fileFor(req: Request): File {
        val url = req.url.toString()
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)
        val slug = (req.url.encodedPath + (req.url.encodedQuery?.let { "_$it" } ?: ""))
            .replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').takeLast(80)
        return File(File(dir, req.url.host), "$slug-$hash.html")
    }
}
