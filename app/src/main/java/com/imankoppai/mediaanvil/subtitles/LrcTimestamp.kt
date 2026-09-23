package com.imankoppai.mediaanvil.subtitles

/** Timestamp helpers shared by every LRC reader. */
internal object LrcTimestamp {
    /**
     * Turns an LRC `[mm:ss.xx]` match into milliseconds.
     *
     * The fraction field is a decimal fraction, not a fixed-width number: `.5`
     * means half a second (500 ms) and `.50` means 50 hundredths (500 ms). Reading
     * it by digit count alone would turn `.5` into 5 ms.
     */
    fun toMillis(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        return minutes * 60_000 + seconds * 1_000 + fractionToMillis(match.groupValues[3])
    }

    /** `"5"` -> 500 ms, `"50"` -> 500 ms, `"500"` -> 500 ms, `""` -> 0 ms. */
    fun fractionToMillis(fraction: String): Long {
        if (fraction.isEmpty()) return 0L
        val digits = fraction.take(3)
        val value = digits.toLongOrNull() ?: return 0L
        return when (digits.length) {
            1 -> value * 100
            2 -> value * 10
            else -> value
        }
    }
}
