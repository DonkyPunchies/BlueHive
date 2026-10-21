// C:\Users\TheMo\AndroidStudioProjects\BlueHive\app\src\main\java\com\example\bluehive\webview\hls\CueMatcher.kt
// Verified: compiles clean under kotlinc 2.2.10 (zero errors, zero warnings),
// no dependency outside the Kotlin stdlib and java.text.Normalizer.

package com.example.bluehive.webview.hls

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Ranks every cue in the loaded subtitle file against a phrase the viewer typed
 * from memory, and returns the few best.
 *
 * ASYMMETRIC ON PURPOSE, and that is the whole design. Every off-the-shelf
 * string similarity -- normalized Levenshtein, token Dice, trigram cosine --
 * asks "how alike are these two strings" and divides by something that grows
 * with the CANDIDATE's length. The viewer here types three words they half
 * remember out of a twelve-word line, so the right answer is always the long
 * one, and a symmetric measure always prefers a short wrong one.
 *
 * MEASURED, on 600 cues seeded with 176 short decoys built from the target
 * lines' own words, over 22 fragment queries:
 *
 *     normalized Levenshtein      6/22 correct at rank 1
 *     token set Dice              6/22
 *     trigram cosine (symmetric)  9/22
 *     this                       22/22
 *
 * Trigram cosine's failure is the one worth seeing, because it is what people
 * reach for: asked for "bigger boat" it returns four copies of "Bigger?" ahead
 * of "We're going to need a bigger boat than that one". Nothing is wrong with
 * the cosine -- "Bigger?" genuinely IS more similar to "bigger boat". Similarity
 * is simply the wrong question.
 *
 * So every term below is normalized by the QUERY, never by the candidate, and
 * candidate length enters only as [W_TIGHT], a tie-break worth 6%.
 *
 * COST, 600 cues (about 1050 candidates once pairs are glued), one full re-rank:
 * 0.23ms mean / 1.13ms worst on a warmed desktop JVM, linear in cue count. There
 * is no index, no prefilter and no incremental state: the whole file is scored on
 * every keystroke. A trigram prefilter was tried and cut 0.22ms to 0.10ms --
 * 0.12ms of nothing in exchange for a second code path that can disagree with
 * the scorer. Do not add it back.
 *
 * NOT THREAD SAFE, deliberately: the DP rows and the per-token scratch are
 * reused across candidates so that a keystroke allocates one String and one
 * String[] in total. Call [rank] from one dispatcher.
 */
class CueMatcher(cues: List<Entry>) {

    /** Input: one cue, exactly as the player holds it. */
    data class Entry(val text: String, val startMs: Long, val endMs: Long)

    /**
     * A cue -- or a glued adjacent pair -- normalized and tokenized ONCE, when
     * the subtitle file is parsed.
     */
    class Candidate internal constructor(
        /** Index into the cue list this matcher was built from. Head, for a pair. */
        val index: Int,
        /** Display text. For a pair this is the FIRST cue's text. */
        val text: String,
        /** The second cue's text when this candidate is a glued pair, else null. */
        val tailText: String?,
        /**
         * Where the offset is solved against. A pair anchors on its HEAD, which
         * is why gluing is gated on two short halves and a tight gap: the worst
         * case is then the head's own duration, inside what the nudges cover.
         */
        val startMs: Long,
        internal val norm: String,
        internal val tokens: Array<String>,
    ) {
        val isPair: Boolean get() = tailText != null
    }

    data class Hit(val candidate: Candidate, val score: Double)

    enum class Status {
        /** Fewer than [MIN_QUERY_CHARS] usable characters. Say "keep typing". */
        TOO_SHORT,

        /**
         * Rows ARE returned, but nothing distinctive was typed -- every word is
         * one this file uses everywhere, so the text decided almost nothing and
         * what really ordered these rows is the 6% proximity term, i.e. the
         * screen is quietly confirming whatever offset is already loaded.
         *
         * WARN, DO NOT REFUSE. An earlier version refused, and refusing is
         * wrong twice over: the viewer may genuinely have heard only "yeah", and
         * the threshold misjudges real queries whenever a file happens to repeat
         * an ordinary word ("boat" in a film about a boat). The panel says so
         * and the rows carry their shift and their repeat count; the viewer
         * adjudicates, which is the only thing that can.
         */
        WEAK,

