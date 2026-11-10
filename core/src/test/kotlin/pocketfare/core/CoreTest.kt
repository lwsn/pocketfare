package pocketfare.core

import pocketfare.core.sites.RoyalRoadAdapter
import pocketfare.core.sites.Sites
import pocketfare.core.sites.XenForo2Adapter
import pocketfare.core.sites.XenForoSites
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline tests only. Live-site checks go through the CLI (see docs/ADAPTERS.md). */
class CoreTest {
    private val f = Fetcher(Fetcher.defaultClient(MemoryCookieJar()), "test")

    @Test
    fun siteMatching() {
        for (u in listOf(
            "https://forums.spacebattles.com/threads/some-story.123456/",
            "https://forum.spacebattles.com/threads/some-story.123456/page-4#post-99",
            "https://forums.sufficientvelocity.com/posts/10232301/",
            "https://sufficientvelocity.com/threads/x.1/threadmarks",
            "https://questionablequesting.com/threads/11624/",
            "https://www.alternatehistory.com/forum/threads/foo.5/",
            "https://www.royalroad.com/fiction/21220/mother-of-learning/chapter/301778/1-good-morning-brother",
            "https://royalroadl.com/fiction/3056",
        )) assertTrue(Sites.isSupported(u), u)
        assertTrue(!Sites.isSupported("https://archiveofourown.org/works/1"))
        assertEquals("forums.sufficientvelocity.com", XenForoSites.forUrl("https://sufficientvelocity.com/threads/a.1/")?.domain)
    }

    @Test
    fun xenforoUrlParsing() {
        val sb = XenForoSites.forUrl("https://forums.spacebattles.com/")!!
        XenForo2Adapter(sb, "https://forums.spacebattles.com/threads/a-b-c.123/page-2", f, Options(), false)
        XenForo2Adapter(sb, "https://forums.spacebattles.com/threads/123/", f, Options(), false)
        XenForo2Adapter(sb, "https://forums.spacebattles.com/posts/456/", f, Options(), false)
        assertFailsWith<UnsupportedSiteException> {
            XenForo2Adapter(sb, "https://forums.spacebattles.com/forums/creative-writing.18/", f, Options(), false)
        }
        val ah = XenForoSites.forUrl("https://www.alternatehistory.com/forum/")!!
        XenForo2Adapter(ah, "https://www.alternatehistory.com/forum/threads/foo.5/", f, Options(), false)
    }

    @Test
    fun extractSharedUrl() {
        assertEquals(
            "https://forums.spacebattles.com/threads/x.1/",
            Sites.extractUrl("Check this out: https://forums.spacebattles.com/threads/x.1/.")
        )
        assertNull(Sites.extractUrl("no link here"))
    }

    @Test
    fun sessionCookieDetection() {
        val sb = Sites.loginSiteForHost("forums.spacebattles.com")!!
        assertNull(sb.sessionCookies(mapOf("xf_csrf" to "abc", "xf_session" to "s1")))
        assertEquals(
            mapOf("xf_user" to "123,abcdef", "xf_session" to "s1"),
            sb.sessionCookies(mapOf("xf_csrf" to "abc", "xf_session" to "s1", "xf_user" to "123,abcdef", "cf_clearance" to "x"))
        )
        // Custom cookie prefix.
        assertEquals(
            setOf("qq_user", "qq_session"),
            sb.sessionCookies(mapOf("qq_user" to "9%2Cabc", "qq_session" to "s", "qq_csrf" to "c"))?.keys
        )
    }

    @Test
    fun cfEmail() {
        // "a@b.c" xor 0x42
        val key = 0x42
        val enc = "%02x".format(key) + "a@b.c".map { "%02x".format(it.code xor key) }.joinToString("")
        assertEquals("a@b.c", XenForo2Adapter.decodeCfEmail(enc))
    }

    @Test
    fun challengeDetection() {
        assertEquals("Cloudflare", detectChallenge(403, "<html><title>Just a moment...</title>"))
        assertEquals("Anubis", detectChallenge(200, "<script id=\"anubis_challenge\" type=\"application/json\">"))
        assertNull(detectChallenge(200, "<p>The god Anubis weighed his heart.</p>"))
        assertNull(detectChallenge(200, "<title>Just a moment</title>")) // CF needs an error status
    }

