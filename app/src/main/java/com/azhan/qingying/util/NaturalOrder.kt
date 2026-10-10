package com.azhan.qingying.util

/**
 * 自然序比较：把文件名切成「数字段 / 非数字段」交替比较，
 * 让 `第2集` 排在 `第10集` 前面 —— 纯字典序会把 10 排到 2 前面。
 *
 * 比较时忽略大小写，数字段按数值比、且前导零更少的排前面（`2.mp4` < `02.mp4`）。
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var iEnd = i
                while (iEnd < a.length && a[iEnd].isDigit()) iEnd++
                var jEnd = j
                while (jEnd < b.length && b[jEnd].isDigit()) jEnd++

                val numA = a.substring(i, iEnd).trimStart('0')
                val numB = b.substring(j, jEnd).trimStart('0')
                val byLength = numA.length.compareTo(numB.length)
                if (byLength != 0) return byLength
                val byValue = numA.compareTo(numB)
                if (byValue != 0) return byValue
                // 数值相同：前导零少的在前
                val byDigits = (iEnd - i).compareTo(jEnd - j)
                if (byDigits != 0) return byDigits

                i = iEnd
                j = jEnd
            } else {
                val lowerA = ca.lowercaseChar()
                val lowerB = cb.lowercaseChar()
                if (lowerA != lowerB) return lowerA.compareTo(lowerB)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