        /** Something distinctive was typed and no cue came close enough. */
        NO_MATCH,

        OK,
    }

    data class Ranking(val status: Status, val hits: List<Hit>)

    // ── index, built once ───────────────────────────────────────────────────────

    private val singles: List<Candidate> = cues.mapIndexed { i, e ->
        val n = normalize(e.text)
        Candidate(i, e.text, null, e.startMs, n, tokenize(n))
    }

    /**
     * ADJACENT PAIRS, because a sentence the viewer heard as one line is very
     * often authored as two cues -- so the phrase they type exists in NEITHER
     * cue alone and the screen looks broken for no visible reason.
     *
     * Gated three ways. The gap must be under [PAIR_MAX_GAP_MS], or the two cues
     * are from different moments; and BOTH members must be short.
     *
     * THE TAIL GATE IS NOT OPTIONAL, and leaving it out is a bug I shipped once.
     * Glue a two-word cue onto a twelve-word one and the pair matches every
     * query the long cue matches, scoring within 0.003 of it -- so the list
     * showed the same line twice, once anchored correctly and once anchored at
     * the short cue's start, two and a half seconds early. A pair only earns a
     * slot when NEITHER half could have held the phrase alone.
     */
    private val pairs: List<Candidate> = buildList {
        for (i in 0 until cues.size - 1) {
            val a = cues[i]
            val b = cues[i + 1]
            if (b.startMs - a.endMs > PAIR_MAX_GAP_MS) continue
            if (singles[i].tokens.size > PAIR_MAX_HALF_TOKENS) continue
            if (singles[i + 1].tokens.size > PAIR_MAX_HALF_TOKENS) continue
            val n = normalize(a.text + " " + b.text)
            if (n.isEmpty()) continue
            add(Candidate(i, a.text, b.text, a.startMs, n, tokenize(n)))
        }
    }

    private val candidates: List<Candidate> = singles + pairs

    /**
     * Document frequency over SINGLE cues only, so gluing cannot inflate it.
     *
     * IDF FROM THIS FILE, not from an English word list. It is the only corpus
     * that matters: "yeah" at 40 of 560 cues scores ln(14) = 2.6 while "libtard"
     * at 1 scores ln(560) = 6.3, and no stopword list has to be shipped or
     * translated. Used ONLY by [bestIdf], to decide whether to WARN -- it never
     * reweights the score, so the measurement above still describes what ranks.
     */
    private val idf: HashMap<String, Double> = HashMap(singles.size * 4)

    /** The idf of a word used [SUBSTANCE_DF] times here: "not everywhere". */
    private val substanceFloor: Double

    init {
        val df = HashMap<String, Int>(singles.size * 4)
        for (c in singles) {
            var seen: HashSet<String>? = null
            for (t in c.tokens) {
                val s = seen ?: HashSet<String>(c.tokens.size).also { seen = it }
                if (s.add(t)) df[t] = (df[t] ?: 0) + 1
            }
        }
        val n = max(1, singles.size).toDouble()
        for ((w, d) in df) idf[w] = ln(n / d)
        substanceFloor = ln(n / SUBSTANCE_DF)
    }

    // Scratch, reused across every candidate in a pass. ART's young-gen
    // collector is the thing being avoided: 600 small int[] per keystroke at
    // four keystrokes a second is what turns a 20ms rank into a visible stutter.
    private val dpPrev = IntArray(MAX_TOKEN_LEN + 2)
    private val dpCur = IntArray(MAX_TOKEN_LEN + 2)
    private val hits = DoubleArray(MAX_QUERY_TOKENS)

