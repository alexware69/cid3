/*
 NaturalOrderComparator.java -- Perform 'natural order' comparisons of strings in Java.
 Copyright (C) 2003 by Pierre-Luc Paour <natorder@paour.com>

 Based on the C version by Martin Pool, of which this is more or less a straight conversion.
 Copyright (C) 2000 by Martin Pool <mbp@humbug.org.au>

 This software is provided 'as-is', without any express or implied
 warranty.  In no event will the authors be held liable for any damages
 arising from the use of this software.

 Permission is granted to anyone to use this software for any purpose,
 including commercial applications, and to alter it and redistribute it
 freely, subject to the following restrictions:

 1. The origin of this software must not be misrepresented; you must not
 claim that you wrote the original software. If you use this software
 in a product, an acknowledgment in the product documentation would be
 appreciated but is not required.
 2. Altered source versions must be plainly marked as such, and must not be
 misrepresented as being the original software.
 3. This notice may not be removed or altered from any source distribution.
 */

import java.io.Serializable
import java.util.*

class NaturalOrderComparator : Comparator<Any?>, Serializable {
    private fun compareRight(a: String, b: String): Int {
        var bias = 0
        var ia = 0
        var ib = 0

        // The longest run of digits wins. That aside, the greatest
        // value wins, but we can't know that it will until we've scanned
        // both numbers to know that they have the same magnitude, so we
        // remember it in BIAS.
        while (true) {
            val ca = charAt(a, ia)
            val cb = charAt(b, ib)
            if (!isDigit(ca) && !isDigit(cb)) {
                return bias
            }
            if (!isDigit(ca)) {
                return -1
            }
            if (!isDigit(cb)) {
                return +1
            }
            if (ca.toInt() == 0 && cb.toInt() == 0) {
                return bias
            }
            if (bias == 0) {
                if (ca < cb) {
                    bias = -1
                } else if (ca > cb) {
                    bias = +1
                }
            }
            ia++
            ib++
        }
    }

    override fun compare(o1: Any?, o2: Any?): Int {
        val a = o1.toString()
        val b = o2.toString()
        var ia = 0
        var ib = 0
        var nza: Int
        var nzb: Int
        var ca: Char
        var cb: Char
        while (true) {
            // Only count the number of zeroes leading the last number compared
            nzb = 0
            nza = nzb
            ca = charAt(a, ia)
            cb = charAt(b, ib)

            // skip over leading spaces or zeros
            while (Character.isSpaceChar(ca) || ca == '0') {
                if (ca == '0') {
                    nza++
                } else {
                    // Only count consecutive zeroes
                    nza = 0
                }
                ca = charAt(a, ++ia)
            }
            while (Character.isSpaceChar(cb) || cb == '0') {
                if (cb == '0') {
                    nzb++
                } else {
                    // Only count consecutive zeroes
                    nzb = 0
                }
                cb = charAt(b, ++ib)
            }

            // Process run of digits
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                val bias = compareRight(a.substring(ia), b.substring(ib))
                if (bias != 0) {
                    return bias
                }
            }
            if (ca.toInt() == 0 && cb.toInt() == 0) {
                // The strings compare the same. Perhaps the caller
                // will want to call strcmp to break the tie.
                return compareEqual(a, b, nza, nzb)
            }
            if (ca < cb) {
                return -1
            }
            if (ca > cb) {
                return +1
            }
            ++ia
            ++ib
        }
    }

    private fun isDigit(c: Char): Boolean {
        return Character.isDigit(c) || c == '.' || c == ','
    }

    private fun charAt(s: String, i: Int): Char {
        return if (i >= s.length) '\u0000' else s[i]
    }

    private fun compareEqual(a: String, b: String, nza: Int, nzb: Int): Int {
        if (nza - nzb != 0) return nza - nzb
        return if (a.length == b.length) a.compareTo(b) else a.length - b.length
    }
}