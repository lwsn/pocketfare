package app.pocketfare

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pocketfare.core.sites.Sites

/**
 * Second share target, "EPUB with options…": pick per-download overrides (starting from the saved
 * defaults) before queueing. Overrides travel with the job, so Retry/Update reuse them.
 */
class OptionsShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.dataString).joinToString(" ")
        val url = Sites.extractUrl(text)
        if (url == null || !Sites.isSupported(url)) {
            Toast.makeText(applicationContext, if (url == null) "No link found in shared text" else "Not a supported site:\n$url", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val defaults = Settings.load(this)

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    var s by remember { mutableStateOf(defaults) }
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp)) {
                            Text("Download with options", style = MaterialTheme.typography.headlineSmall)
                            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Changes here apply to this download only.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                            DownloadOptions(s) { s = it }
                        }
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { s = defaults }, enabled = s != defaults) { Text("Reset") }
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { finish() }) { Text("Cancel") }
                                Button(onClick = {
                                    // Identical to defaults = a plain download (no "Custom options" tag).
                                    Jobs.enqueue(this@OptionsShareActivity, url, if (s == defaults) null else s.toJson())
                                    Toast.makeText(applicationContext, "Downloading as EPUB…", Toast.LENGTH_SHORT).show()
                                    finish()
                                }) { Text("Download") }
                            }
                        }
                    }
                }
            }
        }
    }
}
