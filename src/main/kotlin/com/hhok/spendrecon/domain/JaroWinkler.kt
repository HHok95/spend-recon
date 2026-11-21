package com.hhok.spendrecon.domain

/**
 * Jaro-Winkler similarity in the range 0 to 1.
 * The prefix scale is the usual 0.1, and the common prefix counts at most 4 characters.
 * Merchant names are already normalized, so this compares those strings as they are stored.
 */
fun jaroWinkler(
    left: String,
    right: String,
): Double {
    if (left.isEmpty() && right.isEmpty()) {
        return 1.0
    }
    if (left.isEmpty() || right.isEmpty()) {
        return 0.0
    }
    val leftMatched = BooleanArray(left.length)
    val rightMatched = BooleanArray(right.length)
    val matchDistance = maxOf(0, maxOf(left.length, right.length) / 2 - 1)
    var matches = 0
    for (i in left.indices) {
        val start = maxOf(0, i - matchDistance)
        val end = minOf(i + matchDistance + 1, right.length)
        for (j in start until end) {
            if (rightMatched[j] || left[i] != right[j]) {
                continue
            }
            leftMatched[i] = true
            rightMatched[j] = true
            matches++
            break
        }
    }
    if (matches == 0) {
        return 0.0
    }
    var transpositions = 0
    var k = 0
    for (i in left.indices) {
        if (!leftMatched[i]) {
            continue
        }
        while (!rightMatched[k]) {
            k++
        }
        if (left[i] != right[k]) {
            transpositions++
        }
        k++
    }
    val matched = matches.toDouble()
    val jaro =
        (
            matched / left.length +
                matched / right.length +
                (matched - transpositions / 2.0) / matched
        ) / 3.0
    var prefix = 0
    val prefixLimit = minOf(4, left.length, right.length)
    while (prefix < prefixLimit && left[prefix] == right[prefix]) {
        prefix++
    }
    return jaro + prefix * PREFIX_SCALE * (1.0 - jaro)
}

private const val PREFIX_SCALE = 0.1
