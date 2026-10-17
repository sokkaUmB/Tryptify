package tf.monochrome.android.audio.eq

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream

/**
 * Nothing X's advanced-EQ share code: the text inside the QR code its
 * Equalizer screen exports and scans. Writing one hands the Nothing Ear's own
 * 8-band EQ any bands we like, through the stock app, with no Bluetooth work.
 *
 * Read from one export of an Ear (B171), and confirmed by re-encoding its bands
 * into the app's exact bytes and by a generated code scanning back in:
 *
 *     text    = base64(gzip(payload)), standard alphabet, padded
 *     payload = u8 0x00                          unknown; written as the app does
 *               u8 0x60                          96 = 8 bands x 12 bytes
 *               8 x { f32le gain, freq, q }      dB, Hz, Q; every band a peak
 *               u8 0x01                          unknown; written as the app does
 *               u8 n, n bytes                    profile name, UTF-8
 *
 * Pure JVM, so the format is pinned by unit tests against the captured export.
 */
object NothingEarShareCode {

    const val NUM_BANDS = 8
    private const val BAND_BYTES = 12
    private const val MAX_NAME_BYTES = 255

    data class Band(val freq: Float, val gainDb: Float, val q: Float)

    data class Profile(val name: String, val bands: List<Band>)

    fun encode(profile: Profile): String {
        require(profile.bands.size == NUM_BANDS) { "need $NUM_BANDS bands, got ${profile.bands.size}" }
        val name = truncateUtf8(profile.name, MAX_NAME_BYTES)
        val payload = ByteBuffer.allocate(2 + NUM_BANDS * BAND_BYTES + 2 + name.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        payload.put(0x00).put((NUM_BANDS * BAND_BYTES).toByte())
        for (b in profile.bands) payload.putFloat(b.gainDb).putFloat(b.freq).putFloat(b.q)
        payload.put(0x01).put(name.size.toByte()).put(name)
        return Base64.getEncoder().encodeToString(gzip(payload.array()))
    }

    fun decode(text: String): Profile {
        val payload = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(text.trim())))
            .use { it.readBytes() }
        require(payload.size >= 2) { "payload too short" }
        val table = payload[1].toInt() and 0xff
        require(table % BAND_BYTES == 0 && payload.size >= 2 + table + 2) { "not a band table" }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(2)
        val bands = List(table / BAND_BYTES) {
            val gain = buf.float
            val freq = buf.float
            Band(freq = freq, gainDb = gain, q = buf.float)
        }
        buf.get() // unknown flag
        val n = buf.get().toInt() and 0xff
        val name = String(payload, buf.position(), minOf(n, payload.size - buf.position()), Charsets.UTF_8)
        return Profile(name, bands)
    }

    /**
     * gzip with the header Nothing X writes (mtime 0, no flags, OS "unknown").
     * java.util.zip.GZIPOutputStream would say OS 0 instead; the deflate data
     * is the same either way, so this only keeps our codes identical to the
     * app's own.
     */
    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0xff.toByte()))
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val chunk = ByteArray(512)
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        } finally {
            deflater.end()
        }
        val crc = CRC32().apply { update(data) }.value
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(crc.toInt()).putInt(data.size).array())
        return out.toByteArray()
    }

    /** The longest prefix of [s] that fits in [max] UTF-8 bytes, never splitting a character. */
    private fun truncateUtf8(s: String, max: Int): ByteArray {
        var end = s.length
        while (true) {
            val bytes = s.substring(0, end).toByteArray(Charsets.UTF_8)
            if (bytes.size <= max) return bytes
            end = s.offsetByCodePoints(end, -1)
        }
    }
}
