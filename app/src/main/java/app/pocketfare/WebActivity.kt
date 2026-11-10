package app.pocketfare

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import pocketfare.core.sites.Sites
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Visible WebView for two jobs:
 *  - login: the user signs in on the real site (password, 2FA, captcha all handled by the site);
 *    once the session cookies appear they're moved to the encrypted [SessionStore].
 *  - challenge: the user passes a bot check that the invisible WebView couldn't.
 * Either way, the download that needed it is re-queued and the screen closes by itself.
 */
class WebActivity : ComponentActivity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LOGIN
        val target = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val retry = intent.getStringExtra(EXTRA_RETRY)
        val retrySettings = intent.getStringExtra(EXTRA_SETTINGS)
        val host = Uri.parse(target).host.orEmpty()
        val site = Sites.loginSiteForHost(host)
        val hint = if (mode == MODE_LOGIN) "Log in to ${site?.name ?: host}. Tick \"Stay logged in\"; this screen closes once you're in."
        else "Complete the check; this screen closes once the page loads."

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(hint, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                        }
                        AndroidView(factory = { ctx ->
                            WebView(ctx).also {
                                Net.configure(it)
                                it.webViewClient = WebViewClient()
                                it.loadUrl(target)
                                web = it
                            }
                        }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        lifecycleScope.launch {
            while (true) {
                delay(1000)
                val wv = web ?: continue
                if (mode == MODE_LOGIN && site != null) {
                    val all = parseCookies(CookieManager.getInstance().getCookie("https://${site.domain}/"))
                    val session = site.sessionCookies(all) ?: continue
                    App.sessions.put(site.domain, session)
                    SessionStore.purgeFromWebView(site.domain, session.keys)
                    toast("Logged in to ${site.name}")
                    break
                } else if (mode == MODE_CHALLENGE && wv.progress == 100) {
                    val html = Net.html(wv)
                    if (html.length > 1000 && !Net.isChallenge(html)) {
                        CookieManager.getInstance().flush()
                        toast("Check passed")
                        break
                    }
                }
            }
            retry?.let { Jobs.enqueue(this@WebActivity, it, retrySettings) }
            finish()
        }
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_URL = "url"
        private const val EXTRA_RETRY = "retry"
        private const val EXTRA_SETTINGS = "settings"
        private const val MODE_LOGIN = "login"
        private const val MODE_CHALLENGE = "challenge"

        fun loginIntent(ctx: Context, loginUrl: String, retryUrl: String? = null, retrySettings: String? = null) =
            Intent(ctx, WebActivity::class.java).putExtra(EXTRA_MODE, MODE_LOGIN).putExtra(EXTRA_URL, loginUrl)
                .putExtra(EXTRA_RETRY, retryUrl).putExtra(EXTRA_SETTINGS, retrySettings)

        fun challengeIntent(ctx: Context, url: String, retryUrl: String, retrySettings: String? = null) =
            Intent(ctx, WebActivity::class.java).putExtra(EXTRA_MODE, MODE_CHALLENGE).putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_RETRY, retryUrl).putExtra(EXTRA_SETTINGS, retrySettings)

        fun parseCookies(header: String?): Map<String, String> =
            header?.split(';')?.mapNotNull { p ->
                val i = p.indexOf('=')
                if (i > 0) p.substring(0, i).trim() to p.substring(i + 1).trim() else null
            }?.toMap() ?: emptyMap()
    }
}
