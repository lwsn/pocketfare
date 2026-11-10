package app.pocketfare

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import pocketfare.core.sites.Sites

/** Target of the browser's Share menu. Queues the download and closes immediately. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.dataString).joinToString(" ")
        val url = Sites.extractUrl(text)
        when {
            url == null -> toast("No link found in shared text")
            !Sites.isSupported(url) -> toast("Not a supported site:\n$url")
            else -> {
                Jobs.enqueue(this, url)
                toast("Downloading as EPUB…")
            }
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
}
