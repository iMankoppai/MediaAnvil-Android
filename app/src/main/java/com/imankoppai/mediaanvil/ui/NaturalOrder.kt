package com.imankoppai.mediaanvil.ui

/** Compare digit runs by magnitude without parsing integers (even very long episode numbers). */
internal object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i] in '0'..'9' && b[j] in '0'..'9') {
                val startA = i
                val startB = j
                while (i < a.length && a[i] in '0'..'9') i++
                while (j < b.length && b[j] in '0'..'9') j++
                var significantA = startA
                var significantB = startB
                while (significantA < i - 1 && a[significantA] == '0') significantA++
                while (significantB < j - 1 && b[significantB] == '0') significantB++
                val length = (i - significantA).compareTo(j - significantB)
                if (length != 0) return length
                for (offset in 0 until i - significantA) {
                    val digit = a[significantA + offset].compareTo(b[significantB + offset])
                    if (digit != 0) return digit
                }
            } else {
                val order = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (order != 0) return order
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
