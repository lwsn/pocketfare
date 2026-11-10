package pocketfare.core.sites

// Port of FanFicFare's base_xenforo2forum_adapter.py (Apache-2.0). See NOTICE and docs/ADAPTERS.md.

import pocketfare.core.Chapter
import pocketfare.core.Fetcher
import pocketfare.core.HttpStatusException
import pocketfare.core.LoginRequiredException
import pocketfare.core.Options
import pocketfare.core.SiteAdapter
import pocketfare.core.SpoilerMode
import pocketfare.core.Story
import pocketfare.core.UnsupportedSiteException
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.Instant

class XenForoSite(
    val name: String,
    val domain: String,
    val aliases: List<String>,
    val abbrev: String,
    /** Path prefix of the forum, "/" for most, "/forum/" for AlternateHistory. */
    val prefix: String = "/",
    val threadmarksPerPage: Int = 200,
    val readerPostsPerPage: Int = 10,
) {
    val base get() = "https://$domain$prefix"
    val loginUrl get() = "${base}login/"
    val hosts get() = listOf(domain) + aliases
}

object XenForoSites {
    val all = listOf(
        XenForoSite("SpaceBattles", "forums.spacebattles.com", listOf("forum.spacebattles.com", "spacebattles.com"), "fsb"),
        XenForoSite(
            "Sufficient Velocity", "forums.sufficientvelocity.com",
            listOf("forum.sufficientvelocity.com", "sufficientvelocity.com"), "fsv"
        ),
        XenForoSite(
            "Questionable Questing", "forum.questionablequesting.com", listOf("questionablequesting.com"), "fqq",
            threadmarksPerPage = 400, readerPostsPerPage = 30
        ),
        XenForoSite("AlternateHistory", "www.alternatehistory.com", listOf("alternatehistory.com"), "ah", prefix = "/forum/"),
    )

