package eu.bradshaw.seidr.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SmartRulesTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    // Shapes from a real library's smart playlists (ids made up).
    private val real = listOf(
        """{"all": [{"notContains": {"title": "(live"}}, {"notContains": {"title": "(skit"}}, {"gt": {"duration": 60}}], "sort": "-dateAdded"}""",
        """{"all": [{"is": {"loved": true}}], "sort": "+albumartist"}""",
        """{"all": [{"inTheLast": {"dateadded": "7"}}], "sort": "-dateadded"}""",
        """{"any": [{"notContains": {"filepath": ".flac"}}, {"contains": {"lossysource": ""}}], "sort": "+albumartist,+album"}""",
        """{"all": [{"inPlaylist": {"id": "aaa"}}, {"any": [{"inTheRange": {"originalYear": [2000, 2009]}}, {"all": [{"inTheRange": {"year": [2000, 2009]}}, {"lt": {"originalYear": 1800}}]}]}], "sort": "+year,+artists"}""",
        """{"any": [{"contains": {"title": "We Want Dinos"}}, {"all": [{"inPlaylist": {"id": "aaa"}}, {"notInPlaylist": {"id": "bbb"}}]}]}""",
        """{"all": [{"gt": {"size": 0}}], "sort": "+album", "limit": 50, "order": "desc", "limitPercent": 10}""",
    )

    @Test fun everyRealRuleSetRoundTripsExactly() {
        for (s in real) {
            val json = obj(s)
            val c = SmartRules.parse(json)!!
            assertEquals(s, json, c.toJson())
        }
    }

    @Test fun unknownFieldsAndTestsAreKeptButNotEditable() {
        val c = SmartRules.parse(obj(real[3]))!!
        val lossy = c.root.items[1] as Rule
        assertEquals("lossysource", lossy.field)
        assertFalse(lossy.understood)
        assertTrue((c.root.items[0] as Rule).understood)
        val odd = SmartRules.parse(obj("""{"all": [{"isMissing": {"genre": true}}, {"is": {"title": 5}}, [1, 2], {"a": 1, "b": 2}]}"""))!!
        assertFalse((odd.root.items[0] as Rule).understood)
        assertFalse((odd.root.items[1] as Rule).understood)
        assertTrue(odd.root.items[2] is RawRule)
        assertTrue(odd.root.items[3] is RawRule)
        assertEquals(odd.toJson(), obj("""{"all": [{"isMissing": {"genre": true}}, {"is": {"title": 5}}, [1, 2], {"a": 1, "b": 2}]}"""))
    }

    @Test fun fieldNamesAreReadInAnyCase() {
        val c = SmartRules.parse(obj(real[4]))!!
        val range = ((c.root.items[1] as RuleGroup).items[0] as Rule)
        assertEquals(SmartField.ORIGINAL_YEAR, range.smartField)
        assertTrue(range.understood)
        assertEquals(
            SmartValue.Range(SmartValue.Number(2000.0), SmartValue.Number(2009.0)),
            SmartValue.read(FieldType.NUMBER, SmartOp.IN_THE_RANGE, range.value),
        )
    }

    @Test fun notTheShapeNavidromeWrites() {
        assertNull(SmartRules.parse(obj("""{"sort": "+title"}""")))
        assertNull(SmartRules.parse(obj("""{"all": [], "any": []}""")))
        assertNull(SmartRules.parse(obj("""{"all": {"is": {"title": "x"}}}""")))
    }

    @Test fun editsByPath() {
        val c = SmartRules.parse(obj(real[4]))!!
        val newRule = Rule("is", "genre", JsonPrimitive("Rock"))
        // Replace the innermost year rule.
        val r1 = SmartRules.replace(c.root, listOf(1, 1, 0), newRule)
        assertEquals(newRule, SmartRules.at(r1, listOf(1, 1, 0)))
        assertEquals(c.root.items[0], r1.items[0])
        // Remove the nested any-group.
        val r2 = SmartRules.remove(c.root, listOf(1))
        assertEquals(1, r2.items.size)
        // Add to a nested group.
        val r3 = SmartRules.append(c.root, listOf(1, 1), newRule)
        assertEquals(3, (SmartRules.at(r3, listOf(1, 1)) as RuleGroup).items.size)
        // The root stays.
        assertEquals(c.root, SmartRules.remove(c.root, emptyList()))
        assertEquals(4, c.ruleCount)
    }

    @Test fun emptyGroupsCantBeSaved() {
        val c = SmartRules.parse(obj(real[4]))!!
        assertNull(SmartRules.problem(c))
        val emptied = c.copy(root = SmartRules.replace(c.root, listOf(1), RuleGroup(true, emptyList())))
        assertEquals(SmartRules.Problem.EMPTY_GROUP, SmartRules.problem(emptied))
        assertEquals(SmartRules.Problem.EMPTY_GROUP, SmartRules.problem(SmartCriteria(RuleGroup(false, emptyList()))))
        assertEquals(SmartRules.Problem.BAD_LIMIT, SmartRules.problem(c.copy(limit = 0)))
    }

    @Test fun sortKeysFollowPrefixesAndOrder() {
        val c = SmartRules.parse(obj(real[0]))!!
        assertEquals(listOf(SortKey("dateAdded", true)), c.sortKeys)
        val flipped = SmartRules.parse(obj("""{"all": [{"is": {"loved": true}}], "sort": "year,-title", "order": "desc"}"""))!!
        assertEquals(listOf(SortKey("year", true), SortKey("title", false)), flipped.sortKeys)
        // Written back explicitly, without the flip.
        val w = flipped.withSort(flipped.sortKeys)
        assertEquals("-year,+title", w.sort)
        assertFalse("order" in w.toJson())
        assertEquals(flipped.sortKeys, w.sortKeys)
        assertFalse("sort" in c.withSort(emptyList()).toJson())
    }

    @Test fun valuesReadAndWrite() {
        assertEquals(SmartValue.Days(7), SmartValue.read(FieldType.DATE, SmartOp.IN_THE_LAST, JsonPrimitive("7")))
        assertEquals(SmartValue.Days(7), SmartValue.read(FieldType.DATE, SmartOp.IN_THE_LAST, JsonPrimitive(7)))
        assertEquals(SmartValue.Bool(true), SmartValue.read(FieldType.BOOL, SmartOp.IS, JsonPrimitive("true")))
        assertEquals(SmartValue.Date(LocalDate.of(2020, 1, 2)), SmartValue.read(FieldType.DATE, SmartOp.AFTER, JsonPrimitive("2020-01-02")))
        assertNull(SmartValue.read(FieldType.DATE, SmartOp.AFTER, JsonPrimitive("soon")))
        assertNull(SmartValue.read(FieldType.PLAYLIST, SmartOp.IN_PLAYLIST, JsonPrimitive("")))
        assertEquals(JsonPrimitive(2000), SmartValue.write(SmartValue.Number(2000.0)))
        assertEquals(JsonPrimitive(1.5), SmartValue.write(SmartValue.Number(1.5)))
    }

    @Test fun changingTheTestKeepsAValueThatStillFits() {
        val r = Rule("is", "title", JsonPrimitive("Intro"))
        assertEquals(Rule("contains", "title", JsonPrimitive("Intro")), SmartValue.changeOp(r, SmartOp.CONTAINS))
        val year = Rule("is", "year", JsonPrimitive(1999))
        val ranged = SmartValue.changeOp(year, SmartOp.IN_THE_RANGE)
        assertTrue(ranged.understood)
        assertTrue(SmartValue.read(FieldType.NUMBER, SmartOp.IN_THE_RANGE, ranged.value) is SmartValue.Range)
        val today = LocalDate.of(2026, 11, 18)
        assertEquals(JsonPrimitive(30), SmartValue.default(SmartField.DATE_ADDED, SmartOp.IN_THE_LAST, today).value)
    }

    @Test fun everyFieldHasTests() {
        for (f in SmartField.entries) assertTrue(f.name, f.ops.isNotEmpty())
        assertEquals(listOf(SmartOp.IN_PLAYLIST, SmartOp.NOT_IN_PLAYLIST), SmartField.PLAYLIST.ops)
    }

    @Test fun starterIsEditableButNotYetSaveableAsIs() {
        val s = SmartRules.starter()
        assertTrue(s.understood)
        assertEquals(JsonObject(mapOf("all" to SmartRules.groupItems(RuleGroup(false, listOf(s))))),
            SmartCriteria(RuleGroup(false, listOf(s))).toJson())
    }
}
