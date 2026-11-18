package eu.bradshaw.seidr.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate

/*
 * A Navidrome smart playlist's rules (its "criteria"), as the editor sees them:
 * groups that match All or Any, holding rules and further groups, plus a sort
 * and a limit. Pure, so it's unit-tested.
 *
 * The one promise: whatever the editor doesn't understand is kept exactly as
 * it came, and saved back exactly so. A rule on a field or test it doesn't
 * know is a read-only row; top-level keys it doesn't know ride along; the
 * sort string is left alone unless the user edits the sort.
 */

/** What kind of value a field holds, which decides its tests and its value editor. */
enum class FieldType { TEXT, NUMBER, DATE, BOOL, PLAYLIST }

/**
 * The fields the editor offers, by Navidrome's name for each. Checked against a
 * real server (0.64): Navidrome takes any name, and an unknown one silently
 * matches nothing, so only these are offered. Others in a playlist stay read-only.
 */
enum class SmartField(val key: String, val type: FieldType) {
    TITLE("title", FieldType.TEXT),
    ARTIST("artist", FieldType.TEXT),
    ALBUM_ARTIST("albumartist", FieldType.TEXT),
    ALBUM("album", FieldType.TEXT),
    GENRE("genre", FieldType.TEXT),
    YEAR("year", FieldType.NUMBER),
    ORIGINAL_YEAR("originalyear", FieldType.NUMBER),
    RELEASE_DATE("releasedate", FieldType.DATE),
    DATE_ADDED("dateadded", FieldType.DATE),
    LAST_PLAYED("lastplayed", FieldType.DATE),
    PLAY_COUNT("playcount", FieldType.NUMBER),
    LOVED("loved", FieldType.BOOL),
    DATE_LOVED("dateloved", FieldType.DATE),
    RATING("rating", FieldType.NUMBER),
    PLAYLIST("id", FieldType.PLAYLIST),
    DURATION("duration", FieldType.NUMBER),
    TRACK_NUMBER("tracknumber", FieldType.NUMBER),
    DISC_NUMBER("discnumber", FieldType.NUMBER),
    COMPILATION("compilation", FieldType.BOOL),
    RELEASE_TYPE("releasetype", FieldType.TEXT),
    RECORD_LABEL("recordlabel", FieldType.TEXT),
    COMPOSER("composer", FieldType.TEXT),
    MOOD("mood", FieldType.TEXT),
    BPM("bpm", FieldType.NUMBER),
    COMMENT("comment", FieldType.TEXT),
    LYRICS("lyrics", FieldType.TEXT),
    HAS_COVER_ART("hascoverart", FieldType.BOOL),
    FILE_TYPE("filetype", FieldType.TEXT),
    BITRATE("bitrate", FieldType.NUMBER),
    FILE_PATH("filepath", FieldType.TEXT);

    val ops: List<SmartOp> get() = SmartOp.entries.filter { type in it.types }

    /** Whether songs can be sorted by it (Navidrome sorts by any field but a playlist). */
    val sortable: Boolean get() = type != FieldType.PLAYLIST

    companion object {
        /** Navidrome reads field names in any case ("originalYear", "dateAdded"). */
        fun of(name: String): SmartField? = entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
    }
}

/** How a value is given for a test: one value, a pair (a range), or a count of days. */
enum class ValueShape { ONE, RANGE, DAYS }

