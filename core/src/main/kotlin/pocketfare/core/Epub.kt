package pocketfare.core

import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streaming EPUB 3 writer (with an NCX for EPUB 2 readers). Chapters and images are written as they
 * arrive; the package document and navigation are written last by [finish], which is fine since
 * zip entry order only matters for the leading `mimetype`.
 */
class EpubWriter(out: OutputStream) {
    private val zip = ZipOutputStream(out)
    private val manifest = mutableListOf<Triple<String, String, String>>() // id, href, media-type (+ props via map)
    private val props = HashMap<String, String>()
    private val spine = mutableListOf<String>()
    private val toc = mutableListOf<Pair<String, String>>() // title, href
    private var coverHref: String? = null

    init {
        val mime = "application/epub+zip".toByteArray()
        val e = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size
            crc = CRC32().also { it.update(mime) }.value
        }
        zip.putNextEntry(e); zip.write(mime); zip.closeEntry()
        put(
            "META-INF/container.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
        )
        put("OEBPS/stylesheet.css", CSS)
        manifest.add(Triple("css", "stylesheet.css", "text/css"))
    }

    private fun put(path: String, text: String) = put(path, text.toByteArray(Charsets.UTF_8))
    private fun put(path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
    }

    /** @param href path relative to OEBPS, e.g. "images/img0001.jpg" */
    fun addImage(href: String, bytes: ByteArray, mediaType: String) {
        put("OEBPS/$href", bytes)
        manifest.add(Triple("img" + manifest.size, href, mediaType))
    }

    fun setCover(href: String, bytes: ByteArray, mediaType: String) {
        put("OEBPS/$href", bytes)
        manifest.add(Triple("cover-image", href, mediaType))
        props["cover-image"] = "cover-image"
        coverHref = href
    }

    /** @param bodyXhtml well-formed XHTML fragment */
    fun addChapter(title: String, bodyXhtml: String) {
        val n = spine.size + 1
        val href = "chapter%04d.xhtml".format(n)
        put("OEBPS/$href", page(title, "<h2 class=\"chapter-title\">${esc(title)}</h2>\n$bodyXhtml"))
        val id = "chap$n"
        manifest.add(Triple(id, href, "application/xhtml+xml"))
        spine.add(id)
        toc.add(title to href)
    }

    fun finish(story: Story, titlePage: Boolean = true) {
        val uid = "urn:uuid:" + UUID.nameUUIDFromBytes(story.sourceUrl.toByteArray())
        val modified = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
        val date = { i: Instant? -> i?.atOffset(ZoneOffset.UTC)?.toLocalDate()?.toString() }

        // Title page
        val rows = buildList {
            add("Author" to story.authors.joinToString(", "))
            add("Source" to "<a href=\"${esc(story.sourceUrl)}\">${esc(story.sourceUrl)}</a>")
            story.status?.let { add("Status" to esc(it)) }
            date(story.datePublished)?.let { add("Published" to it) }
            date(story.dateUpdated)?.let { add("Updated" to it) }
            add("Chapters" to toc.size.toString())
            story.words?.let { add("Words" to esc(it)) }
            if (story.tags.isNotEmpty()) add("Tags" to esc(story.tags.joinToString(", ")))
            add("Downloaded" to (date(Instant.now()) ?: ""))
        }
        val cover = coverHref?.let { "<div class=\"cover\"><img src=\"${esc(it)}\" alt=\"Cover\"/></div>\n" } ?: ""
        val titleBody = buildString {
            append(cover)
            append("<h1>${esc(story.title)}</h1>\n<p class=\"byline\">by ${esc(story.authors.joinToString(", "))}</p>\n<table class=\"meta\">\n")
            for ((k, v) in rows) append("<tr><th>$k</th><td>$v</td></tr>\n")
            append("</table>\n")
            story.description?.let { append("<div class=\"description\"><p>${esc(it)}</p></div>\n") }
        }
        if (titlePage) {
            put("OEBPS/title_page.xhtml", page(story.title, titleBody))
            manifest.add(0, Triple("title_page", "title_page.xhtml", "application/xhtml+xml"))
            spine.add(0, "title_page")
        }

        // EPUB 3 nav
        val titleNav = if (titlePage) "<li><a href=\"title_page.xhtml\">Title Page</a></li>\n" else ""
        val navItems = toc.joinToString("\n") { (t, h) -> "<li><a href=\"$h\">${esc(t)}</a></li>" }
        put(
            "OEBPS/nav.xhtml",
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>${esc(story.title)}</title></head>
<body><nav epub:type="toc" id="toc"><h1>Contents</h1><ol>
$titleNav$navItems
</ol></nav></body></html>"""
        )
        manifest.add(Triple("nav", "nav.xhtml", "application/xhtml+xml"))
        props["nav"] = "nav"

        // EPUB 2 NCX
        val points = ((if (titlePage) listOf("Title Page" to "title_page.xhtml") else emptyList()) + toc).mapIndexed { i, (t, h) ->
            """<navPoint id="np${i + 1}" playOrder="${i + 1}"><navLabel><text>${esc(t)}</text></navLabel><content src="$h"/></navPoint>"""
        }.joinToString("\n")
        put(
            "OEBPS/toc.ncx",
            """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="$uid"/><meta name="dtb:depth" content="1"/></head>
<docTitle><text>${esc(story.title)}</text></docTitle>
<navMap>
$points
</navMap></ncx>"""
        )
        manifest.add(Triple("ncx", "toc.ncx", "application/x-dtbncx+xml"))

        val meta = buildString {
            append("<dc:identifier id=\"uid\">$uid</dc:identifier>\n")
            append("<dc:title>${esc(story.title)}</dc:title>\n")
            for (a in story.authors) append("<dc:creator>${esc(a)}</dc:creator>\n")
            append("<dc:language>en</dc:language>\n")
            append("<dc:source>${esc(story.sourceUrl)}</dc:source>\n")
            append("<dc:publisher>${esc(story.siteName)}</dc:publisher>\n")
            date(story.datePublished)?.let { append("<dc:date>$it</dc:date>\n") }
            story.description?.let { append("<dc:description>${esc(it)}</dc:description>\n") }
            for (t in story.tags) append("<dc:subject>${esc(t)}</dc:subject>\n")
            append("<meta property=\"dcterms:modified\">$modified</meta>\n")
            if (coverHref != null) append("<meta name=\"cover\" content=\"cover-image\"/>\n")
        }
        val items = manifest.joinToString("\n") { (id, href, type) ->
            val p = when { props["cover-image"] == id -> " properties=\"cover-image\""; props["nav"] == id -> " properties=\"nav\""; else -> "" }
            "<item id=\"$id\" href=\"${esc(href)}\" media-type=\"$type\"$p/>"
        }
        val itemrefs = spine.joinToString("\n") { "<itemref idref=\"$it\"/>" }
        put(
            "OEBPS/content.opf",
            """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
$meta</metadata>
<manifest>
$items
</manifest>
<spine toc="ncx">
$itemrefs
</spine>
</package>"""
        )
        zip.finish()
        zip.flush()
    }

    private fun page(title: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>${esc(title)}</title><link rel="stylesheet" type="text/css" href="stylesheet.css"/></head>
<body>
$body
</body>
</html>"""

    companion object {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")

        const val CSS = """body { margin: 0 2%; }
h1, h2.chapter-title { text-align: center; }
p.byline { text-align: center; font-style: italic; }
table.meta th { text-align: left; padding-right: 1em; vertical-align: top; }
.cover { text-align: center; }
.cover img { max-width: 100%; max-height: 60vh; }
img { max-width: 100%; }
blockquote { margin: 1em 1.5em; padding-left: 0.8em; border-left: 3px solid #999; }
fieldset { border: 1px solid #999; margin: 1em 0; padding: 0.5em; }
legend { font-weight: bold; }
p.poster { font-style: italic; }
.author-note { border: 1px dashed #999; padding: 0.5em; margin: 1em 0; font-size: 0.95em; }
"""
    }
}
