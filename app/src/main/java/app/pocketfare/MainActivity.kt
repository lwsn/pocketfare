package app.pocketfare

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkInfo
import androidx.work.WorkManager
import pocketfare.core.FileNameStyle
import pocketfare.core.Options
import pocketfare.core.SpoilerMode
import pocketfare.core.sites.Sites

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    var settingsOpen by remember { mutableStateOf(false) }
                    BackHandler(enabled = settingsOpen) { settingsOpen = false }
                    if (settingsOpen) SettingsScreen(onBack = { settingsOpen = false })
                    else HomeScreen(onSettings = { settingsOpen = true })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ home

@Composable
private fun HomeScreen(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val wm = remember { WorkManager.getInstance(ctx) }
    val jobs by wm.getWorkInfosByTagFlow(Jobs.TAG).collectAsState(initial = emptyList())
    var url by remember { mutableStateOf("") }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PocketFare", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Text(
            "Share a story link from your browser to \"Save as EPUB\", or paste it here.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Story URL") }, singleLine = true)
        val extracted = Sites.extractUrl(url)
        val supported = extracted != null && Sites.isSupported(extracted)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = supported, onClick = { Jobs.enqueue(ctx, extracted!!); url = "" }) { Text("Download") }
            OutlinedButton(enabled = supported, onClick = {
                ctx.startActivity(Intent(ctx, OptionsShareActivity::class.java).putExtra(Intent.EXTRA_TEXT, extracted)); url = ""
            }) { Text("With options…") }
            if (url.isNotBlank() && !supported) Text("Unsupported site", Modifier.align(Alignment.CenterVertically))
        }

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (jobs.any { it.state.isFinished }) TextButton(onClick = { wm.pruneWork() }) { Text("Clear finished") }
        }
        if (jobs.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
        // WorkInfo has no timestamps on older WorkManager APIs; running/queued first is good enough.
        for (job in jobs.sortedBy { if (it.state.isFinished) 1 else 0 }) JobCard(job, wm)
    }
}

@Composable
private fun JobCard(job: WorkInfo, wm: WorkManager) {
    val ctx = LocalContext.current
    val url = job.tags.firstOrNull { it.startsWith("url:") }?.removePrefix("url:") ?: ""
    val data = if (job.state.isFinished) job.outputData else job.progress
    val title = data.getString(Jobs.KEY_TITLE) ?: url
    val custom = data.getString(Jobs.KEY_SETTINGS)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (custom != null) Text("Custom options", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            when (job.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Text("Queued (waiting for network)", style = MaterialTheme.typography.bodySmall)
                WorkInfo.State.RUNNING -> {
                    val done = data.getInt(Jobs.KEY_DONE, 0)
                    val total = data.getInt(Jobs.KEY_TOTAL, 0)
                    Text("${if (total > 0) "$done/$total  " else ""}${data.getString(Jobs.KEY_STEP) ?: "Starting"}",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // No M3 1.3 track gap / end "stop" dot: plain continuous bar.
                    if (total > 0) LinearProgressIndicator(
                        progress = { done.toFloat() / total }, Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        gapSize = 0.dp, drawStopIndicator = {},
                    )
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp), gapSize = 0.dp)
                    TextButton(onClick = { wm.cancelWorkById(job.id) }) { Text("Cancel") }
                }
                WorkInfo.State.SUCCEEDED -> {
                    val failed = data.getInt(Jobs.KEY_FAILED, 0)
                    Text("Saved to ${data.getString(Jobs.KEY_SAVED)}" + if (failed > 0) " ($failed chapters failed)" else "",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { Jobs.enqueue(ctx, url, custom) }) { Text("Update") }
                }
                WorkInfo.State.FAILED -> {
                    val action = data.getString(Jobs.KEY_ACTION)
                    val actionUrl = data.getString(Jobs.KEY_ACTION_URL)
                    val msg = (data.getString(Jobs.KEY_ERROR) ?: "Failed").replace("Tap to open it", "Open it")
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Opens the WebView directly; it re-queues the download itself once passed.
                        if (action != null && actionUrl != null) Button(onClick = {
                            NotificationManagerCompat.from(ctx).cancel(job.id.hashCode() + 1)
                            ctx.startActivity(
                                if (action == Jobs.ACTION_LOGIN) WebActivity.loginIntent(ctx, actionUrl, url, custom)
                                else WebActivity.challengeIntent(ctx, actionUrl, url, custom)
                            )
                        }) { Text(if (action == Jobs.ACTION_LOGIN) "Log in" else "Verify") }
                        TextButton(onClick = { Jobs.enqueue(ctx, url, custom) }) { Text("Retry") }
                    }
                }
                WorkInfo.State.CANCELLED -> Text("Cancelled", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(Settings.load(ctx)) }
    fun update(n: Settings) { s = n; Settings.save(ctx, n) }

    var tree by remember { mutableStateOf(Prefs.outputTree(ctx)) }
    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Prefs.setOutputTree(ctx, uri); tree = uri
        }
    }
    val loggedIn by App.sessions.loggedIn.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Section("Saving")
        val folder = tree?.let { DocumentFile.fromTreeUri(ctx, it)?.name } ?: Storage.DEFAULT_DIR
        Text("Save to: $folder", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDir.launch(null) }) { Text("Choose folder") }
            if (tree != null) TextButton(onClick = { Prefs.setOutputTree(ctx, null); tree = null }) { Text("Use Downloads") }
        }
        Label("File name")
        for (style in FileNameStyle.entries) Radio(style.label, s.fileNameStyle == style) { update(s.copy(fileNameStyle = style)) }

        DownloadOptions(s) { update(it) }

        Section("Accounts")
        Text(
            "Log in on the site itself. Only session cookies are kept, encrypted with a key that never leaves this device; your password is never stored.",
            style = MaterialTheme.typography.bodySmall
        )
        for (site in Sites.loginSites) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(site.name)
                    Text(if (site.domain in loggedIn) "Logged in" else "Not logged in", style = MaterialTheme.typography.bodySmall)
                }
                if (site.domain in loggedIn) TextButton(onClick = { App.sessions.remove(site.domain) }) { Text("Log out") }
                else OutlinedButton(onClick = { ctx.startActivity(WebActivity.loginIntent(ctx, site.loginUrl)) }) { Text("Log in") }
            }
        }

        Section("Network")
        Label("Delay between requests")
        for ((label, ms) in listOf("0.5 s" to 500L, "1.5 s (default)" to 1500L, "3 s" to 3000L, "5 s (gentlest)" to 5000L))
            Radio(label, s.requestDelayMs == ms) { update(s.copy(requestDelayMs = ms)) }

        Spacer(Modifier.height(24.dp))
        Text("Site adapters ported from FanFicFare (Apache-2.0).", style = MaterialTheme.typography.bodySmall)
    }
}

