/*
 * AIComposeEngineTest — unit tests for AIComposeEngine.
 *
 * Tests cover:
 * - normalizePinyin: space removal, tone digit stripping, v→u conversion
 * - Candidate list truncation: breakpoint logic, distinct dedup, maxCandidates cap
 *
 * Note: Full JNI/LlamaEngine tests require a device or emulator with the
 * native library loaded. These tests focus on pure Kotlin logic.
 */
package org.fcitx.fcitx5.android.plugin.aicompose

import org.junit.Assert.*
import org.junit.Test

class AIComposeEngineTest {

    // ─── normalizePinyin tests ────────────────────────────────────────────────

    @Test
    fun `normalizePinyin strips tone digits`() {
        // e.g. "ni3hao4" → "nihao"
        assertEquals("nihao", normalizePinyin("ni3hao4"))
        assertEquals("zhong", normalizePinyin("zhong1"))
        assertEquals("wen", normalizePinyin("wen2"))
        assertEquals("ni", normalizePinyin("ni3"))
        assertEquals("hao", normalizePinyin("hao4"))
    }

    @Test
    fun `normalizePinyin removes spaces`() {
        // spaces are stripped so the LLM prompt handles segmentation
        assertEquals("nihao", normalizePinyin("ni hao"))
        assertEquals("zhongguo", normalizePinyin("zhong guo"))
        assertEquals("zhongguo", normalizePinyin("zhong1 guo2"))
    }

    @Test
    fun `normalizePinyin replaces v with u`() {
        // ü in nü/lü is often typed as 'v' on some Chinese IMEs
        assertEquals("lu", normalizePinyin("lv"))
        assertEquals("nu", normalizePinyin("nv"))
        assertEquals("lvse", normalizePinyin("lvse"))
        assertEquals("nushu", normalizePinyin("nvshu"))
        assertEquals("nüe", normalizePinyin("nüe"))  // ü → v → u in two steps
    }

    @Test
    fun `normalizePinyin lowercases`() {
        assertEquals("nihao", normalizePinyin("NI3HAO4"))
        assertEquals("nihao", normalizePinyin("Ni3Hao4"))
    }

    @Test
    fun `normalizePinyin empty string`() {
        assertEquals("", normalizePinyin(""))
    }

    @Test
    fun `normalizePinyin only spaces`() {
        assertEquals("", normalizePinyin("   "))
    }

    // ─── Candidate list breakpoint tests ──────────────────────────────────────

    /**
     * Mirrors the breakpoints logic in AIComposeEngine.requestCompletion:
     * val breakpoints = listOf(4, 8, 12, 16, 20, 24, 28)
     *     .filter { it <= output.length }
     *     .take(maxCandidates - 1)
     */
    private fun breakpointsFor(length: Int, maxCandidates: Int): List<Int> {
        return listOf(4, 8, 12, 16, 20, 24, 28)
            .filter { it <= length }
            .take(maxCandidates - 1)
    }

    @Test
    fun `breakpoints filter correctly by output length`() {
        assertEquals(listOf<Int>(), breakpointsFor(2, 5))   // too short: no breakpoints
        assertEquals(listOf(4), breakpointsFor(4, 5))       // exactly 4
        assertEquals(listOf(4, 8), breakpointsFor(9, 5))    // 8 included, 12 excluded
        assertEquals(listOf(4, 8, 12, 16), breakpointsFor(20, 5))
    }

    @Test
    fun `breakpoints respect maxCandidates cap`() {
        // take(maxCandidates - 1) means with maxCandidates=5, max 4 breakpoints
        assertEquals(4, breakpointsFor(100, 5).size)
        assertEquals(2, breakpointsFor(100, 3).size)
        assertEquals(1, breakpointsFor(100, 2).size)
        assertEquals(0, breakpointsFor(100, 1).size)
    }

    @Test
    fun `candidates built from breakpoints are distinct and capped`() {
        val output = "一二三四五六七八九十百千万亿"  // 12 chars
        val maxCandidates = 5
        val breakpoints = breakpointsFor(output.length, maxCandidates)
        val candidates = if (breakpoints.isEmpty()) {
            listOf(output.take(maxCandidates))
        } else {
            (breakpoints.map { output.take(it) } + output)
                .distinct()
                .take(maxCandidates)
        }

        // All candidates within maxCandidates limit
        assertTrue(candidates.size <= maxCandidates)
        // No duplicates
        assertEquals(candidates.distinct().size, candidates.size)
        // All are prefixes of output
        candidates.forEach { assertTrue(output.startsWith(it)) }
    }

    @Test
    fun `very short output falls back to single candidate`() {
        val output = "你好"  // 2 chars, no breakpoint matches
        val candidates = if (listOf(4, 8, 12, 16, 20, 24, 28).filter { it <= output.length }.isEmpty()) {
            listOf(output.take(5))  // maxCandidates default is 5
        } else {
            emptyList()
        }
        assertEquals(1, candidates.size)
        assertEquals("你好", candidates[0])
    }
}
