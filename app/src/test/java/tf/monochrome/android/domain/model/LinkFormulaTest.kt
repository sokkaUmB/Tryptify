package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The music link formula: what it accepts, what it says about what it refuses, and what it computes. */
class LinkFormulaTest {
    private fun eval(text: String, value: Float, band: Float): Float? {
        val compiled = LinkFormula.compile(text)
        assertTrue("'$text' should compile, got ${compiled.problem} ${compiled.detail}", compiled.ok)
        return LinkFormula.evaluate(compiled.program!!, value, band)
    }

    private fun problem(text: String) = LinkFormula.compile(text).problem

    @Test
    fun `blank means no formula, which is fine`() {
        val c = LinkFormula.compile("   ")
        assertTrue(c.ok)
        assertNull(c.program)
    }

    @Test
    fun `the spec's own example works`() {
        assertEquals(0.3f, eval("value + (fft[band] * 0.25)", 0.2f, 0.4f)!!, 1e-6f)
    }

    @Test
    fun `precedence is school arithmetic`() {
        assertEquals(1f, eval("value + band * 2 - 1", 1f, 0.5f)!!, 1e-6f)
        assertEquals(1.5f, eval("(value + band) * 2 - 1", 0.5f, 0.75f)!!, 1e-6f)
        assertEquals(-0.5f, eval("-band + value", 0.25f, 0.75f)!!, 1e-6f)
    }

    @Test
    fun `pasted operators are read as the ones on the keyboard`() {
        assertEquals(0.1f, eval("value × band ÷ 2 − 0.1", 1f, 0.4f)!!, 1e-6f)
    }

    @Test
    fun `the functions do what they say`() {
        assertEquals(0.2f, eval("min(value, band)", 0.2f, 0.9f)!!, 1e-6f)
        assertEquals(0.9f, eval("max(value, band)", 0.2f, 0.9f)!!, 1e-6f)
        assertEquals(0.4f, eval("abs(-band)", 0f, 0.4f)!!, 1e-6f)
        assertEquals(1f, eval("clamp(band * 3, 0, 1)", 0f, 0.5f)!!, 1e-6f)
        assertEquals(0.75f, eval("CLAMP(Band * 3, 0, 1)", 0f, 0.25f)!!, 1e-6f)
    }

    @Test
    fun `dividing by zero gives zero instead of breaking`() {
        assertEquals(0f, eval("band / (value - value)", 1f, 1f)!!, 0f)
    }

    @Test
    fun `an answer too big to be a number is no answer`() {
        val big = "10000000000000000000"
        assertNull(eval("band * $big * $big * $big", 0f, 1f))
    }

    @Test
    fun `every mistake gets its own plain message`() {
        assertEquals(LinkFormula.Problem.ENDS_WITH_OPERATOR, problem("value + band *"))
        assertEquals(LinkFormula.Problem.NO_BAND, problem("value + 2"))
        assertEquals(LinkFormula.Problem.MISSING_BRACKET, problem("(value + band"))
        assertEquals(LinkFormula.Problem.MISSING_BRACKET, problem("value + band)"))
        assertEquals(LinkFormula.Problem.NOT_FINISHED, problem("value band"))
        assertEquals(LinkFormula.Problem.TOO_LONG, problem("band+".repeat(30) + "band"))
        val unknown = LinkFormula.compile("value + loud * band")
        assertEquals(LinkFormula.Problem.UNKNOWN_WORD, unknown.problem)
        assertEquals("loud", unknown.detail)
        val symbol = LinkFormula.compile("value \$ band")
        assertEquals(LinkFormula.Problem.BAD_SYMBOL, symbol.problem)
        assertEquals("\$", symbol.detail)
    }

    @Test
    fun `a program never needs more stack than it is given`() {
        // Deep nesting to the right keeps every operand on the stack at once.
        val deep = (1..16).joinToString("") { "band+(" } + "band" + ")".repeat(16)
        assertTrue(deep.length <= LinkFormula.MAX_LENGTH)
        assertEquals(LinkFormula.Problem.TOO_LONG, problem(deep))
        val fine = (1..10).joinToString("") { "band+(" } + "band" + ")".repeat(10)
        assertEquals(11f, eval(fine, 0f, 1f)!!, 1e-6f)
    }

    @Test
    fun `a broken program is refused at run time too`() {
        // Hand-made, as a corrupted saved file could hold.
        assertNull(LinkFormula.evaluate(floatArrayOf(LinkFormula.OP_ADD, 0f), 0f, 0f))
        assertNull(LinkFormula.evaluate(floatArrayOf(LinkFormula.OP_BAND, 0f, LinkFormula.OP_BAND, 0f), 0f, 0f))
        assertNull(LinkFormula.evaluate(floatArrayOf(99f, 0f), 0f, 0f))
    }
}
