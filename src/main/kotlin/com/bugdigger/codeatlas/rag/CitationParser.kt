package com.bugdigger.codeatlas.rag

/**
 * Streaming parser for inline `[n]` citations in assistant text.
 *
 * Behavior (lenient):
 *  - `[12]` parses as citation index 12.
 *  - `\[` is treated as a literal bracket and NOT a citation (escape is consumed).
 *  - `[` with non-digit content (e.g. `[TODO]`) emits as literal text.
 *  - A trailing partial `[1` or `\` at the end of a delta is buffered and re-examined
 *    when the next delta arrives, so citations split across streaming boundaries are
 *    still recognized.
 *
 * Output is a sequence of [Segment.Text] and [Segment.Citation] spans. Callers render
 * Text verbatim and Citation as a clickable link to the source chunk at `index - 1`.
 */
class CitationParser {

    sealed class Segment {
        data class Text(val value: String) : Segment()
        data class Citation(val index: Int) : Segment()
    }

    private val pending = StringBuilder()

    /** Feed a new delta. Returns the segments that are now safely resolvable. */
    fun feed(delta: String): List<Segment> {
        if (delta.isEmpty()) return emptyList()
        pending.append(delta)
        return drain(finalFlush = false)
    }

    /** Call once after the stream ends. Flushes any buffered partial input as plain text. */
    fun finish(): List<Segment> = drain(finalFlush = true)

    private fun drain(finalFlush: Boolean): List<Segment> {
        val out = mutableListOf<Segment>()
        val text = StringBuilder()
        var i = 0
        val s = pending
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> {
                    // Need one more char to decide.
                    if (i + 1 >= s.length) {
                        if (finalFlush) {
                            text.append('\\'); i++
                        } else {
                            break // keep `\` buffered
                        }
                    } else {
                        val next = s[i + 1]
                        if (next == '[') {
                            text.append('['); i += 2
                        } else {
                            text.append('\\'); i++
                        }
                    }
                }
                c == '[' -> {
                    // Try to parse [digits]
                    var j = i + 1
                    while (j < s.length && s[j].isDigit()) j++
                    if (j == i + 1) {
                        // No digits after `[`.
                        if (j >= s.length) {
                            // `[` at end of buffer: buffer for next delta (or flush literal on finish).
                            if (finalFlush) {
                                text.append('['); i++
                            } else {
                                break
                            }
                        } else {
                            // `[X...` — not a citation. Emit literal `[` and continue.
                            text.append('['); i++
                        }
                    } else if (j < s.length) {
                        if (s[j] == ']') {
                            val n = s.substring(i + 1, j).toIntOrNull()
                            if (n != null) {
                                if (text.isNotEmpty()) {
                                    out += Segment.Text(text.toString()); text.setLength(0)
                                }
                                out += Segment.Citation(n)
                                i = j + 1
                            } else {
                                // Overflow or malformed number: treat as literal.
                                text.append(s, i, j + 1)
                                i = j + 1
                            }
                        } else {
                            // `[123X` — not a citation. Emit literal up to and including the non-digit.
                            text.append(s, i, j + 1)
                            i = j + 1
                        }
                    } else {
                        // Ran out of input mid-`[digits`. Keep buffered for next delta.
                        if (finalFlush) {
                            text.append(s, i, s.length); i = s.length
                        } else {
                            break
                        }
                    }
                }
                else -> {
                    text.append(c); i++
                }
            }
        }
        if (text.isNotEmpty()) out += Segment.Text(text.toString())
        // Keep unconsumed tail for the next delta.
        val remaining = s.substring(i)
        s.setLength(0)
        s.append(remaining)
        return out
    }
}
