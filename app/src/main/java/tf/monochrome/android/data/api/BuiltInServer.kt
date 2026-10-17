// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.api

/**
 * The chart server every install has: a TryptMania Parser on a CPU
 * (Railway), Trypt Mania's Slow mode, so charting works with nothing set up.
 *
 * Its address, secret path and all, comes from the build
 * (`BuildConfig.BUILT_IN_CPU_SERVER`, from local.properties), never from the
 * repository; a build without one has none. It is always in the list under
 * Settings › Connections and cannot be removed: [ensure] puts it back on
 * every read and every save. It can still be moved and checked again.
 */
object BuiltInServer {

    /**
     * [servers] with [builtIn] in it; null when it is there already, or the
     * build has none. An entry for the same host (one added by hand, or
     * under an older secret) becomes it, in its place; otherwise it goes last.
     */
    fun ensure(servers: List<ApiServer>, builtIn: String): List<ApiServer>? {
        val url = ApiServers.normalizeUrl(builtIn) ?: return null
        if (servers.any { it.url == url }) return null
        val host = hostOf(url)
        val at = servers.indexOfFirst { hostOf(it.url) == host }
        if (at < 0) return servers + entry(url)
        val kept = servers[at]
        return servers.filterIndexed { i, s -> i == at || hostOf(s.url) != host }
            .map { if (it === kept) it.copy(url = url, services = it.services + SERVICES) else it }
    }

    /** Whether [url] is the build's server, which the list does not offer to remove. */
    fun isBuiltIn(url: String, builtIn: String): Boolean {
        val own = ApiServers.normalizeUrl(builtIn) ?: return false
        return url == own
    }

    /**
     * The entry as added: a CPU stem and lyrics server, as it says of itself,
     * so Slow finds it before it has been asked, and Fast tries it last.
     */
    fun entry(url: String): ApiServer = ApiServer(url = url, services = SERVICES, stemDevice = "cpu")

    /** What the build's server serves: it separates for charts and times lyrics. */
    private val SERVICES = setOf(ApiService.STEMS, ApiService.LYRICS)

    /** What the list shows for it: its host, never its secret path. */
    fun shownAs(url: String): String = hostOf(url)

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').lowercase()
}
