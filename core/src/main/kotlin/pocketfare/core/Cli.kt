package pocketfare.core

import java.io.File

/**
 * Desktop entry point for testing adapters without a phone:
 *   ./gradlew :core:run --args="<story url> [output dir] [--no-images] [--meta-only] [--record|--replay]"
 *
 * --record saves every HTML page fetched to fixtures/ (repo root); --replay serves pages from there with
 * no network access. Relative output dirs resolve against the repo root.
 */
fun main(args: Array<String>) {
    val url = args.firstOrNull { !it.startsWith("--") } ?: run {
        System.err.println("usage: <story url> [output dir] [--no-images] [--meta-only]"); return
    }
    val outDir = File(args.filter { !it.startsWith("--") }.getOrNull(1) ?: ".")
    val opts = Options(includeImages = "--no-images" !in args)
    val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    val fixtures = File("fixtures")
    val f = when {
        "--replay" in args -> Fetcher(Fetcher.defaultClient(MemoryCookieJar(), FixtureInterceptor(fixtures, replay = true)), ua, minDelayMs = 0)
        "--record" in args -> Fetcher(Fetcher.defaultClient(MemoryCookieJar(), FixtureInterceptor(fixtures, replay = false)), ua)
        else -> Fetcher(Fetcher.defaultClient(MemoryCookieJar()), ua)
    }
    val d = Downloader(f, opts)

    val adapter = d.adapter(url)
    val story = adapter.loadMetadata()
    println("Title:    ${story.title}")
    println("Author:   ${story.authors.joinToString()}")
    println("Status:   ${story.status}   Words: ${story.words}   Updated: ${story.dateUpdated}")
    println("Tags:     ${story.tags.joinToString()}")
    println("Cover:    ${story.coverUrl}")
    println("Chapters: ${story.chapters.size}")
    story.chapters.take(5).forEach { println("  - ${it.title}  ${it.url}") }
    if (story.chapters.size > 5) println("  ... ${story.chapters.last().title}")
    if ("--meta-only" in args) return

    outDir.mkdirs()
    val file = File(outDir, Downloader.fileName(story))
    val result = file.outputStream().buffered().use { out ->
        d.download(adapter, story, out) { msg, done, total -> println("[$done/$total] $msg") }
    }
    println("Wrote ${file.absolutePath} (${file.length() / 1024} KiB, ${result.images} images, ${result.failedChapters.size} failed)")
}
