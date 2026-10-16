// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.api

/**
 * The chart server every install starts with: a TryptMania Parser on a CPU
 * (Railway), Trypt Mania's Slow mode, so charting works with nothing set up.
 *
 * Its address, secret path and all, comes from the build
 * (`BuildConfig.BUILT_IN_CPU_SERVER`, from local.properties), never from the
 * repository; a build without one ships none. Once added it is an ordinary
 * entry under Settings › Connections: it can be moved or removed, and a
 * removed one stays removed. A later build with a new address (a new secret)
 * moves the entry it added to the new one.
 */
object BuiltInServer {

    /**
     * [servers] with [builtIn] added at the end, or the entry a build added
     * before ([seeded], its address) moved to it. Null when the list stays
     * as it is: no built-in server, the same one added already, the player's
     * own entry for its host, or the one added before removed by the player.
     */
    fun seed(servers: List<ApiServer>, builtIn: String, seeded: String?): List<ApiServer>? {
        val url = ApiServers.normalizeUrl(builtIn) ?: return null
        if (url == seeded) return null
        if (seeded != null) {
            if (servers.none { it.url == seeded }) return null
            if (servers.any { it.url == url }) return servers.filterNot { it.url == seeded }
            return servers.map { if (it.url == seeded) it.copy(url = url) else it }
        }
        if (servers.any { hostOf(it.url) == hostOf(url) }) return null
        return servers + entry(url)
    }

    /**
     * The entry as added: a CPU stem server, as it says of itself, so Slow
     * finds it before it has been asked, and Fast tries it last.
     */
    fun entry(url: String): ApiServer = ApiServer(url = url, services = setOf(ApiService.STEMS), stemDevice = "cpu")

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').lowercase()
}
