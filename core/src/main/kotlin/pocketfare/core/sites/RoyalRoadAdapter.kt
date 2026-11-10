package pocketfare.core.sites

// Port of FanFicFare's adapter_royalroadcom.py (Apache-2.0), redesign-UI path only.
// See NOTICE and docs/ADAPTERS.md.

import pocketfare.core.Chapter
import pocketfare.core.Fetcher
import pocketfare.core.HttpStatusException
import pocketfare.core.Options
import pocketfare.core.SiteAdapter
import pocketfare.core.SpoilerMode
import pocketfare.core.Story
import pocketfare.core.UnsupportedSiteException
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneOffset

class RoyalRoadAdapter(inputUrl: String, private val f: Fetcher, private val opts: Options) : SiteAdapter {

    private val storyId: String = URL_RE.find(inputUrl)?.groupValues?.get(1) ?: throw UnsupportedSiteException(inputUrl)
    private val storyUrl = "https://$DOMAIN/fiction/$storyId"

    override fun loadMetadata(): Story {
        val r = f.get(storyUrl)
        val doc = Jsoup.parse(r.body, r.url)
        val story = Story(storyUrl, "Royal Road", "rylrdl", storyId)

        val ld = doc.selectFirst("script[type=application/ld+json]")?.data()
            ?: throw HttpStatusException(storyUrl, 404)
        val json = JSONObject(ld)
        story.title = json.optString("name")
        json.optJSONObject("author")?.let {
            story.authors.add(it.optString("name"))
            story.authorUrl = it.optString("url").ifEmpty { null }
        }
        story.description = Jsoup.parse(json.optString("description")).text().ifEmpty { null }
        story.coverUrl = json.optString("image").ifEmpty { null }
        parseDate(json.optString("datePublished"))?.let { story.datePublished = it }
        parseDate(json.optString("dateModified"))?.let { story.dateUpdated = it }

        // Chapter list is embedded as `window.chapters = [...];`
        val js = Regex("^ *window\\.chapters *= *(.*?) *; *$", RegexOption.MULTILINE).find(r.body)?.groupValues?.get(1)
        if (js != null) {
            val arr = JSONArray(js)
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                // Skip premium/patreon-locked chapters.
                val tiers = c.opt("subscriptionTiers")
                if (tiers != null && tiers != JSONObject.NULL && !(tiers is JSONArray && tiers.length() == 0)) continue
                story.chapters.add(Chapter(c.optString("title"), "https://$DOMAIN" + c.optString("url"), c.optString("id")))
                parseDate(c.optString("date"))?.let { d ->
                    if (story.dateUpdated == null || d > story.dateUpdated) story.dateUpdated = d
                }
            }
        }
        if (story.chapters.isEmpty()) throw HttpStatusException("$storyUrl (no chapters found)", 404)

        for (a in doc.select("div#chapterHeroData a[href^=/fictions/search?tagsAdd=]")) {
            if ("Unspecified" !in a.text()) story.tags.add(a.text())
        }
        for (label in doc.select("div#chapterHeroData span.bg-accent").map { it.text() }) {
            when (label) {
                "COMPLETED" -> story.status = "Completed"
                "ONGOING" -> story.status = "In-Progress"
                "HIATUS" -> story.status = "Hiatus"
                "STUB" -> story.status = "Stub"
                "DROPPED" -> story.status = "Dropped"
                "INACTIVE" -> story.status = "Inactive"
                "Fan Fiction" -> story.tags.add("FanFiction")
                "Original" -> story.tags.add("Original")
            }
        }
        Regex("calculated from ([0-9,]+) words").find(r.body)?.let { story.words = it.groupValues[1] }
        return story
    }

    override fun loadChapter(story: Story, index: Int): Element {
        val url = story.chapters[index].url
        val r = f.get(url)
        val doc = Jsoup.parse(r.body, r.url)

        // Anti-piracy: RR injects hidden paragraphs whose random class is set to display:none in a <style>.
        val hidden = mutableSetOf<String>()
        for (style in doc.select("style")) {
            Regex("\\.(\\S+)\\s*\\{[^}]*display\\s*:\\s*none\\s*;[^}]*\\}", RegexOption.IGNORE_CASE)
                .findAll(style.data()).forEach { hidden.add(it.groupValues[1]) }
        }

        val content = doc.selectFirst("div.chapter-inner.chapter-content")
            ?: throw HttpStatusException("$url (chapter content missing)", 404)
        for (cls in hidden) content.select("[class~=(^|\\s)${Regex.escape(cls)}(\\s|$)]").remove()

        for (div in content.select("div.spoiler")) {
            when (opts.spoilers) {
                SpoilerMode.REMOVE -> { div.remove(); continue }
                SpoilerMode.INLINE -> div.selectFirst("div.smalltext")?.remove()
                SpoilerMode.BOX -> { // fieldset/legend (FFF legend_spoilers)
                    val small = div.selectFirst("div.smalltext")
                    val title = small?.text() ?: "Spoiler"
                    small?.remove()
                    div.tagName("fieldset").prependElement("legend").text(title)
                }
            }
            div.select("div.spoiler-inner").removeAttr("style")
        }

        if (opts.includeAuthorNotes) {
            // select() returns document order, so notes before/after the content are the nearest ones.
            val seq = doc.select("div.author-note-portlet, div.chapter-inner.chapter-content")
            val pos = seq.indexOf(content)
            val front = seq.getOrNull(pos - 1)?.takeIf { it.hasClass("author-note-portlet") }
            val end = seq.getOrNull(pos + 1)?.takeIf { it.hasClass("author-note-portlet") }
            front?.let { content.prependChild(it.addClass("author-note")) }
            end?.let { content.appendChild(it.addClass("author-note")) }
        }
        return content
    }

    private fun parseDate(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(s.replace("Z", "+00:00")).toInstant()
        } catch (e: Exception) {
            try { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) } catch (e2: Exception) { null }
        }
    }

    companion object {
        const val DOMAIN = "www.royalroad.com"
        val URL_RE = Regex("^https?://(?:www\\.)?royalroadl?\\.com/fiction/(\\d+)", RegexOption.IGNORE_CASE)
        fun matches(url: String) = URL_RE.containsMatchIn(url)
    }
}
