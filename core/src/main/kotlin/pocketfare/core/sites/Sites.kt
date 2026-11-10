package pocketfare.core.sites

import pocketfare.core.Fetcher
import pocketfare.core.Options
import pocketfare.core.SiteAdapter

/** A site that supports "log in via WebView, keep only the session cookies (encrypted)". */
class LoginSite(
    val name: String,
    val domain: String,
    val hosts: List<String>,
    val loginUrl: String,
) {
    /**
     * Given all cookies the WebView holds for this site, return the ones that make up a logged-in
     * session, or null if not logged in yet.
     *
     * XenForo's cookie prefix is configurable per board ("xf_" by default), so detect it from the
     * remember-me cookie: `<prefix>user` with value "<userId>,<token>".
     */
    fun sessionCookies(all: Map<String, String>): Map<String, String>? {
        val userCookie = all.entries.firstOrNull { (k, v) -> k.endsWith("user") && Regex("^\\d+(,|%2C)").containsMatchIn(v) }
            ?: return null
        val prefix = userCookie.key.removeSuffix("user")
        val names = setOf("${prefix}user", "${prefix}session", "${prefix}tfa_trust")
        return all.filterKeys { it in names }
    }

    /** Names that, once we hold a session, must be kept out of the WebView's plaintext cookie DB. */
    fun isSessionCookie(name: String, held: Set<String>) = name in held ||
        (held.any { it.endsWith("user") } && name == held.first { it.endsWith("user") }.removeSuffix("user") + "session")
}

object Sites {
    val loginSites: List<LoginSite> = XenForoSites.all.map { LoginSite(it.name, it.domain, it.hosts, it.loginUrl) }

    fun loginSiteForHost(host: String): LoginSite? = loginSites.firstOrNull { host.lowercase() in it.hosts }

    fun isSupported(url: String) = XenForoSites.forUrl(url) != null || RoyalRoadAdapter.matches(url)

    /** @param hasLogin whether the app holds a saved session for the site with this domain. */
    fun adapterFor(url: String, f: Fetcher, opts: Options, hasLogin: (domain: String) -> Boolean): SiteAdapter? {
        XenForoSites.forUrl(url)?.let { return XenForo2Adapter(it, url, f, opts, hasLogin(it.domain)) }
        if (RoyalRoadAdapter.matches(url)) return RoyalRoadAdapter(url, f, opts)
        return null
    }

    /** Pull the first http(s) URL out of shared text ("Title - https://..."). */
    fun extractUrl(text: String): String? =
        Regex("https?://[^\\s<>\"']+").find(text)?.value?.trimEnd('.', ',', ')', ']')
}