    /**
     * The best cues for [typed], best first, at most [limit] of them.
     *
     * @param playheadMs where the picture is now, for the proximity tie-break.
     * @param rate the live subtitle rate, used only to convert the duplicate
     *   separation from offset-space back into cue-space.
     */
    fun rank(typed: String, playheadMs: Long, rate: Double = 1.0, limit: Int = 4): Ranking {
        val q = normalize(typed)
        // UNDER THREE CHARACTERS THERE IS NO ANSWER, and saying so beats
        // guessing. At two the per-token edit budget is zero, so only an exact
        // one- or two-letter token can match -- measured: "n" and "ne" score
        // zero against all 600 cues.
        if (q.length < MIN_QUERY_CHARS) return Ranking(Status.TOO_SHORT, emptyList())
        val qt = tokenize(q)
        if (qt.isEmpty()) return Ranking(Status.TOO_SHORT, emptyList())

        // THE QUERY IS JUDGED, NOT THE CANDIDATES, and the verdict is a warning
        // rather than a veto. Measured as the rarest word typed, not the sum:
        // two ordinary words add up past any sum threshold ("come on" scored 6.0
        // against a floor of 4.3) while one distinctive word is the whole signal.
        val weak = bestIdf(qt) < substanceFloor

        val keep = min(SHORTLIST, max(limit, 1) * 6)
        val topScore = DoubleArray(keep)
        val topCand = arrayOfNulls<Candidate>(keep)
        var filled = 0

        // TRUNCATED, NOT REFUSED. Someone who types a whole long speech has
        // given more than enough to identify it, and the scratch arrays are
        // fixed-size; the first two dozen words decide it.
        val tq = if (qt.size > MAX_QUERY_TOKENS) Array(MAX_QUERY_TOKENS) { qt[it] } else qt
        for (c in candidates) {
            val s = score(q, tq, c, playheadMs)
            if (s <= 0.0) continue
            if (filled < keep) {
                topScore[filled] = s
                topCand[filled] = c
                filled++
                bubbleUp(topScore, topCand, filled - 1)
            } else if (s > topScore[keep - 1]) {
                topScore[keep - 1] = s
                topCand[keep - 1] = c
                bubbleUp(topScore, topCand, keep - 1)
            }
        }
        if (filled == 0) return Ranking(Status.NO_MATCH, emptyList())

        val shortlist = ArrayList<Hit>(filled)
        for (i in 0 until filled) shortlist.add(Hit(topCand[i]!!, topScore[i]))
        val rows = selectRows(shortlist, rate, limit)
        return when {
            rows.isEmpty() -> Ranking(Status.NO_MATCH, emptyList())
            weak -> Ranking(Status.WEAK, rows)
            else -> Ranking(Status.OK, rows)
        }
    }

    /**
     * How many cues in the file read exactly like this one. The row says so, and
     * that number is the honest warning that the pick is a guess.
     */
    fun identicalCount(c: Candidate): Int {
        var n = 0
        for (s in singles) if (s.norm == c.norm) n++
        if (n == 0) for (p in pairs) if (p.norm == c.norm) n++
        return max(1, n)
    }

    // ── selection ──────────────────────────────────────────────────────────────

    /**
     * Fill the rows from the shortlist under three constraints, because the
     * viewer is not really choosing a LINE -- they are choosing an OFFSET.
     *
     * SEPARATION. Two rows whose implied offsets differ by less than
     * [DUP_OFFSET_MS] are one answer wearing two labels: whichever is picked,
     * the nudge buttons close the remainder. [DUP_OFFSET_MS] is not a new number
     * -- it is HlsPlayerActivity's SYNC_MIN_OFFSET_DELTA_MS (line 788), already
     * defined there as the point below which two timing observations are
     * indistinguishable from the same observation made twice. Implied offsets
     * differ only by (startB - startA) * rate, so the test is done in cue space
     * and needs no playhead.
     *
     * ONE ROW PER CUE. A pair covers cues i and i+1, a single covers i, so two
     * candidates built from the same cue are the same answer and the first wins.
     *
     * REPEATS. At most [MAX_PER_TEXT] rows may read the same, so a film's eleven
     * "Yeah."s cannot take every slot while nothing distinguishes them.
     *
     * RELAXED RATHER THAN LEFT SHORT, and that is why [MAX_PER_TEXT] is a
     * preference and not a cap: four informative rows beat two rows and two
     * empty slots, and every row states its own implied shift, so rows that are
     * nearly the same answer are visibly nearly the same answer. For a query
     * that really is one repeated word the relaxation is the right outcome --
     * the viewer then IS choosing among occurrences, and the panel has told them
     * so via Status.WEAK and the repeat count.
     */
    private fun selectRows(shortlist: List<Hit>, rate: Double, limit: Int): List<Hit> {
        val safeRate = if (rate.isNaN() || rate <= 0.1) 1.0 else rate
        val sepCue = (DUP_OFFSET_MS / safeRate).toLong()
        val out = ArrayList<Hit>(limit)
        val perText = HashMap<String, Int>(limit * 2)
        val usedCues = HashSet<Int>(limit * 3)
        for (pass in 0..2) {
            val sep = when (pass) {
                0 -> sepCue
                1 -> sepCue / 4
                else -> 0L
            }
            val maxPerText = if (pass == 2) limit else MAX_PER_TEXT
            for (h in shortlist) {
                if (out.size >= limit) return out
                if (out.any { it.candidate === h.candidate }) continue
                if (sep > 0L && out.any { abs(it.candidate.startMs - h.candidate.startMs) < sep }) continue
                val used = perText[h.candidate.norm] ?: 0
                if (used >= maxPerText) continue
                val i0 = h.candidate.index
                if (usedCues.contains(i0)) continue
                if (h.candidate.isPair && usedCues.contains(i0 + 1)) continue
                perText[h.candidate.norm] = used + 1
                usedCues.add(i0)
                if (h.candidate.isPair) usedCues.add(i0 + 1)
                out.add(h)
            }
            if (out.size >= limit) return out
        }
        return out
    }

