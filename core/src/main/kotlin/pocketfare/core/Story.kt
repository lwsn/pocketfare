package pocketfare.core

import org.jsoup.nodes.Element
import java.time.Instant

class Chapter(
    val title: String,
    val url: String,
    /** Adapter-specific key (e.g. XenForo post id). */
    val key: String? = null,
)

class Story(val sourceUrl: String, val siteName: String, val siteAbbrev: String, val storyId: String) {
    var title: String = ""
    val authors = mutableListOf<String>()
    var authorUrl: String? = null
    /** Plain text. */
    var description: String? = null
    /** "Completed", "In-Progress", "Hiatus", ... */
    var status: String? = null
    var datePublished: Instant? = null
    var dateUpdated: Instant? = null
    val tags = linkedSetOf<String>()
    var words: String? = null
    var coverUrl: String? = null
    val chapters = mutableListOf<Chapter>()

    fun noteDate(d: Instant?) {
        if (d == null) return
        if (datePublished == null || d < datePublished) datePublished = d
        if (dateUpdated == null || d > dateUpdated) dateUpdated = d
    }
}

enum class SpoilerMode { BOX, INLINE, REMOVE }

enum class FileNameStyle(val label: String) {
    TITLE_AUTHOR("Title - Author.epub"),
    FFF("Title-site_storyId.epub (FanFicFare style)"),
}

/**
 * User-facing options. Names in comments are the FanFicFare personal.ini equivalents.
 * Defaults follow FFF's defaults.ini for XenForo/Royal Road, except include_images (FFF: false).
 */
data class Options(
    // --- general
    val includeImages: Boolean = true,               // include_images
    val includeCover: Boolean = true,                // (never_make_cover inverted)
    val includeTitlePage: Boolean = true,            // include_titlepage
    val addChapterNumbers: Boolean = false,          // add_chapter_numbers
    val spoilers: SpoilerMode = SpoilerMode.BOX,     // legend_spoilers / remove_spoilers
    /** Host-provided image processing (resize/grayscale); return null to drop the image. */
    val imageTransform: ((ByteArray) -> ByteArray?)? = null,
    // --- Royal Road
    val includeAuthorNotes: Boolean = true,          // include_author_notes
    // --- XenForo
    val skipThreadmarkCategories: Set<String> = setOf("Staff Post"), // skip_threadmarks_categories
    val orderThreadmarksByDate: Boolean = false,     // order_threadmarks_by_date
    val alwaysIncludeFirstPost: Boolean = false,     // always_include_first_post
    val apocryphaToOmake: Boolean = false,           // apocrypha_to_omake
    val revealInvisibleText: Boolean = false,        // reveal_invisible_text
    val includeNonAuthorPoster: Boolean = false,     // include_nonauthor_poster
) {
    companion object {
        val THREADMARK_CATEGORIES = listOf("Threadmarks", "Sidestory", "Apocrypha", "Omake", "Media", "Informational", "Staff Post")
    }
}

interface SiteAdapter {
    /** Fetch story metadata and the chapter list. */
    fun loadMetadata(): Story

    /**
     * Fetch one chapter. Returns the content element, with relative links resolvable via its
     * baseUri. The caller clones/sanitizes it, so adapters may return a live document node.
     */
    fun loadChapter(story: Story, index: Int): Element
}
