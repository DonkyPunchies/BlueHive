package com.example.bluehive.webview.hls

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way HlsPlayerActivity can close must be classified, in source.
 *
 * WHY A TEST THAT READS CODE. The same defect has now been found four separate
 * times, each in a different branch, each by a viewer rather than by me: a
 * recovery path runs out of options and calls finish(), the activity closes
 * without setting RESULT_SOURCE_FAILED, the detail screen never learns, the
 * failover chain never advances, and from the sofa it is indistinguishable from
 * a crash. Auditing the branches by hand fixed the four that had already bitten
 * and did nothing about the fifth.
 *
 * So the rule is enforced instead of remembered. Every finish() must carry a
 * marker naming which kind of ending it is:
 *
 *   EXIT: source-failed    this source cannot play it; the chain should move on.
 *                          ONLY legal inside abandonSourceWithNotice, which is
 *                          what actually sets the result.
 *   EXIT: not-the-source   a decoder fault, a malformed launch -- something the
 *                          next source would hit identically, so failing over
 *                          would only repeat it.
 *   EXIT: dismissed        the viewer left, the episode ended, the idle watcher
 *                          closed us. Nothing failed.
 *
 * Adding a finish() without one fails this test. That is the point: the next
 * person has to decide which of the three it is, and deciding is the step that
 * was being skipped.
 */
class PlayerExitPolicyTest {

    private val markers = listOf("EXIT: source-failed", "EXIT: not-the-source", "EXIT: dismissed")

    private val source: List<String> by lazy {
        // Gradle runs unit tests with the module directory as the working dir.
        val f = File("src/main/java/com/example/bluehive/webview/hls/HlsPlayerActivity.kt")
        assertTrue(
            "Cannot find HlsPlayerActivity.kt at ${f.absolutePath} — if the file moved, " +
                "move this test's path with it rather than deleting the check.",
            f.exists(),
        )
        f.readLines()
    }

    /** Lines that actually CALL finish(), as opposed to mentioning it in prose. */
    private fun callSites(): List<Pair<Int, String>> =
        source.withIndex()
            .map { (i, l) -> (i + 1) to l }
            .filter { (_, l) ->
                // Prose mentions finish() too -- this file documents past bugs
                // in detail. A KDoc continuation or a line comment is not a call.
                val t = l.trimStart()
                if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) false
                else Regex("""(^|[^.\w])finish\(\)""")
                    .containsMatchIn(l.substringBefore("//"))
            }

    @Test
    fun `every finish call is classified`() {
        val unclassified = callSites().filter { (_, l) -> markers.none { it in l } }
        assertTrue(
            "Unclassified finish() in HlsPlayerActivity — add one of $markers " +
                "as a trailing comment, after deciding which it is:\n" +
                unclassified.joinToString("\n") { (n, l) -> "  line $n: ${l.trim()}" },
            unclassified.isEmpty(),
        )
    }

    @Test
    fun `there is at least one of each kind, so the markers stay honest`() {
        val all = callSites().joinToString("\n") { it.second }
        markers.forEach { m ->
            assertTrue("No finish() marked '$m' — has the policy changed?", m in all)
        }
    }

    @Test
    fun `only abandonSourceWithNotice may report a failed source`() {
        // Reporting the failure and closing have to happen together. A bare
        // finish() marked source-failed would close WITHOUT setting the result,
        // which is the exact bug this file exists to prevent.
        val reporting = callSites().filter { (_, l) -> "EXIT: source-failed" in l }
        assertEquals(
            "Exactly one finish() may be marked source-failed, inside " +
                "abandonSourceWithNotice. Found: $reporting",
            1, reporting.size,
        )
        val (line, _) = reporting.single()
        val enclosing = source.take(line).lastOrNull { it.contains("private fun ") }
        assertTrue(
            "The source-failed finish() is in `$enclosing`, not abandonSourceWithNotice.",
            enclosing?.contains("abandonSourceWithNotice") == true,
        )
    }
}
