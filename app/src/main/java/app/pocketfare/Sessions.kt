package app.pocketfare

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import pocketfare.core.sites.Sites
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Login session cookies, per site domain, encrypted at rest with AES-256-GCM. The key lives in the
 * Android Keystore (hardware-backed where available) and can't be exported. Passwords are never
 * seen by the app: the user logs in on the real site in a WebView, and only the resulting session
 * cookies are captured here and removed from the WebView's own (plaintext) cookie database.
 */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "sessions.enc")
    private val data: MutableMap<String, MutableMap<String, String>> = load()
    private val _loggedIn = MutableStateFlow(data.keys.toSet())
    val loggedIn: StateFlow<Set<String>> = _loggedIn

    @Synchronized fun get(domain: String): Map<String, String>? = data[domain]?.toMap()
    @Synchronized fun has(domain: String) = data[domain]?.isNotEmpty() == true

    @Synchronized fun put(domain: String, cookies: Map<String, String>) {
        data[domain] = cookies.toMutableMap(); save()
    }

    @Synchronized fun update(domain: String, name: String, value: String?) {
        val m = data[domain] ?: return
        if (value.isNullOrEmpty()) m.remove(name) else m[name] = value
        // Losing the remember-me cookie means the server logged us out.
        if (m.keys.none { it.endsWith("user") }) data.remove(domain)
        save()
    }

    @Synchronized fun remove(domain: String) {
        data.remove(domain); save()
    }

    private fun save() {
        val json = JSONObject()
        for ((d, m) in data) json.put(d, JSONObject(m as Map<*, *>))
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(json.toString().toByteArray())
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(cipher.iv + ct)
        tmp.renameTo(file)
        _loggedIn.value = data.keys.toSet()
    }

    private fun load(): MutableMap<String, MutableMap<String, String>> {
        if (!file.exists()) return HashMap()
        return try {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            val json = JSONObject(String(cipher.doFinal(bytes, 12, bytes.size - 12)))
            val out = HashMap<String, MutableMap<String, String>>()
            for (d in json.keys()) {
                val o = json.getJSONObject(d)
                out[d] = o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
            }
            out
        } catch (e: Exception) {
            // Key lost (e.g. app data restored to a new device): sessions are unrecoverable by design.
            file.delete(); HashMap()
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        private const val ALIAS = "pocketfare_sessions"
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /** Expire cookies in the WebView store (they're held encrypted in SessionStore instead). */
        fun purgeFromWebView(domain: String, names: Collection<String>) {
            val cm = CookieManager.getInstance()
            val parent = domain.substringAfter('.')
            for (n in names) for (attr in listOf("", "; Domain=$domain", "; Domain=.$parent")) {
                cm.setCookie("https://$domain/", "$n=; Max-Age=0; Path=/$attr")
            }
            cm.flush()
        }
    }
}

/**
 * OkHttp cookie jar shared with the WebView: bot-check cookies (cf_clearance, Anubis auth) set while
 * passing a challenge in WebView apply to downloads too. Login session cookies come from
 * [SessionStore] instead, and rotated session cookies from responses go back there.
 */
class AppCookieJar(private val sessions: SessionStore) : CookieJar {
    private val cm = CookieManager.getInstance()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val out = mutableListOf<Cookie>()
        val names = mutableSetOf<String>()
        Sites.loginSiteForHost(url.host)?.let { site ->
            sessions.get(site.domain)?.forEach { (k, v) -> cookie(url.host, k, v)?.let { out.add(it); names.add(k) } }
        }
        cm.getCookie(url.toString())?.split(';')?.forEach { part ->
            val i = part.indexOf('=')
            if (i > 0) {
                val k = part.substring(0, i).trim()
                if (k !in names) cookie(url.host, k, part.substring(i + 1).trim())?.let { out.add(it) }
            }
        }
        return out
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val site = Sites.loginSiteForHost(url.host)
        val held = site?.let { sessions.get(it.domain) }
        for (c in cookies) {
            if (site != null && held != null && site.isSessionCookie(c.name, held.keys)) {
                val expired = c.expiresAt < System.currentTimeMillis()
                sessions.update(site.domain, c.name, if (expired) null else c.value)
            } else {
                cm.setCookie(url.toString(), c.toString())
            }
        }
    }

    private fun cookie(host: String, name: String, value: String): Cookie? = try {
        Cookie.Builder().name(name).value(value).domain(host).path("/").build()
    } catch (e: IllegalArgumentException) {
        null
    }
}
