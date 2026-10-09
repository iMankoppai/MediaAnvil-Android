package com.imankoppai.mediaanvil.subtitles

enum class LyricsScript(val value: String) {
    Original("original"), Simplified("simplified"), Traditional("traditional");
    companion object {
        fun from(value: String): LyricsScript = entries.firstOrNull { it.value == value } ?: Original
    }
}

/** Offline phrase-first conversion using the bundled OpenCC 1.1.9 dictionaries. */
object ChineseLyrics {
    private val simplified by lazy { Dictionary.load("TS") }
    private val traditional by lazy { Dictionary.load("ST") }
    private val timedPrefix = Regex("^(?:\\[(?:\\d+:\\d+(?:[.:]\\d+)?|offset:[+-]?\\d+)])+")

    fun convertLrc(original: String, script: LyricsScript): String {
        if (script == LyricsScript.Original) return original
        val dictionary = if (script == LyricsScript.Simplified) simplified else traditional
        // Convert lyric bodies only. LRC metadata, timestamps, offsets and line
        // endings remain byte-for-byte identical to the searched candidate.
        return original.splitToSequence('\n').joinToString("\n") { line ->
            val prefix = timedPrefix.find(line)?.value.orEmpty()
            if (prefix.isEmpty() && line.startsWith('[')) line
            else prefix + dictionary.convert(line.substring(prefix.length))
        }
    }

    private class Dictionary(private val words: Map<String, String>, private val lengths: Map<Char, Int>) {
        fun convert(text: String): String = buildString(text.length) {
            var index = 0
            while (index < text.length) {
                var matched = false
                val maximum = minOf(lengths[text[index]] ?: 0, text.length - index)
                for (length in maximum downTo 1) {
                    val replacement = words[text.substring(index, index + length)] ?: continue
                    append(replacement)
                    index += length
                    matched = true
                    break
                }
                if (!matched) append(text[index++])
            }
        }

        companion object {
            fun load(direction: String): Dictionary {
                val words = HashMap<String, String>()
                val lengths = HashMap<Char, Int>()
                // Character fallbacks first; phrase mappings take precedence.
                for (suffix in listOf("Characters", "Phrases")) {
                    checkNotNull(ChineseLyrics::class.java.getResourceAsStream("/opencc/$direction$suffix.txt"))
                        .bufferedReader(Charsets.UTF_8).useLines { lines ->
                            lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                                val tab = line.indexOf('\t')
                                if (tab <= 0) return@forEach
                                val key = line.substring(0, tab)
                                words[key] = line.substring(tab + 1).substringBefore(' ')
                                lengths[key[0]] = maxOf(lengths[key[0]] ?: 0, key.length)
                            }
                        }
                }
                return Dictionary(words, lengths)
            }
        }
    }
}