    @Test
    fun imageTypes() {
        assertEquals("jpg", Downloader.imageType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0))?.first)
        assertEquals("png", Downloader.imageType(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))?.first)
        assertNull(Downloader.imageType("<svg".toByteArray()))
    }

    @Test
    fun royalRoadUrl() {
        assertTrue(RoyalRoadAdapter.matches("https://www.royalroad.com/fiction/21220/mother-of-learning"))
    }

    @Test
    fun epubStructure() {
        val story = Story("https://example.com/s/1", "Example", "ex", "1").apply {
            title = "A <Story> & more"; authors.add("Someone"); description = "Desc"
        }
        val out = ByteArrayOutputStream()
        EpubWriter(out).apply {
            addChapter("One", "<div><p>Hello<br/>world &amp; all</p></div>")
            addChapter("Two", "<div><p>Bye</p></div>")
            finish(story)
        }
        val names = mutableListOf<String>()
        val contents = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names.add(e.name); contents[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals("mimetype", names.first())
        assertEquals("application/epub+zip", contents["mimetype"])
        for (n in listOf("META-INF/container.xml", "OEBPS/content.opf", "OEBPS/nav.xhtml", "OEBPS/toc.ncx", "OEBPS/chapter0002.xhtml"))
            assertNotNull(contents[n], n)
        // Every XHTML/XML file must be well-formed.
        val parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        for ((n, c) in contents) if (n.endsWith(".xhtml") || n.endsWith(".opf") || n.endsWith(".ncx") || n.endsWith(".xml"))
            parser.parse(ByteArrayInputStream(c.toByteArray()))
        assertTrue("A &lt;Story&gt; &amp; more" in contents["OEBPS/content.opf"]!!)
    }

    /**
     * Replays recorded pages from fixtures/ (see docs/ADAPTERS.md "Fixtures"). Skipped when the
     * fixture isn't present (fixtures/ is gitignored since it holds third-party story text).
     */
    @Test
    fun replaySufficientVelocity() {
        val dir = java.io.File(System.getProperty("fixtures.dir") ?: "fixtures")
        val url = "https://forums.sufficientvelocity.com/threads/cyberpunk-2077-forgive-me-for-letting-you-down-again.124796/"
        if (!java.io.File(dir, "forums.sufficientvelocity.com").exists()) return
        val rf = Fetcher(Fetcher.defaultClient(MemoryCookieJar(), FixtureInterceptor(dir, replay = true)), "test", 0)
        val d = Downloader(rf, Options(includeImages = false))
        val adapter = d.adapter(url)
        val story = adapter.loadMetadata()
        assertEquals("[Cyberpunk 2077] Forgive me for letting you down (again)", story.title)
        assertEquals(listOf("Tango"), story.authors)
        assertEquals(2, story.chapters.size)
        assertEquals("Feel the rhythm of the streets", story.chapters[0].title)
        val result = d.download(adapter, story, ByteArrayOutputStream()) { _, _, _ -> }
        assertTrue(result.failedChapters.isEmpty())
    }

    @Test
    fun xhtmlSanitizing() {
        // Run the sanitizer through a fake adapter returning fixed HTML.
        val html = """<div class="bbWrapper"><p onclick="x()" data-foo="1">Hi<br>there &nbsp; <b>bold</b></p>
            <img src="data:image/gif;base64,AAAA" alt=":)"><script>bad()</script><center>c</center>
            <a href="/threads/x.1/">rel</a></div>"""
        val doc = Jsoup.parse(html, "https://forums.spacebattles.com/threads/x.1/")
        val adapter = object : SiteAdapter {
            override fun loadMetadata() = error("unused")
            override fun loadChapter(story: Story, index: Int) = doc.selectFirst("div.bbWrapper")!!
        }
        val story = Story("https://forums.spacebattles.com/threads/x.1/", "SB", "fsb", "1").apply {
            title = "T"; chapters.add(Chapter("C1", "u"))
        }
        val out = ByteArrayOutputStream()
        Downloader(f, Options(includeImages = false)).download(adapter, story, out) { _, _, _ -> }
        var chapter = ""
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == "OEBPS/chapter0001.xhtml") chapter = z.readBytes().toString(Charsets.UTF_8) }
        }
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(chapter.toByteArray()))
        assertTrue("onclick" !in chapter && "data-foo" !in chapter && "bad()" !in chapter, chapter)
        assertTrue("[:)]" in chapter, chapter)
        assertTrue("href=\"https://forums.spacebattles.com/threads/x.1/\"" in chapter, chapter)
    }
}