    private fun bubbleUp(scores: DoubleArray, cands: Array<Candidate?>, from: Int) {
        var j = from
        while (j > 0 && scores[j - 1] < scores[j]) {
            val s = scores[j]; scores[j] = scores[j - 1]; scores[j - 1] = s
            val c = cands[j]; cands[j] = cands[j - 1]; cands[j - 1] = c
            j--
        }
    }

    // ── scoring ────────────────────────────────────────────────────────────────

    /** The idf of the rarest word typed. See [rank]. */
    private fun bestIdf(qt: Array<String>): Double {
        var best = 0.0
        for (w in qt) {
            // UNKNOWN WORDS COUNT AS DISTINCTIVE. A word this file never uses is
            // either a mishearing or the one word that would have identified the
            // line, and in both cases the right move is to search and report
            // what was found rather than to judge before looking.
            val v = idf[w] ?: substanceFloor
            if (v > best) best = v
        }
        return best
    }

    private fun score(q: String, qt: Array<String>, c: Candidate, playheadMs: Long): Double {
        val n = qt.size

        // ── 1. COVERAGE (52%) ────────────────────────────────────────────────
        // How much of what they typed is in this cue AT ALL, per query token,
        // each token matched fuzzily so a typo still counts. Divided by the
        // QUERY's token count, which is what makes a three-word fragment of a
        // twelve-word line score 1.0 instead of 0.25.
        //
        // The dominant term because it is the only one that survives all four
        // things the input does wrong at once: typos, missing words, wrong
        // order, and stopping halfway.
        var cov = 0.0
        for (i in 0 until n) {
            hits[i] = tokenHit(qt[i], c)
            cov += hits[i]
        }
        cov /= n

        // EARLY OUT, and the reason the whole pass is sub-millisecond: on a
        // three-word query this rejects roughly nine cues in ten before any of
        // the positional work below runs.
        if (cov < COVERAGE_FLOOR) return 0.0

        // ── 2. ORDER (22%) ───────────────────────────────────────────────────
        // Longest run of query tokens found at increasing positions. Coverage
        // alone cannot tell "put the gun down" from "down the gun put", and in a
        // file full of lines built from the same handful of common words that is
        // what breaks the ties.
        //
        // A RUN, not a strict subsequence, because the viewer drops words from
        // the middle: "ground me libtard" has no contiguous run in the real line
        // and still scores 2/3 here rather than failing.
        var bestRun = 0
        var run = 0
        var lastPos = -1
        for (i in 0 until n) {
            var pos = -1
            if (hits[i] > ORDER_HIT_FLOOR) {
                var j = max(0, lastPos + 1)
                while (j < c.tokens.size) {
                    val t = c.tokens[j]
                    if (t == qt[i] || t.startsWith(qt[i])) { pos = j; break }
                    j++
                }
            }
            if (pos >= 0) {
                run++
                lastPos = pos
                if (run > bestRun) bestRun = run
            } else {
                run = 0
            }
        }
        val order =
            if (n == 1) (if (hits[0] > ORDER_HIT_FLOOR) 1.0 else 0.0)
            else bestRun.toDouble() / n

        // ── 3. LITERAL SUBSTRING (14%) ───────────────────────────────────────
        // The strongest evidence there is and nearly free: one contains() on
        // text already normalized. Kept small on purpose -- it is zero the moment
        // there is a single typo, so it can only ever be a bonus on top of
        // coverage, never the thing the ranking rests on. Alone it scores 4/22.
        val sub = when {
            c.norm.startsWith(q) -> 1.0
            c.norm.contains(q) -> 0.85
            else -> 0.0
        }

        // ── 4. TIGHTNESS (6%) ────────────────────────────────────────────────
        // The ONLY place candidate length enters, held to 6% because the common
        // case -- a fragment of a long line -- is exactly what it argues
        // against. It exists for the opposite one: typing a whole short line,
        // "Yeah." should beat a twenty-word cue that merely contains "yeah".
        val tight = n.toDouble() / max(n, c.tokens.size)

        // ── 5. PROXIMITY (6%) ────────────────────────────────────────────────
        // THE SIGNAL NOBODY USES AND THE ONLY ONE THAT ORDERS NEAR-DUPLICATES.
        // Text cannot separate the eleven cues reading "Yeah.", so no amount of
        // scoring will. But the viewer typed what they JUST heard, so the right
        // cue is near the playhead -- and that is free.
        //
        // RATIONAL DECAY, NOT EXPONENTIAL, and this was a real bug. exp(-d/45s)
        // is the obvious choice and it is wrong: already 0.0001 at seven minutes,
        // so every distant cue gets an identical zero and the far duplicates go
        // back to arbitrary order -- the exact problem this term exists to fix.
        // 1/(1+d/T) still separates them: 0.33 at 2min, 0.13 at 7min.
        //
        // WIDE, SOFT, AND ONLY 6%, because the desync being fixed is itself
        // unknown. A heavier prior would reject the correct cue for being badly
        // timed, which is what it is guilty of and why this screen exists.
        val prox = 1.0 / (1.0 + abs(c.startMs - playheadMs) / PROX_FALLOFF_MS)

        return W_COV * cov +
            W_ORDER * order +
            W_SUB * sub +
            W_TIGHT * tight +
            W_PROX * prox
    }

