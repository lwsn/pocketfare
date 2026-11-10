package pocketfare.core

import pocketfare.core.sites.Sites
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import java.io.OutputStream

data class DownloadResult(val story: Story, val failedChapters: List<String>, val images: Int)

fun interface Progress {
    /** Called before each step. Throw (e.g. CancellationException) to abort. */
    fun update(message: String, done: Int, total: Int)
}

class Downloader(
    private val f: Fetcher,
    private val opts: Options = Options(),
    private val hasLogin: (domain: String) -> Boolean = { false },
) {
    /** Fetch metadata only (for naming the output file before downloading). */
    fun adapter(url: String): SiteAdapter =
        Sites.adapterFor(url, f, opts, hasLogin) ?: throw UnsupportedSiteException(url)

    fun download(adapter: SiteAdapter, story: Story, out: OutputStream, progress: Progress): DownloadResult {
        val epub = EpubWriter(out)
        val images = ImageCollector(epub)
        val failed = mutableListOf<String>()
        val total = story.chapters.size

        if (opts.includeCover) story.coverUrl?.let { url ->
            progress.update("Cover", 0, total)
            f.getBytes(url, story.sourceUrl)?.let(::transform)
                ?.let { b -> imageType(b)?.let { (ext, mime) -> epub.setCover("images/cover.$ext", b, mime) } }
        }

        for (i in 0 until total) {
            val ch = story.chapters[i]
            val title = if (opts.addChapterNumbers) "${i + 1}. ${ch.title}" else ch.title
            progress.update(ch.title, i, total)
            val el = try {
                adapter.loadChapter(story, i)
            } catch (e: ChallengeException) {
                throw e
            } catch (e: LoginRequiredException) {
                throw e
            } catch (e: Exception) {
                // One retry, then a placeholder so a single bad post doesn't sink a 300-chapter thread.
                // Bot checks / login walls must still surface from the retry, not become placeholders.
                try {
                    adapter.loadChapter(story, i)
                } catch (e2: ChallengeException) {
                    throw e2
                } catch (e2: LoginRequiredException) {
                    throw e2
                } catch (e2: Exception) {
                    null
                }
            }
            if (el == null) {
                failed.add(ch.title)
                epub.addChapter(title, "<p><em>Failed to download this chapter: ${EpubWriter.esc(ch.url)}</em></p>")
                continue
            }
            epub.addChapter(title, toXhtml(el, images))
        }
        progress.update("Finishing", total, total)
        epub.finish(story, titlePage = opts.includeTitlePage)
        return DownloadResult(story, failed, images.count)
    }

    private inner class ImageCollector(val epub: EpubWriter) {
        val byUrl = HashMap<String, String?>()
        var count = 0
        var totalBytes = 0L

        /** Returns the in-book href, or null if the image couldn't be included. */
        fun include(url: String, referer: String): String? = byUrl.getOrPut(url) {
            if (totalBytes > MAX_TOTAL_IMAGE_BYTES) return@getOrPut null
            val bytes = f.getBytes(url, referer)?.let(::transform) ?: return@getOrPut null
            val (ext, mime) = imageType(bytes) ?: return@getOrPut null
            count++
            totalBytes += bytes.size
            val href = "images/img%04d.%s".format(count, ext)
            epub.addImage(href, bytes, mime)
            href
        }
    }

    private fun transform(b: ByteArray): ByteArray? = opts.imageTransform?.let { t ->
        try { t(b) } catch (e: Exception) { b }
    } ?: b

    /** Clone + sanitize a content element into a well-formed XHTML fragment. */
    private fun toXhtml(src: Element, images: ImageCollector): String {
        val base = src.baseUri()
        val doc = Document.createShell(base)
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(false)
        val el = src.clone()
        doc.body().appendChild(el)

        el.select("script, style, form, input, button, textarea, select, iframe, object, embed, video, audio, svg, link, meta").remove()
        for (e in el.getAllElements()) {
            val drop = e.attributes().asList().map { it.key }.filter { k ->
                k.startsWith("on") || k.startsWith("data-") || k.startsWith("aria-") || ':' in k ||
                    k in setOf("loading", "srcset", "sizes", "decoding", "role", "tabindex", "itemprop", "itemscope", "itemtype", "contenteditable", "draggable", "target", "rel")
            }
            drop.forEach { e.removeAttr(it) }
        }
        for (a in el.select("a[href]")) {
            val h = a.attr("href")
            if (h.startsWith("javascript:")) a.removeAttr("href") else a.attr("href", a.absUrl("href").ifEmpty { h })
        }
        for (img in el.select("img")) {
            val url = img.absUrl("src")
            val href = if (opts.includeImages && url.startsWith("http")) images.include(url, base) else null
            if (href != null) {
                img.attr("src", href)
                if (!img.hasAttr("alt")) img.attr("alt", "")
            } else {
                val alt = img.attr("alt")
                if (alt.isNotBlank()) img.replaceWith(TextNode("[$alt]")) else img.remove()
            }
        }
        // XHTML has no <center>/<font>; readers mostly cope, but keep the output valid.
        el.select("center").forEach { it.tagName("div").attr("style", "text-align:center") }
        el.select("font").forEach { it.unwrap() }
        return el.outerHtml().replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
    }

    companion object {
        const val MAX_TOTAL_IMAGE_BYTES = 150L * 1024 * 1024

        /** (extension, media type) by magic bytes; null for unsupported formats. */
        fun imageType(b: ByteArray): Pair<String, String>? {
            fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xff else -1
            return when {
                at(0) == 0xFF && at(1) == 0xD8 -> "jpg" to "image/jpeg"
                at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png" to "image/png"
                at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "gif" to "image/gif"
                b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp" to "image/webp"
                else -> null
            }
        }

        /** Filesystem-safe output name. */
        fun fileName(story: Story, style: FileNameStyle = FileNameStyle.TITLE_AUTHOR): String {
            val raw = when (style) {
                FileNameStyle.TITLE_AUTHOR -> "${story.title} - ${story.authors.firstOrNull() ?: story.siteName}"
                FileNameStyle.FFF -> "${story.title}-${story.siteAbbrev}_${story.storyId}"
            }
            val clean = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").replace(Regex("\\s+"), " ").trim().take(150)
            return "$clean.epub"
        }
    }
}
