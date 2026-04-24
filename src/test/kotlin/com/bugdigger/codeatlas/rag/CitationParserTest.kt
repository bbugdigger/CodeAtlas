package com.bugdigger.codeatlas.rag

import org.junit.Assert.assertEquals
import org.junit.Test

class CitationParserTest {

    private fun parse(vararg deltas: String): List<CitationParser.Segment> {
        val p = CitationParser()
        val out = mutableListOf<CitationParser.Segment>()
        for (d in deltas) out += p.feed(d)
        out += p.finish()
        return merge(out)
    }

    /** Collapse adjacent Text segments so assertions are stable regardless of flush timing. */
    private fun merge(segments: List<CitationParser.Segment>): List<CitationParser.Segment> {
        val out = mutableListOf<CitationParser.Segment>()
        for (s in segments) {
            val last = out.lastOrNull()
            if (s is CitationParser.Segment.Text && last is CitationParser.Segment.Text) {
                out[out.size - 1] = CitationParser.Segment.Text(last.value + s.value)
            } else {
                out += s
            }
        }
        return out
    }

    @Test
    fun parsesSingleAndMultiDigitCitations() {
        val r = parse("see [1] and [12].")
        assertEquals(
            listOf(
                CitationParser.Segment.Text("see "),
                CitationParser.Segment.Citation(1),
                CitationParser.Segment.Text(" and "),
                CitationParser.Segment.Citation(12),
                CitationParser.Segment.Text("."),
            ),
            r,
        )
    }

    @Test
    fun escapedBracketIsLiteral() {
        val r = parse("literal \\[1] brackets")
        assertEquals(listOf(CitationParser.Segment.Text("literal [1] brackets")), r)
    }

    @Test
    fun bracketedNonNumericIsLiteral() {
        val r = parse("todo [TODO] later")
        assertEquals(listOf(CitationParser.Segment.Text("todo [TODO] later")), r)
    }

    @Test
    fun citationSplitAcrossDeltasIsRecognized() {
        val r = parse("prefix [", "1] suffix")
        assertEquals(
            listOf(
                CitationParser.Segment.Text("prefix "),
                CitationParser.Segment.Citation(1),
                CitationParser.Segment.Text(" suffix"),
            ),
            r,
        )
    }

    @Test
    fun trailingPartialBracketFlushesAsTextOnFinish() {
        val r = parse("ending [1")
        assertEquals(listOf(CitationParser.Segment.Text("ending [1")), r)
    }

    @Test
    fun adjacentCitationsParseSeparately() {
        val r = parse("see [1][2]")
        assertEquals(
            listOf(
                CitationParser.Segment.Text("see "),
                CitationParser.Segment.Citation(1),
                CitationParser.Segment.Citation(2),
            ),
            r,
        )
    }
}
