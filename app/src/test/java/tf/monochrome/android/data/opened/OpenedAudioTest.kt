package tf.monochrome.android.data.opened

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.opened.OpenedAudio.Refusal

class OpenedAudioTest {

    private val pkg = "tf.monochrome.android"
    private val roots = listOf("/data/user/0/$pkg", "/storage/emulated/0/Android/data/$pkg")

    private fun check(
        action: String? = "android.intent.action.VIEW",
        scheme: String? = "content",
        mime: String? = "audio/flac",
        authority: String? = "com.android.externalstorage.documents",
        path: String? = null,
    ) = OpenedAudio.refusal(action, scheme, mime, authority, path, roots, pkg)

    @Test
    fun `a song from a file manager is taken`() {
        assertNull(check())
        assertNull(check(scheme = "file", authority = null, path = "/storage/emulated/0/Music/a.mp3", mime = "audio/mpeg"))
        assertNull(check(mime = "application/ogg"))
        assertNull(check(mime = "AUDIO/X-FLAC; charset=binary"))
    }

    @Test
    fun `anything but a VIEW of a file is refused`() {
        assertEquals(Refusal.NOT_A_FILE, check(action = "android.intent.action.SEND"))
        assertEquals(Refusal.NOT_A_FILE, check(action = null))
        assertEquals(Refusal.NOT_A_FILE, check(scheme = "https"))
        assertEquals(Refusal.NOT_A_FILE, check(scheme = "tf.monotrypt.android"))
        assertEquals(Refusal.NOT_A_FILE, check(scheme = null))
        assertEquals(Refusal.NOT_A_FILE, check(authority = null))
        assertEquals(Refusal.NOT_A_FILE, check(scheme = "file", authority = null, path = null))
    }

    @Test
    fun `a type that is not audio is refused`() {
        assertEquals(Refusal.NOT_AUDIO, check(mime = "image/png"))
        assertEquals(Refusal.NOT_AUDIO, check(mime = "video/mp4"))
        assertEquals(Refusal.NOT_AUDIO, check(mime = "application/octet-stream"))
        assertEquals(Refusal.NOT_AUDIO, check(mime = "audio/"))
        assertEquals(Refusal.NOT_AUDIO, check(mime = null))
    }

    @Test
    fun `the app's own files and providers are refused`() {
        assertEquals(Refusal.PRIVATE, check(authority = "$pkg.fileprovider"))
        assertEquals(Refusal.PRIVATE, check(authority = pkg))
        assertEquals(Refusal.PRIVATE, check(scheme = "file", authority = null, path = "/data/user/0/$pkg/databases/x.mp3"))
        assertEquals(Refusal.PRIVATE, check(scheme = "file", authority = null, path = "/storage/emulated/0/Android/data/$pkg/files/a.flac"))
        // Another app's private storage too, whatever the per-user path.
        assertEquals(Refusal.PRIVATE, check(scheme = "file", authority = null, path = "/data/data/com.other/files/a.mp3"))
        assertEquals(Refusal.PRIVATE, check(scheme = "file", authority = null, path = "/data/user_de/0/com.other/a.mp3"))
    }

    @Test
    fun `a provider merely starting with the package name is not ours`() {
        assertNull(check(authority = "${pkg}x.provider"))
        assertNull(check(scheme = "file", authority = null, path = "/storage/emulated/0/Android/data/${pkg}x.mp3"))
    }

    @Test
    fun `names are safe to write`() {
        assertEquals("song.flac", OpenedAudio.safeName("song.flac", "flac"))
        assertEquals("song.flac", OpenedAudio.safeName("../../etc/song.flac", null))
        assertEquals("song.mp3", OpenedAudio.safeName("C:\\music\\song.mp3", null))
        assertEquals("song.flac", OpenedAudio.safeName("...song.flac", null))
        assertEquals("ab.mp3", OpenedAudio.safeName("a\u0000b.mp3", null))
        assertEquals("audio.ogg", OpenedAudio.safeName(null, "ogg"))
        assertEquals("audio", OpenedAudio.safeName("", null))
        assertEquals("audio", OpenedAudio.safeName("", "../x"))
        assertEquals("voice note.m4a", OpenedAudio.safeName("voice note", "m4a"))
        val long = OpenedAudio.safeName("x".repeat(300) + ".flac", null)
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".flac"))
    }

    @Test
    fun `the title falls back to the file name`() {
        assertEquals("My song", OpenedAudio.titleFromName("My_song.mp3"))
        assertEquals(".mp3", OpenedAudio.titleFromName(".mp3"))
    }

    @Test
    fun `the same file keeps one copy and one id`() {
        val a = OpenedAudio.copyKey("content://x/1", 10)
        assertEquals(a, OpenedAudio.copyKey("content://x/1", 10))
        assertFalse(a == OpenedAudio.copyKey("content://x/1", 11))
        assertFalse(a == OpenedAudio.copyKey("content://x/2", 10))
        assertTrue(OpenedAudio.trackId(a).startsWith(OpenedAudio.ID_PREFIX))
        assertFalse(OpenedAudio.trackId(a).startsWith("local_"))
    }

    @Test
    fun `old copies go, newest and the playing one stay`() {
        val copies = mapOf("a" to 1L, "b" to 2L, "c" to 3L, "d" to 4L, "e" to 5L)
        // Three kept: the playing one, then the two newest others.
        assertEquals(setOf("a", "b"), OpenedAudio.copiesToDrop(copies, keepKey = "e", keep = 3))
        assertEquals(setOf("b", "c"), OpenedAudio.copiesToDrop(copies, keepKey = "a", keep = 3))
        // A new copy not listed yet still takes its slot.
        assertEquals(setOf("a", "b", "c"), OpenedAudio.copiesToDrop(copies, keepKey = "new", keep = 3))
        assertEquals(emptySet<String>(), OpenedAudio.copiesToDrop(mapOf("a" to 1L), keepKey = "a"))
    }

    @Test
    fun `size limits`() {
        assertTrue(OpenedAudio.fits(10, 10 + OpenedAudio.SPARE_BYTES))
        assertFalse(OpenedAudio.fits(10, 9 + OpenedAudio.SPARE_BYTES))
        assertFalse(OpenedAudio.fits(OpenedAudio.MAX_BYTES + 1, Long.MAX_VALUE))
        assertTrue(OpenedAudio.fits(-1, 0))
    }
}