/** The tests, by Navidrome's name, and which field types each applies to. */
enum class SmartOp(val key: String, val types: Set<FieldType>, val shape: ValueShape = ValueShape.ONE) {
    IS("is", setOf(FieldType.TEXT, FieldType.NUMBER, FieldType.DATE, FieldType.BOOL)),
    IS_NOT("isNot", setOf(FieldType.TEXT, FieldType.NUMBER, FieldType.DATE, FieldType.BOOL)),
    CONTAINS("contains", setOf(FieldType.TEXT)),
    NOT_CONTAINS("notContains", setOf(FieldType.TEXT)),
    STARTS_WITH("startsWith", setOf(FieldType.TEXT)),
    ENDS_WITH("endsWith", setOf(FieldType.TEXT)),
    GT("gt", setOf(FieldType.NUMBER)),
    LT("lt", setOf(FieldType.NUMBER)),
    BEFORE("before", setOf(FieldType.DATE)),
    AFTER("after", setOf(FieldType.DATE)),
    IN_THE_LAST("inTheLast", setOf(FieldType.DATE), ValueShape.DAYS),
    NOT_IN_THE_LAST("notInTheLast", setOf(FieldType.DATE), ValueShape.DAYS),
    IN_THE_RANGE("inTheRange", setOf(FieldType.NUMBER, FieldType.DATE), ValueShape.RANGE),
    IN_PLAYLIST("inPlaylist", setOf(FieldType.PLAYLIST)),
    NOT_IN_PLAYLIST("notInPlaylist", setOf(FieldType.PLAYLIST));

    companion object {
        fun of(name: String): SmartOp? = entries.firstOrNull { it.key == name }
    }
}

/** One entry in a group: a rule, a nested group, or something kept as it came. */
sealed interface RuleNode

/** Matches when all (or, with [any], any) of [items] do. */
data class RuleGroup(val any: Boolean, val items: List<RuleNode>) : RuleNode

/** `{op: {field: value}}`. [field] is as written (case kept), [value] as it came until edited. */
data class Rule(val op: String, val field: String, val value: JsonElement) : RuleNode {
    val smartField: SmartField? get() = SmartField.of(this.field)
    val smartOp: SmartOp? get() = SmartOp.of(op)

    /** The editor can show and change it: a known field, a test that suits it, and a value it can read. */
    val understood: Boolean get() {
        val f = smartField ?: return false
        val o = smartOp ?: return false
        return f.type in o.types && SmartValue.read(f.type, o, value) != null
    }
}

/** Anything else in a group, saved back untouched. */
data class RawRule(val json: JsonElement) : RuleNode

/** One sort key: a field (as written) and its direction. */
data class SortKey(val field: String, val descending: Boolean)

/**
 * A whole set of rules. [extra] holds every top-level key that isn't the root
 * group, `sort` or `limit` (`order`, `offset`, `limitPercent` and anything
 * newer), and goes back as it came.
 */
data class SmartCriteria(
    val root: RuleGroup,
    val sort: String? = null,
    val limit: Int? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    /** The sort, key by key, as Navidrome reads it: `-` is descending, and `order: desc` flips the lot. */
    val sortKeys: List<SortKey> get() {
        val flip = (extra["order"] as? JsonPrimitive)?.contentOrNull.equals("desc", ignoreCase = true)
        return sort.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { k ->
            val desc = k.startsWith('-')
            SortKey(k.trimStart('+', '-').trim(), desc != flip)
        }
    }

    /** With the sort set to [keys], written out in full (so a stray `order` goes). */
    fun withSort(keys: List<SortKey>): SmartCriteria = copy(
        sort = keys.joinToString(",") { (if (it.descending) "-" else "+") + it.field }.ifEmpty { null },
        extra = JsonObject(extra - "order"),
    )

    fun toJson(): JsonObject = JsonObject(buildMap {
        put(if (root.any) "any" else "all", SmartRules.groupItems(root))
        sort?.let { put("sort", JsonPrimitive(it)) }
        limit?.let { put("limit", JsonPrimitive(it)) }
        putAll(extra)
    })

    /** Every rule, at any depth, counting ones kept as they came. */
    val ruleCount: Int get() = SmartRules.count(root)
}