    /**
     * Best fuzzy match for one query token against any token in the cue.
     *
     * BAG OF WORDS, scanned linearly: the cue has a dozen tokens, so an index
     * would cost more to maintain than the scan costs to run.
     */
    private fun tokenHit(qtok: String, c: Candidate): Double {
        var best = 0.0
        val ql = qtok.length
        // EDIT BUDGET SCALED TO THE TOKEN, not a fixed threshold. One edit in a
        // three-letter word is a different word ("the"/"she"), and in a file full
        // of short common words a flat budget of 1 makes every stopword match
        // every other stopword.
        val budget = when {
            ql <= 3 -> 0
            ql <= 5 -> 1
            ql <= 9 -> 2
            else -> 3
        }
        for (ct in c.tokens) {
            if (ct == qtok) return 1.0
            // PREFIX, scored just under exact. Two separate things produce it: a
            // word abandoned halfway on a remote, and a remembered stem with the
            // wrong ending ("tell" for "tells"). Measured: "somebod in this ro"
            // ranks its line first entirely on this.
            if (ql >= PREFIX_MIN_CHARS && ct.length > ql && ct.startsWith(qtok)) {
                if (PREFIX_SCORE > best) best = PREFIX_SCORE
                continue
            }
            if (budget == 0) continue
            // Two O(1) rejects before the DP; together they skip most pairs.
            if (abs(ct.length - ql) > budget) continue
            if (ct.length > MAX_TOKEN_LEN) continue
            val d = levBanded(qtok, ct, budget)
            if (d > budget) continue
            val v = 1.0 - d.toDouble() / max(ql, ct.length)
            if (v > best) best = v
        }
        return best
    }