/** Options that can differ per download: shown in Settings (defaults) and in "EPUB with options…". */
@Composable
fun DownloadOptions(s: Settings, update: (Settings) -> Unit) {
    fun opts(f: (Options) -> Options) = update(s.copy(options = f(s.options)))
    val o = s.options

    Section("Images")
    Toggle("Include images", "Off: images become their alt text. Smaller files.", o.includeImages) { v -> opts { it.copy(includeImages = v) } }
    Toggle("Include cover", "Threadmark index icon / Royal Road cover", o.includeCover) { v -> opts { it.copy(includeCover = v) } }
    Toggle("Grayscale images", "For e-ink readers", s.grayscaleImages) { update(s.copy(grayscaleImages = it)) }
    Label("Shrink large images")
    for ((label, size) in listOf("Keep original" to 0, "2500 px" to 2500, "1600 px" to 1600, "1200 px" to 1200))
        Radio(label, s.imageMaxSize == size) { update(s.copy(imageMaxSize = size)) }

    Section("Book")
    Toggle("Title page", "Cover, author, source link, status, tags", o.includeTitlePage) { v -> opts { it.copy(includeTitlePage = v) } }
    Toggle("Add chapter numbers", "\"1. Chapter title\"", o.addChapterNumbers) { v -> opts { it.copy(addChapterNumbers = v) } }
    Label("Spoilers")
    Radio("Show in a labelled box", o.spoilers == SpoilerMode.BOX) { opts { it.copy(spoilers = SpoilerMode.BOX) } }
    Radio("Show inline", o.spoilers == SpoilerMode.INLINE) { opts { it.copy(spoilers = SpoilerMode.INLINE) } }
    Radio("Remove", o.spoilers == SpoilerMode.REMOVE) { opts { it.copy(spoilers = SpoilerMode.REMOVE) } }

    Section("Forums (SpaceBattles, SV, QQ, AH)")
    Label("Threadmark categories to include")
    for (cat in Options.THREADMARK_CATEGORIES) {
        val on = cat !in o.skipThreadmarkCategories
        Row(Modifier.fillMaxWidth().clickable(enabled = cat != "Threadmarks") {
            opts { it.copy(skipThreadmarkCategories = if (on) it.skipThreadmarkCategories + cat else it.skipThreadmarkCategories - cat) }
        }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = on, onCheckedChange = null, enabled = cat != "Threadmarks")
            Text(cat, Modifier.padding(start = 8.dp))
        }
    }
    Toggle("Order threadmarks by date", "Interleave categories chronologically instead of grouping them", o.orderThreadmarksByDate) { v -> opts { it.copy(orderThreadmarksByDate = v) } }
    Toggle("Always include first post", "Even when it isn't threadmarked", o.alwaysIncludeFirstPost) { v -> opts { it.copy(alwaysIncludeFirstPost = v) } }
    Toggle("Treat Apocrypha as Omake", null, o.apocryphaToOmake) { v -> opts { it.copy(apocryphaToOmake = v) } }
    Toggle("Show non-author posters", "Adds \"Chapter by: name\" to threadmarks posted by someone else (quests, omake)", o.includeNonAuthorPoster) { v -> opts { it.copy(includeNonAuthorPoster = v) } }
    Toggle("Reveal invisible text", "Show text hidden with transparent color", o.revealInvisibleText) { v -> opts { it.copy(revealInvisibleText = v) } }

    Section("Royal Road")
    Toggle("Include author's notes", null, o.includeAuthorNotes) { v -> opts { it.copy(includeAuthorNotes = v) } }
}

@Composable fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

@Composable fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

@Composable fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable fun Radio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
