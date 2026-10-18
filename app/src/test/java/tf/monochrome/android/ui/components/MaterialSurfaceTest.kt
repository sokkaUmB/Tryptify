package tf.monochrome.android.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app is liquid glass or Material 3, and lists are Material 3 in both
 * (docs/ui-invariants.md, Glass and List rows). The old frosted pane, a third
 * look every row wore while liquid glass was on, is not to come back.
 */
class MaterialSurfaceTest {
    private val ui = File("src/main/java/tf/monochrome/android/ui")

    @Test
    fun `the Material 3 surface is a flat fill`() {
        val src = File(ui, "components/MaterialSurface.kt").readText()
        // A rim, a sheen, a blur or a clip is the frosted pane again.
        for (banned in listOf("border(", "Brush", "hazeEffect", "clip(")) {
            assertFalse("MaterialSurface.kt has $banned", banned in src)
        }
    }

    @Test
    fun `the old frosted modifier is gone`() {
        assertFalse(File(ui, "components/LiquidGlass.kt").exists())
        val users = ui.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { "import tf.monochrome.android.ui.components.liquidGlass" in it.readText() }
            .map { it.name }
            .toList()
        assertTrue("still importing the frosted pane: $users", users.isEmpty())
    }

    @Test
    fun `song rows and library tiles stand on the Material 3 surface`() {
        for (path in listOf(
            "components/TrackItem.kt",
            "components/AlbumItem.kt",
            "components/ArtistItem.kt",
            "library/LibraryIndex.kt",
        )) {
            val src = File(ui, path).readText()
            assertTrue("$path is not on materialSurface", ".materialSurface(" in src)
            for (glass in listOf(".playerGlass(", "GlassMaterial(", "PressableGlass(", ".liveGlassLens(")) {
                assertFalse("$path draws $glass", glass in src)
            }
        }
    }
}