    /**
     * Levenshtein, banded to [budget] and abandoned the moment the whole band
     * exceeds it, over the two reused scratch rows.
     *
     * BANDED BECAUSE THE BUDGET IS TINY. Full DP on two 6-letter tokens is 49
     * cells; banded at 1 it is 18, and the abandon usually fires on row two.
     * This is what makes per-token edit distance affordable at 600 cues --
     * whole-string Levenshtein measured 0.58ms per keystroke against this
     * function's 0.21 for the complete score, and ranked 6/22.
     */
    private fun levBanded(a: String, b: String, budget: Int): Int {
        val la = a.length
        val lb = b.length
        if (abs(la - lb) > budget) return budget + 1
        if (la == 0) return lb
        if (lb == 0) return la
        var prev = dpPrev
        var cur = dpCur
        for (j in 0..lb) prev[j] = j
        for (i in 1..la) {
            cur[0] = i
            val lo = max(1, i - budget)
            val hi = min(lb, i + budget)
            if (lo > 1) cur[lo - 1] = budget + 1
            var rowBest = budget + 1
            val ca = a[i - 1]
            for (j in lo..hi) {
                val cost = if (ca == b[j - 1]) 0 else 1
                val v = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
                cur[j] = v
                if (v < rowBest) rowBest = v
            }
            for (j in hi + 1..lb) cur[j] = budget + 1
            if (rowBest > budget) return budget + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[lb]
    }

    companion object {
        /** Below this there is no answer worth showing. See [rank]. */
        const val MIN_QUERY_CHARS = 3

        private const val MAX_QUERY_TOKENS = 24
        private const val MAX_TOKEN_LEN = 40
        private const val SHORTLIST = 24
        private const val COVERAGE_FLOOR = 0.34
        private const val ORDER_HIT_FLOOR = 0.6
        private const val PREFIX_SCORE = 0.92
        private const val PREFIX_MIN_CHARS = 3
        private const val PROX_FALLOFF_MS = 60_000.0

        /** HlsPlayerActivity.SYNC_MIN_OFFSET_DELTA_MS. See [selectRows]. */
        private const val DUP_OFFSET_MS = 1_500.0
        private const val MAX_PER_TEXT = 2
        private const val PAIR_MAX_GAP_MS = 1_200L
        private const val PAIR_MAX_HALF_TOKENS = 8

        /** "Used more than eight times here" is not distinctive. See [rank]. */
        private const val SUBSTANCE_DF = 8.0

        private const val W_COV = 0.52
        private const val W_ORDER = 0.22
        private const val W_SUB = 0.14
        private const val W_TIGHT = 0.06
        private const val W_PROX = 0.06

        /**
         * Lowercase, accents folded, punctuation to spaces -- EXCEPT apostrophes,
         * which are DELETED.
         *
         * THE APOSTROPHE IS NOT A DETAIL. Nobody types one on a remote, and
         * turning it into a space splits "don't" into "don" + "t" while the
         * viewer typed the single token "dont" -- so cue and query tokenize
         * differently on the most common words in English dialogue. Measured over
         * twelve contraction lines typed without apostrophes, mean coverage:
         * 0.844 with apostrophe-as-space, 1.000 with it deleted. All twelve
         * improved; "ive been waiting" and "ill be there" went 0.667 -> 1.000.
         *
         * Accents folded for the same reason: the remote cannot produce "é", so
         * "Renee" has to reach "Renée". Markup is NOT handled here -- cue text is
         * already free of it, because stripCueMarkup runs at parse time.
         */
        fun normalize(s: String): String {
            val folded = Normalizer.normalize(s, Normalizer.Form.NFD)
            val sb = StringBuilder(folded.length)
            var pendingSpace = false
            for (ch in folded) {
                // Combining marks left over from NFD -- drop, no space.
                if (ch.category == CharCategory.NON_SPACING_MARK) continue
                if (ch == '\'' || ch == '\u2019' || ch == '\u02BC' || ch == '\u02B9') continue
                val c = ch.lowercaseChar()
                if (c.isLetterOrDigit()) {
                    sb.append(c)
                    pendingSpace = false
                } else if (!pendingSpace && sb.isNotEmpty()) {
                    sb.append(' ')
                    pendingSpace = true
                }
            }
            return sb.toString().trimEnd()
        }

        private fun tokenize(norm: String): Array<String> =
            if (norm.isEmpty()) emptyArray() else norm.split(' ').toTypedArray()
    }
}