object SmartRules {
    /** The rules from a playlist's `rules`, or null if they aren't the shape Navidrome writes. */
    fun parse(json: JsonObject): SmartCriteria? {
        val roots = json.keys.filter { it == "all" || it == "any" }
        if (roots.size != 1) return null
        val items = json[roots[0]] as? JsonArray ?: return null
        val limit = (json["limit"] as? JsonPrimitive)?.takeIf { it.isNumber() }?.intOrNull
        val sort = (json["sort"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val known = buildSet {
            add(roots[0])
            if (sort != null) add("sort")
            if (limit != null) add("limit")
        }
        return SmartCriteria(
            root = RuleGroup(roots[0] == "any", items.map(::node)),
            sort = sort,
            limit = limit,
            extra = JsonObject(json.filterKeys { it !in known }),
        )
    }

    private fun JsonPrimitive.isNumber() = !isString && (longOrNull != null || doubleOrNull != null)

    private fun node(e: JsonElement): RuleNode {
        val o = e as? JsonObject ?: return RawRule(e)
        if (o.size != 1) return RawRule(e)
        val (key, inner) = o.entries.first()
        if ((key == "all" || key == "any") && inner is JsonArray) return RuleGroup(key == "any", inner.map(::node))
        val body = inner as? JsonObject ?: return RawRule(e)
        if (body.size != 1) return RawRule(e)
        val (field, value) = body.entries.first()
        return Rule(key, field, value)
    }

    fun groupItems(g: RuleGroup): JsonArray = JsonArray(g.items.map(::toJson))

    fun toJson(n: RuleNode): JsonElement = when (n) {
        is RuleGroup -> JsonObject(mapOf((if (n.any) "any" else "all") to groupItems(n)))
        is Rule -> JsonObject(mapOf(n.op to JsonObject(mapOf(n.field to n.value))))
        is RawRule -> n.json
    }

    fun count(g: RuleGroup): Int = g.items.sumOf { if (it is RuleGroup) count(it) else 1 }

    // A node is found by its path: the index in the root's items, then in that group's, and so on.

    fun at(root: RuleGroup, path: List<Int>): RuleNode? {
        var node: RuleNode = root
        for (i in path) node = (node as? RuleGroup)?.items?.getOrNull(i) ?: return null
        return node
    }

    /** [root] with the node at [path] replaced by [node]. */
    fun replace(root: RuleGroup, path: List<Int>, node: RuleNode): RuleGroup {
        if (path.isEmpty()) return node as? RuleGroup ?: root
        val i = path.first()
        val child = root.items.getOrNull(i) ?: return root
        val next = if (path.size == 1) node else replace(child as? RuleGroup ?: return root, path.drop(1), node)
        return root.copy(items = root.items.toMutableList().also { it[i] = next })
    }

    /** [root] without the node at [path] (the root itself can't go). */
    fun remove(root: RuleGroup, path: List<Int>): RuleGroup {
        if (path.isEmpty()) return root
        val parent = at(root, path.dropLast(1)) as? RuleGroup ?: return root
        val i = path.last()
        if (i !in parent.items.indices) return root
        return replace(root, path.dropLast(1), parent.copy(items = parent.items.toMutableList().also { it.removeAt(i) }))
    }

    /** [root] with [node] added at the end of the group at [groupPath]. */
    fun append(root: RuleGroup, groupPath: List<Int>, node: RuleNode): RuleGroup {
        val group = at(root, groupPath) as? RuleGroup ?: return root
        return replace(root, groupPath, group.copy(items = group.items + node))
    }

    /** Why these rules can't be saved yet, or null if they can. Navidrome refuses an empty group (422). */
    fun problem(c: SmartCriteria): Problem? {
        fun check(g: RuleGroup): Problem? {
            if (g.items.isEmpty()) return Problem.EMPTY_GROUP
            return g.items.firstNotNullOfOrNull { if (it is RuleGroup) check(it) else null }
        }
        return check(c.root) ?: if (c.limit != null && c.limit <= 0) Problem.BAD_LIMIT else null
    }

    enum class Problem { EMPTY_GROUP, BAD_LIMIT }

    /** A first rule for a new playlist or group: one the user then changes. */
    fun starter(): Rule = SmartValue.default(SmartField.ARTIST, SmartOp.CONTAINS)
}

/**
 * A rule's value as the editor holds it: text for text fields, numbers, a date,
 * a count of days, yes/no, or a playlist id. Ranges are a pair of these.
 */
sealed interface SmartValue {
    data class Text(val text: String) : SmartValue
    data class Number(val n: Double) : SmartValue
    data class Date(val date: LocalDate) : SmartValue
    data class Days(val days: Int) : SmartValue
    data class Bool(val yes: Boolean) : SmartValue
    data class PlaylistRef(val id: String) : SmartValue
    data class Range(val from: SmartValue, val to: SmartValue) : SmartValue

    companion object {
        /** [value] read for [type] and [op], or null if it isn't what they take. */
        fun read(type: FieldType, op: SmartOp, value: JsonElement): SmartValue? = when (op.shape) {
            ValueShape.RANGE -> (value as? JsonArray)?.takeIf { it.size == 2 }?.let { arr ->
                val a = one(type, arr[0]) ?: return null
                val b = one(type, arr[1]) ?: return null
                Range(a, b)
            }
            // Navidrome writes days as a number or a numeric string ("7"): both read.
            ValueShape.DAYS -> (value as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()?.takeIf { it >= 0 }?.let(::Days)
            ValueShape.ONE -> one(type, value)
        }

        private fun one(type: FieldType, value: JsonElement): SmartValue? {
            val p = value as? JsonPrimitive ?: return null
            return when (type) {
                FieldType.TEXT -> p.takeIf { it.isString }?.content?.let(::Text)
                FieldType.NUMBER -> (p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull())?.let(::Number)
                FieldType.DATE -> p.contentOrNull?.let { runCatching { LocalDate.parse(it.trim().take(10)) }.getOrNull() }?.let(::Date)
                // Navidrome takes true or "true".
                FieldType.BOOL -> (p.booleanOrNull ?: p.contentOrNull?.lowercase()?.toBooleanStrictOrNull())?.let(::Bool)
                FieldType.PLAYLIST -> p.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }?.let(::PlaylistRef)
            }
        }

        fun write(v: SmartValue): JsonElement = when (v) {
            is Text -> JsonPrimitive(v.text)
            // Whole numbers go as whole numbers (2000, not 2000.0).
            is Number -> if (v.n == Math.rint(v.n) && kotlin.math.abs(v.n) < 1e15) JsonPrimitive(v.n.toLong()) else JsonPrimitive(v.n)
            is Date -> JsonPrimitive(v.date.toString())
            is Days -> JsonPrimitive(v.days)
            is Bool -> JsonPrimitive(v.yes)
            is PlaylistRef -> JsonPrimitive(v.id)
            is Range -> JsonArray(listOf(write(v.from), write(v.to)))
        }

        /** A sensible value to start [field] and [op] with, as a whole rule. */
        fun default(field: SmartField, op: SmartOp, today: LocalDate = LocalDate.now()): Rule {
            fun one(): SmartValue = when (field.type) {
                FieldType.TEXT -> Text("")
                FieldType.NUMBER -> Number(if (field == SmartField.YEAR || field == SmartField.ORIGINAL_YEAR) today.year.toDouble() else 0.0)
                FieldType.DATE -> Date(today)
                FieldType.BOOL -> Bool(true)
                FieldType.PLAYLIST -> PlaylistRef("")
            }
            val v = when (op.shape) {
                ValueShape.ONE -> one()
                ValueShape.DAYS -> Days(30)
                ValueShape.RANGE -> when (field.type) {
                    FieldType.DATE -> Range(Date(today.minusYears(1)), Date(today))
                    else -> Range(one(), one())
                }
            }
            return Rule(op.key, field.key, write(v))
        }

        /**
         * [rule] moved to [op] (same field): the value carries over when it still
         * fits (is → contains keeps the text), else it starts afresh.
         */
        fun changeOp(rule: Rule, op: SmartOp): Rule {
            val field = rule.smartField ?: return rule
            val old = rule.smartOp
            return if (old != null && old.shape == op.shape) rule.copy(op = op.key)
            else default(field, op)
        }
    }
}