    fun forUrl(url: String): XenForoSite? {
        val host = Regex("^https?://([^/:?#]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase()
            ?: return null
        return all.firstOrNull { host in it.hosts }
    }
}

class XenForo2Adapter(
    private val site: XenForoSite,
    inputUrl: String,
    private val f: Fetcher,
    private val opts: Options,
    /** True when the app holds a saved login for this site; a logged-out page then means it expired. */
    private val expectLoggedIn: Boolean,
) : SiteAdapter {

    private val threadId: String?
    private val postId: String?

    /** Canonical "https://host/prefix/threads/slug.123/" once loaded. */
    private lateinit var storyUrl: String
    private val postCache = HashMap<String, Element>()          // "post-123" -> article
    private val readerIndex = HashMap<String, Pair<String, Int>>() // post id -> (category num, index in category)
    private var reader = false

    init {
        val path = Regex("^https?://[^/]+(/.*)?$", RegexOption.IGNORE_CASE).find(inputUrl)?.groupValues?.get(1) ?: "/"
        val p = Regex.escape(site.prefix)
        // threads/slug.123/... , threads/123/... , posts/456/ , threads/slug.123/post-456 , ...#post-456
        val thread = Regex("^${p}threads/(?:[^/]*\\.)?(\\d+)(?:/|$)").find(path)
        val post = Regex("^${p}posts/(\\d+)").find(path)
        threadId = thread?.groupValues?.get(1)
        postId = post?.groupValues?.get(1)
        if (threadId == null && postId == null) throw UnsupportedSiteException(inputUrl)
    }

    // ---------------------------------------------------------------- fetching / parsing

    private fun fetchSoup(url: String): Document {
        val r = try {
            f.get(url)
        } catch (e: HttpStatusException) {
            // QQ (and others for restricted subforums) answer 403 when login is needed.
            if (e.code == 403) throw LoginRequiredException(site.name, site.loginUrl, expectLoggedIn)
            throw e
        }
        val doc = Jsoup.parse(r.body, r.url)
        if (expectLoggedIn && doc.selectFirst("html[data-logged-in=false]") != null) {
            throw LoginRequiredException(site.name, site.loginUrl, expired = true)
        }
        prepare(doc)
        return doc
    }

    /** Equivalent of FFF's make_soup() cleanup plus post caching. */
    private fun prepare(doc: Document) {
        for (img in doc.select("img.lazyload")) {
            when {
                img.hasAttr("data-src") -> img.attr("src", img.attr("data-src"))
                img.hasAttr("data-url") -> img.attr("src", img.attr("data-url"))
            }
        }
        doc.select("noscript, iframe").remove()
        doc.select("div[class~=bbCodeBlock-(expand|shrink)Link]").remove()
        for (a in doc.select("a.__cf_email__[data-cfemail]")) {
            a.replaceWith(TextNode(decodeCfEmail(a.attr("data-cfemail"))))
        }
        for (d in doc.select("div.bbCodeBlock-expandContent")) d.tagName("blockquote")
        // Spoilers: BOX -> <fieldset><legend>title</legend>content</fieldset> (FFF legend_spoilers)
        for (div in doc.select("div.bbCodeSpoiler")) {
            when (opts.spoilers) {
                SpoilerMode.REMOVE -> div.remove()
                SpoilerMode.INLINE -> div.select("button").remove()
                SpoilerMode.BOX -> {
                    val title = div.selectFirst("button span")?.text() ?: "Spoiler"
                    div.select("button").remove()
                    div.tagName("fieldset").addClass("bbCodeSpoilerContainer")
                    div.prependElement("legend").text(title)
                }
            }
        }
        for (post in doc.select("article.message--post")) {
            val key = post.attr("data-content")
            if (key.isNotEmpty()) postCache[key] = post
        }
    }

    private fun postBody(post: Element): Element? =
        post.selectFirst("article.message-body div.bbWrapper") ?: post.selectFirst("div.bbWrapper")

    private fun firstPost(doc: Document): Element? {
        val posts = doc.select("article.message--post").take(3)
        return posts.firstOrNull { !it.hasClass("sticky-container") } ?: posts.firstOrNull()
    }

    private fun timeOf(el: Element?): Instant? {
        val t = el?.selectFirst("time") ?: return null
        val s = t.attr("data-timestamp").ifEmpty { t.attr("data-time") }
        return s.toDoubleOrNull()?.let { Instant.ofEpochSecond(it.toLong()) }
    }

    private fun abs(href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("/") -> "https://${site.domain}$href"
        else -> site.base + href
    }

    /** Post id from any post-ish URL, or null if it isn't a link to a post on this site. */
    private fun postIdOf(url: String): String? {
        val u = abs(url.trim('\'', '"'))
        val host = Regex("^https?://([^/]+)").find(u)?.groupValues?.get(1)?.lowercase() ?: return null
        if (host !in site.hosts) return null
        Regex("#post-(\\d+)").find(u)?.let { return it.groupValues[1] }
        Regex("/posts/(\\d+)").find(u)?.let { return it.groupValues[1] }
        Regex("/threads/[^/]*/post-(\\d+)").find(u)?.let { return it.groupValues[1] }
        Regex("reply\\?quote=(\\d+)").find(u)?.let { return it.groupValues[1] }
        Regex("goto/post\\?id=(\\d+)").find(u)?.let { return it.groupValues[1] }
        return null
    }

    // ---------------------------------------------------------------- metadata

    override fun loadMetadata(): Story {
        // A posts/ URL redirects to the thread page containing it; we always download the whole thread.
        val start = if (threadId != null) "${site.base}threads/$threadId/" else "${site.base}posts/$postId/"
        var doc = fetchSoup(start)
        val canonical = Regex("^(https://[^/]+${Regex.escape(site.prefix)}threads/[^/]+/)").find(doc.location())
            ?.groupValues?.get(1) ?: throw UnsupportedSiteException(doc.location())
        storyUrl = canonical
        val tid = Regex("\\.(\\d+)/$").find(canonical)?.groupValues?.get(1)
            ?: Regex("/threads/(\\d+)/$").find(canonical)?.groupValues?.get(1) ?: threadId ?: postId!!
        if (doc.location() != canonical) doc = fetchSoup(canonical) // landed mid-thread; need page 1

        val story = Story(canonical, site.name, site.abbrev, tid)

        val h1 = doc.selectFirst("h1.p-title-value")
        h1?.select("span.label")?.forEach { story.tags.add(it.text()); it.remove() }
        story.title = h1?.text().orEmpty().ifEmpty { "Thread $tid" }

        val first = firstPost(doc) ?: throw HttpStatusException(canonical, 404)
        val userA = first.selectFirst("section.message-user a.username")
        if (userA != null) {
            story.authors.add(userA.text())
            story.authorUrl = userA.absUrl("href")
        } else {
            story.authors.add(first.selectFirst("section.message-user span.username")?.text() ?: "Unknown")
        }

        for (tag in doc.select("div.p-description a.tagItem")) {
            tag.select("title").remove()
            story.tags.add(tag.text().replace(Regex("\\s+"), " "))
        }

        val threadmarks = extractThreadmarks(doc, story)
        if (threadmarks.isNotEmpty()) {
            reader = doc.selectFirst("a[href~=\\.$tid(/\\d+)?/reader/?$]") != null
            if (opts.alwaysIncludeFirstPost) {
                val firstId = first.attr("data-content").removePrefix("post-")
                if (threadmarks.first().postId != firstId) {
                    story.chapters.add(Chapter("First Post", "${site.base}posts/$firstId/", firstId))
                }
            }
            for (tm in threadmarks) {
                story.noteDate(tm.date)
                val prefix = if (tm.catName != "Threadmarks") "${tm.catName} - " else ""
                story.chapters.add(Chapter(prefix + tm.title, "${site.base}posts/${tm.postId}/", tm.postId))
                readerIndex[tm.postId] = tm.catNum to tm.catIndex
            }
            setThreadmarksMetadata(doc, story)
        } else {
            // No threadmarks: first post, plus any post links inside it (old-style index posts).
            story.noteDate(timeOf(first.selectFirst("div.message-attribution-main")))
            story.noteDate(timeOf(first.selectFirst("div.message-lastEdit")))
            val firstId = first.attr("data-content").removePrefix("post-")
            story.chapters.add(Chapter("First Post", "${site.base}posts/$firstId/", firstId))
            val seen = mutableSetOf(firstId)
            postBody(first)?.select("a[href]")?.forEach { a ->
                if (a.text() == "↑" || a.attr("data-xf-click") == "attribution") return@forEach
                val pid = postIdOf(a.attr("href")) ?: return@forEach
                if (seen.add(pid)) story.chapters.add(Chapter(a.text(), "${site.base}posts/$pid/", pid))
            }
        }

        if (story.description == null) {
            postBody(first)?.text()?.let { story.description = it.take(1000) }
        }
        return story
    }

    private class Threadmark(
        val catName: String, val catNum: String, val catIndex: Int,
        val title: String, val postId: String, val date: Instant?,
    )

    private var threadmarkHeaderDoc: Document? = null

    private fun extractThreadmarks(doc: Document, story: Story): List<Threadmark> {
        val nav = doc.selectFirst("div.block-outer-recent-threadmarks") ?: return emptyList()
        val groups = LinkedHashMap<String, List<Threadmark>>()
        for (a in nav.select("a.menuTrigger[href*=threadmarks]")) {
            val href = a.attr("href")
            val catNum = href.substringAfter("threadmark_category=", "1").substringBefore('&')
            var catName = a.text()
            if (catName in opts.skipThreadmarkCategories) continue
            if (catName == "Apocrypha" && opts.apocryphaToOmake) catName = "Omake"
            groups[catName] = fetchThreadmarks(abs(href), catName, catNum, 0, mutableSetOf(), true)
        }
        val order = listOf("Threadmarks", "Sidestory", "Apocrypha", "Omake", "Media", "Informational", "Staff Post")
        val out = mutableListOf<Threadmark>()
        for (c in order) groups.remove(c)?.let { out.addAll(it) }
        for (c in groups.keys.sorted()) out.addAll(groups.getValue(c))
        if (opts.orderThreadmarksByDate) out.sortBy { it.date ?: Instant.MAX }
        return out
    }

    private fun fetchThreadmarks(
        url0: String, catName: String, catNum: String, startIndex: Int,
        dedup: MutableSet<String>, firstPage: Boolean,
    ): List<Threadmark> {
        if (!dedup.add(url0)) return emptyList()
        val url = if ("per_page" in url0) url0
        else url0 + (if ('?' in url0) "&" else "?") + "per_page=${site.threadmarksPerPage}"
        val soup = fetchSoup(url)
        if (firstPage && catNum == "1" && threadmarkHeaderDoc == null) threadmarkHeaderDoc = soup

        var list: Element = soup.selectFirst("div.structItemContainer") ?: soup
        if (list !== soup) {
            // SV quirk: the "..." fetcher sometimes sits right after the container; move it in.
            list.nextElementSibling()?.takeIf { it.hasClass("structItem--threadmark") }?.let { list.appendChild(it) }
        }
        val out = mutableListOf<Threadmark>()
        var idx = startIndex
        for (item in list.select("div.structItem--threadmark")) {
            val a = item.selectFirst("a[data-tp-primary=on]")
            if (a == null) {
                // Collapsed range ("..."): fetch it.
                val fetch = item.selectFirst("div[data-xf-click=threadmark-fetcher]")?.attr("data-fetchurl") ?: continue
                val more = fetchThreadmarks(abs(fetch), catName, catNum, idx, dedup, false)
                out.addAll(more)
                idx += more.size
            } else {
                val pid = postIdOf(a.attr("href")) ?: continue
                out.add(Threadmark(catName, catNum, idx, a.text(), pid, timeOf(item)))
                idx++
            }
        }
        if (firstPage) {
            val pages = soup.select("ul.pageNav-main li.pageNav-page a")
            val m = pages.lastOrNull()?.attr("href")?.let { Regex("^(.*page=)(\\d+)$").find(it) }
            if (m != null) {
                for (j in 2..m.groupValues[2].toInt()) {
                    val more = fetchThreadmarks(abs(m.groupValues[1] + j), catName, catNum, startIndex + out.size, dedup, false)
                    out.addAll(more)
                }
            }
        }
        return out
    }

    private fun setThreadmarksMetadata(threadDoc: Document, story: Story) {
        val header = threadmarkHeaderDoc?.selectFirst("div.threadmarkListingHeader")
            ?: threadDoc.selectFirst("div.threadmarkListingHeader")
        if (header != null) {
            header.selectFirst("article.message-body div.bbWrapper")?.text()?.takeIf { it.isNotBlank() }
                ?.let { story.description = it.take(1000) }
            val statusDt = header.select("dt").firstOrNull { it.text() == "Index progress" || it.text() == "Status" }
            statusDt?.nextElementSibling()?.text()?.let { s ->
                story.status = when {
                    "Incomplete" in s || "Ongoing" in s -> "In-Progress"
                    "Complete" in s -> "Completed"
                    else -> s
                }
            }
            header.selectFirst("span.threadmarkListingHeader-icon img")?.let { story.coverUrl = it.absUrl("src") }
        }
        threadDoc.selectFirst("span.collapseTrigger.collapseTrigger--block")?.text()?.let { t ->
            Regex(" ([^ ]+) words\\)").find(t)?.let { story.words = it.groupValues[1] }
        }
    }

    // ---------------------------------------------------------------- chapters

    override fun loadChapter(story: Story, index: Int): Element {
        val ch = story.chapters[index]
        val pid = ch.key ?: postIdOf(ch.url) ?: error("No post id for ${ch.url}")
        val key = "post-$pid"

        var post = postCache[key]
        // Reader mode returns threadmarked posts in order, N per page: far fewer requests than one per post.
        if (post == null && reader) {
            val (cat, idx) = readerIndex[pid] ?: (null to 0)
            if (cat != null) {
                for (offset in intArrayOf(0, 1, -1, 2, -2)) {
                    val page = (idx + site.readerPostsPerPage) / site.readerPostsPerPage + offset
                    if (page < 1) continue
                    fetchSoup("${storyUrl}reader/page-$page?threadmark_category=$cat")
                    post = postCache[key]
                    if (post != null) break
                }
            }
        }
        if (post == null) {
            fetchSoup("${site.base}posts/$pid/")
            post = postCache[key]
        }
        post ?: throw HttpStatusException(ch.url, 404)

        post.select("div.noticeContent").remove()
        val body = postBody(post) ?: throw HttpStatusException(ch.url, 404)
        // Drop posts from the cache once used to keep memory flat on long threads.
        postCache.remove(key)
        if (opts.includeNonAuthorPoster) {
            val poster = post.selectFirst("div.message-userDetails a.username, h4.message-name a.username")?.text()
            if (poster != null && poster !in story.authors) {
                body.prependElement("p").addClass("poster").text("Chapter by: $poster")
            }
        }
        if (opts.revealInvisibleText) {
            for (span in body.select("span[style~=color:\\s*transparent]")) span.removeAttr("style").addClass("invisible_text")
        }
        handleEmbeds(body)
        replaceSmilies(body)
        return body
    }

    /** s9e media embeds -> link with thumbnail (FFF link_embedded_media). */
    private fun handleEmbeds(body: Element) {
        for (tag in body.select("[data-s9e-mediaembed-iframe]")) {
            val arr = try { JSONArray(tag.attr("data-s9e-mediaembed-iframe")) } catch (e: Exception) { continue }
            val m = HashMap<String, String>()
            var i = 0
            while (i + 1 < arr.length()) { m[arr.optString(i)] = arr.optString(i + 1); i += 2 }
            var src = m["src"] ?: continue
            if ("/embed/" in src) src = "https://youtu.be" + src.substring(src.lastIndexOf('/'))
            val a = Element("a").attr("href", src).text(src)
            val bg = m["style"]?.let { Regex("url\\(['\"]?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1) }
            if (bg != null) { a.text(""); a.appendElement("img").attr("src", bg).attr("alt", src) }
            tag.replaceWith(a)
        }
    }

    /** Sprite/failed smilies become their alt text (FFF replace_failed_smilies_with_alt_text). */
    private fun replaceSmilies(body: Element) {
        for (img in body.select("img.smilie, img.mceSmilie, img[src^=data:image]")) {
            val alt = img.attr("alt").replace("`", "")
            if (alt.isNotEmpty()) img.replaceWith(TextNode(alt)) else img.remove()
        }
    }

    companion object {
        fun decodeCfEmail(e: String): String = try {
            val k = e.substring(0, 2).toInt(16)
            (2 until e.length - 1 step 2).map { (e.substring(it, it + 2).toInt(16) xor k).toChar() }.joinToString("")
        } catch (ex: Exception) {
            "[email]"
        }
    }
}
