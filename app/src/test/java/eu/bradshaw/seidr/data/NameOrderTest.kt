package eu.bradshaw.seidr.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class NameOrderTest {
    private val saved = Locale.getDefault()
    @After fun restore() = Locale.setDefault(saved)

    private fun sort(locale: Locale, vararg names: String): List<String> {
        Locale.setDefault(locale)
        return names.sortedWith(nameOrder { it })
    }

    @Test fun accentsSortWithTheirLetterAndCaseIsIgnored() {
        // Code points would put "Émile" after "zebra".
        assertEquals(listOf("apple", "Émile", "Eve", "zebra"), sort(Locale.UK, "zebra", "Émile", "apple", "Eve"))
    }

    @Test fun chineseSortsByPinyinNotCodePoint() {
        // 阿 (ā), 北 (běi), 中 (zhōng); code points give 中, 北, 阿.
        assertEquals(listOf("阿", "北", "中"), sort(Locale.SIMPLIFIED_CHINESE, "中", "阿", "北"))
    }
}
