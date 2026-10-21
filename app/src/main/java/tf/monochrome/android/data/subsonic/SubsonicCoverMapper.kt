package tf.monochrome.android.data.subsonic

import coil3.map.Mapper
import coil3.request.Options

/**
 * Turns a `subsonic-cover://<id>` artwork reference into the server's real
 * getCoverArt URL, inside this app's image loader and nowhere else.
 *
 * Registered ahead of Coil's own String mapper, so the URL it returns then
 * takes the ordinary network path, disk cache included. That URL is stable
 * from one launch to the next (the token's salt is fixed per sign-in), so the
 * cache finds it again. With no server connected the reference is left as
 * it is, no fetcher takes it, and the row shows its placeholder.
 *
 * [server] is read on every call rather than captured, so connecting,
 * switching or disconnecting takes effect on the next picture.
 */
class SubsonicCoverMapper(private val server: () -> SubsonicServer?) : Mapper<String, String> {
    override fun map(data: String, options: Options): String? {
        val id = Subsonic.coverArtId(data) ?: return null
        return Subsonic.coverArtUrl(server() ?: return null, id)
    }
}
