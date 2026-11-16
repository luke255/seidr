package eu.bradshaw.seidr.data

import java.text.Collator

/**
 * Sorts by name the way a reader of the app's language expects: accented
 * letters with their base letter, case ignored, and Chinese or Japanese by the
 * language's collation (pinyin, stroke or kana order) rather than raw code
 * points. Make one per sort, since the language can change while the app runs.
 */
fun <T> nameOrder(name: (T) -> String): Comparator<T> {
    val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
    return Comparator { a, b -> collator.compare(name(a), name(b)) }
}
