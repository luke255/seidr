package eu.bradshaw.seidr.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.data.FieldType
import eu.bradshaw.seidr.data.RawRule
import eu.bradshaw.seidr.data.Rule
import eu.bradshaw.seidr.data.RuleGroup
import eu.bradshaw.seidr.data.RuleNode
import eu.bradshaw.seidr.data.SmartCriteria
import eu.bradshaw.seidr.data.SmartField
import eu.bradshaw.seidr.data.SmartOp
import eu.bradshaw.seidr.data.SmartRules
import eu.bradshaw.seidr.data.SmartValue
import eu.bradshaw.seidr.data.SortKey
import eu.bradshaw.seidr.data.ValueShape
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.data.api.ok
import eu.bradshaw.seidr.sync.EditResult
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.components.Load
import eu.bradshaw.seidr.ui.components.LoadContent
import eu.bradshaw.seidr.ui.components.SectionHeader
import eu.bradshaw.seidr.ui.components.bottomContentPadding
import eu.bradshaw.seidr.ui.primaryFocus
import eu.bradshaw.seidr.ui.selectToEdit
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Edits a smart playlist's rules ([id]), or makes a new one ([id] null, named
 * [initialName]). Nothing is written until Save, and Save checks the rules
 * weren't changed elsewhere meanwhile (see `SmartPlaylists.save`).
 */
class SmartRulesVm(private val graph: Graph, val id: String?, initialName: String) : ViewModel() {
    var state by mutableStateOf<Load<Unit>>(if (id == null) Load.Ready(Unit) else Load.Loading)
        private set
    /** The rules as the server had them when loaded: Save goes ahead only if they still are. */
    var seen: JsonObject? = null
        private set
    var seenName: String = initialName
        private set
    var criteria by mutableStateOf(SmartCriteria(RuleGroup(false, listOf(SmartRules.starter()))))
    var name by mutableStateOf(initialName)
    /** The rules aren't the shape this editor reads: shown as unchanged, nothing editable. */
    var unreadable by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)

    init {
        if (id != null) load()
    }

    fun load() {
        val id = id ?: return
        viewModelScope.launch {
            state = Load.Loading
            state = runCatching { graph.smart.load(id) }.fold({ p ->
                val rules = p.rules ?: return@fold Load.Error(graph.app.getString(R.string.smart_not_smart))
                seen = rules
                seenName = p.name
                name = p.name
                val parsed = SmartRules.parse(rules)
                unreadable = parsed == null
                if (parsed != null) criteria = parsed
                Load.Ready(Unit)
            }, { e -> Load.Error(e.message ?: e.javaClass.simpleName) })
        }
    }

    /** Anything changed since it was loaded (or anything at all, for a new one). */
    val dirty: Boolean get() = id == null || name.trim() != seenName || (!unreadable && seen != criteria.toJson())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartRulesScreen(id: String?, initialName: String) {
    val actions = LocalActions.current
    val graph = actions.graph
    val vm = viewModel(key = "smart:$id") { SmartRulesVm(graph, id, initialName) }
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = vm.dirty && vm.state is Load.Ready) { confirmDiscard = true }

    val playlists by graph.library.allPlaylists.collectAsStateWithLifecycle()
    // Rules may name any playlist this login can see, smart ones included, but never itself.
    val pickable = remember(playlists, id) { playlists.orEmpty().filter { it.id != id }.sortedBy { it.name.lowercase() } }

    val saved = stringResource(R.string.smart_saved_plain)
    val restored = stringResource(R.string.smart_restored)
    val needName = stringResource(R.string.smart_problem_name)
    val emptyGroup = stringResource(R.string.smart_problem_empty_group)
    val badLimit = stringResource(R.string.smart_problem_limit)
    val createFailed = stringResource(R.string.library_create_failed)
    fun save() {
        val problem = when {
            vm.name.isBlank() -> needName
            vm.unreadable -> null
            else -> when (SmartRules.problem(vm.criteria)) {
                SmartRules.Problem.EMPTY_GROUP -> emptyGroup
                SmartRules.Problem.BAD_LIMIT -> badLimit
                null -> null
            }
        }
        if (problem != null) return actions.toast(problem)
        val name = vm.name.trim()
        vm.saving = true
        actions.launch {
            if (vm.id == null) {
                val newId = graph.smart.create(name, vm.criteria.toJson())
                vm.saving = false
                if (newId == null) return@launch actions.toast(createFailed)
                runCatching { graph.browse.refreshPlaylists() }
                actions.nav.pop()
                actions.open(Route.Playlist(newId))
                actions.toast(graph.app.getString(R.string.smart_created, name))
                return@launch
            }
            val before = vm.seen ?: return@launch
            val rules = if (vm.unreadable) before else vm.criteria.toJson()
            val r = graph.smart.save(vm.id, before, rules, name.takeIf { it != vm.seenName })
            vm.saving = false
            if (r.result != EditResult.Applied) {
                // Changed elsewhere: show them as they are now (the message says so).
                if (r.result == EditResult.Conflict) vm.load()
                return@launch actions.report(r.result, saved)
            }
            actions.nav.pop()
            val count = runCatching { graph.client.strict.getPlaylist(vm.id).ok() }.getOrNull()?.playlist?.songCount
            val message = count?.let { graph.app.resources.getQuantityString(R.plurals.smart_saved, it, it) } ?: saved
            // Undo puts the old rules (and name) back, if nobody has changed them since.
            actions.report(r.result, message) {
                val back = r.rules?.let { now -> graph.smart.save(vm.id, now, before, vm.seenName.takeIf { it != r.name }) }
                actions.report(back?.result ?: EditResult.Conflict, restored)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(stringResource(if (id == null) R.string.smart_new_title else R.string.smart_edit_rules),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            },
            navigationIcon = {
                IconButton(onClick = { if (vm.dirty && vm.state is Load.Ready) confirmDiscard = true else actions.nav.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                }
            },
            actions = {
                if (vm.state is Load.Ready) {
                    TextButton(enabled = !vm.saving && vm.dirty, onClick = ::save) {
                        Text(stringResource(if (id == null) R.string.library_create else R.string.playlist_save))
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        LoadContent(vm.state, vm::load) {
            Editor(vm, pickable)
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.smart_discard_title)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; actions.nav.pop() }) { Text(stringResource(R.string.smart_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.smart_keep_editing)) }
            },
        )
    }
}

/**
 * Where a rule being edited lives: an existing one at [path], or a new one added
 * to the group at [path] ([adding]), or the first of a new group added there
 * ([newGroup], which matches [any]).
 */
private data class Editing(val path: List<Int>, val rule: Rule, val adding: Boolean, val newGroup: Boolean = false, val any: Boolean = false)

@Composable
private fun Editor(vm: SmartRulesVm, playlists: List<Playlist>) {
    // A new playlist opens on its first rule.
    var editing by remember {
        mutableStateOf(if (vm.id == null && vm.criteria.root.items.size == 1) Editing(listOf(0), SmartRules.starter(), adding = false) else null)
    }
    var addingSort by remember { mutableStateOf(false) }
    val c = vm.criteria
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
        item(key = "name") {
            OutlinedTextField(
                vm.name, { vm.name = it },
                label = { Text(stringResource(R.string.library_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectToEdit(),
            )
        }
        if (vm.unreadable) {
            item(key = "unreadable") { Note(stringResource(R.string.smart_unreadable)) }
            return@LazyColumn
        }
        item(key = "match") { SectionHeader(stringResource(R.string.smart_songs_that_match)) }
        item(key = "rules") {
            GroupView(
                c.root, emptyList(), playlists,
                onChange = { path, node -> vm.criteria = c.copy(root = SmartRules.replace(c.root, path, node)) },
                onRemove = { path -> vm.criteria = c.copy(root = SmartRules.remove(c.root, path)) },
                onEdit = { editing = it },
                first = true,
            )
        }

        item(key = "sort") { SectionHeader(stringResource(R.string.smart_sort_header)) }
        val keys = c.sortKeys
        if (keys.isEmpty()) item(key = "sort-none") { Note(stringResource(R.string.smart_sort_default)) }
        itemsIndexed(keys, key = { i, k -> "sort$i:${k.field}" }) { i, k ->
            SortRow(k,
                onFlip = { vm.criteria = c.withSort(keys.toMutableList().also { it[i] = k.copy(descending = !k.descending) }) },
                onRemove = { vm.criteria = c.withSort(keys.toMutableList().also { it.removeAt(i) }) })
        }
        item(key = "sort-add") {
            Box(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { addingSort = true }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.smart_add_sort))
                }
                DropdownMenu(addingSort, { addingSort = false }) {
                    (SmartField.entries.filter { it.sortable }.map { it.key } + RANDOM).forEach { f ->
                        DropdownMenuItem({ Text(sortLabel(f)) }, onClick = {
                            addingSort = false
                            vm.criteria = c.withSort(keys + SortKey(f, false))
                        })
                    }
                }
            }
        }

        item(key = "limit") {
            SectionHeader(stringResource(R.string.smart_limit_header))
            var text by remember(c.limit) { mutableStateOf(c.limit?.toString().orEmpty()) }
            OutlinedTextField(
                text,
                { t ->
                    text = t.filter(Char::isDigit).take(6)
                    vm.criteria = c.copy(limit = text.toIntOrNull())
                },
                label = { Text(stringResource(R.string.smart_limit)) },
                placeholder = { Text(stringResource(R.string.smart_limit_none)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectToEdit(),
            )
        }
    }

    editing?.let { e ->
        RuleDialog(e.rule, playlists, onDismiss = { editing = null }, onDone = { rule ->
            editing = null
            val root = vm.criteria.root
            vm.criteria = vm.criteria.copy(
                root = when {
                    e.newGroup -> SmartRules.append(root, e.path, RuleGroup(e.any, listOf(rule)))
                    e.adding -> SmartRules.append(root, e.path, rule)
                    else -> SmartRules.replace(root, e.path, rule)
                },
            )
        })
    }
}

private const val RANDOM = "random"

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** A group: All/Any, its rules and groups (indented), and adding to it (the root's at its foot, a nested one's in its header). */
@Composable
private fun GroupView(
    group: RuleGroup,
    path: List<Int>,
    playlists: List<Playlist>,
    onChange: (List<Int>, RuleNode) -> Unit,
    onRemove: (List<Int>) -> Unit,
    onEdit: (Editing) -> Unit,
    first: Boolean = false,
) {
    val nested = path.isNotEmpty()
    Column(
        if (nested) Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (path.size % 2 == 1) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(vertical = 6.dp)
        else Modifier.padding(vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                listOf(false to R.string.smart_match_all, true to R.string.smart_match_any).forEachIndexed { i, (any, label) ->
                    SegmentedButton(
                        selected = group.any == any,
                        onClick = { if (group.any != any) onChange(path, group.copy(any = any)) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        modifier = if (first && i == 0) Modifier.primaryFocus() else Modifier,
                    ) { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (nested) {
                // A nested group's adds sit in its header, so the ends of groups don't stack up rows of buttons.
                var adding by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, stringResource(R.string.smart_add_to_group)) }
                    DropdownMenu(adding, { adding = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.smart_add_rule)) }, onClick = {
                            adding = false
                            onEdit(Editing(path, SmartRules.starter(), adding = true))
                        })
                        DropdownMenuItem({ Text(stringResource(R.string.smart_add_group)) }, onClick = {
                            adding = false
                            onEdit(Editing(path, SmartRules.starter(), adding = true, newGroup = true, any = !group.any))
                        })
                    }
                }
                IconButton(onClick = { onRemove(path) }) { Icon(Icons.Filled.Close, stringResource(R.string.smart_remove_group)) }
            } else Spacer(Modifier.width(12.dp))
        }
        group.items.forEachIndexed { i, node ->
            val p = path + i
            when (node) {
                is RuleGroup -> GroupView(node, p, playlists, onChange, onRemove, onEdit)
                is Rule -> RuleRow(node, playlists, onClick = if (node.understood) ({ onEdit(Editing(p, node, false)) }) else null,
                    onRemove = { onRemove(p) })
                is RawRule -> RuleRow(null, playlists, onClick = null, onRemove = { onRemove(p) }, raw = node.json.toString())
            }
        }
        if (!nested) Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { onEdit(Editing(path, SmartRules.starter(), adding = true)) }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.smart_add_rule))
            }
            TextButton(onClick = { onEdit(Editing(path, SmartRules.starter(), adding = true, newGroup = true, any = !group.any)) }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.smart_add_group))
            }
        }
    }
}

/**
 * One rule: its field, then its test and value. One the editor can't read
 * ([onClick] null) shows as written, with a lock, and can only be removed.
 */
@Composable
private fun RuleRow(rule: Rule?, playlists: List<Playlist>, onClick: (() -> Unit)?, onRemove: () -> Unit, raw: String? = null) {
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).heightIn(min = 56.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (rule != null && onClick != null) {
                val f = rule.smartField!!
                val op = rule.smartOp!!
                Text(fieldLabel(f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.smart_test_value, opLabel(op), valueText(f.type, op, rule, playlists)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        rule?.let { "${it.field} ${it.op} ${it.value}" } ?: raw.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(stringResource(R.string.smart_kept), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.smart_remove_rule)) }
    }
}

@Composable
private fun SortRow(key: SortKey, onFlip: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(sortLabel(key.field), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // Random has no direction.
        if (!key.field.equals(RANDOM, ignoreCase = true)) {
            IconButton(onClick = onFlip) {
                Icon(if (key.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    stringResource(if (key.descending) R.string.smart_descending else R.string.smart_ascending))
            }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.smart_remove_sort)) }
    }
}

@Composable
private fun sortLabel(field: String): String = when {
    field.equals(RANDOM, ignoreCase = true) -> stringResource(R.string.smart_sort_random)
    // Sort-only names Navidrome also takes, for the multi-valued tags.
    field.equals("artists", ignoreCase = true) -> fieldLabel(SmartField.ARTIST)
    field.equals("albumartists", ignoreCase = true) -> fieldLabel(SmartField.ALBUM_ARTIST)
    else -> SmartField.of(field)?.let { fieldLabel(it) } ?: field
}

/**
 * Edits one rule: field, then test, then value. Changing the field starts the
 * test and value afresh; changing the test keeps the value when it still fits.
 */
@Composable
private fun RuleDialog(start: Rule, playlists: List<Playlist>, onDismiss: () -> Unit, onDone: (Rule) -> Unit) {
    var rule by remember { mutableStateOf(start) }
    val field = rule.smartField ?: return
    val op = rule.smartOp ?: return
    // Text as typed, so a half-typed number isn't lost; checked on Done.
    var a by remember(rule.field, rule.op) { mutableStateOf(initialText(field.type, op, rule, 0)) }
    var b by remember(rule.field, rule.op) { mutableStateOf(initialText(field.type, op, rule, 1)) }
    var error by remember { mutableStateOf(false) }

    fun built(): Rule? {
        val v = SmartValue.read(field.type, op, rule.value)
        fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()?.let(SmartValue::Number)
        val value: SmartValue? = when {
            field.type == FieldType.TEXT -> SmartValue.Text(a)
            field.type == FieldType.NUMBER && op.shape == ValueShape.RANGE ->
                num(a)?.let { x -> num(b)?.let { y -> SmartValue.Range(x, y) } }
            field.type == FieldType.NUMBER -> num(a)
            op.shape == ValueShape.DAYS -> a.trim().toIntOrNull()?.takeIf { it >= 0 }?.let(SmartValue::Days)
            field.type == FieldType.PLAYLIST -> (v as? SmartValue.PlaylistRef)?.takeIf { it.id.isNotEmpty() }
            else -> v
        }
        return value?.let { rule.copy(value = SmartValue.write(it)) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.smart_rule_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Picker(stringResource(R.string.smart_field), fieldLabel(field), SmartField.entries, { fieldLabel(it) }, primary = true) { f ->
                    if (f != field) rule = SmartValue.default(f, f.ops.first())
                }
                Picker(stringResource(R.string.smart_test), opLabel(op), field.ops, { opLabel(it) }) { o ->
                    // What's typed so far comes along when it still fits.
                    if (o != op) rule = SmartValue.changeOp(built() ?: rule, o)
                }
                when {
                    field.type == FieldType.TEXT -> ValueField(a, { a = it }, stringResource(R.string.smart_value), Modifier.fillMaxWidth())
                    field.type == FieldType.NUMBER && op.shape == ValueShape.RANGE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ValueField(a, { a = it }, stringResource(R.string.smart_from), Modifier.weight(1f), number = true)
                        ValueField(b, { b = it }, stringResource(R.string.smart_to), Modifier.weight(1f), number = true)
                    }
                    field.type == FieldType.NUMBER -> ValueField(a, { a = it }, stringResource(R.string.smart_value), Modifier.fillMaxWidth(), number = true)
                    op.shape == ValueShape.DAYS -> ValueField(a, { a = it }, stringResource(R.string.smart_days_label), Modifier.fillMaxWidth(), number = true)
                    field.type == FieldType.DATE -> {
                        val v = SmartValue.read(field.type, op, rule.value)
                        if (v is SmartValue.Range) {
                            DateButton(stringResource(R.string.smart_from), (v.from as SmartValue.Date).date) {
                                rule = rule.copy(value = SmartValue.write(v.copy(from = SmartValue.Date(it))))
                            }
                            DateButton(stringResource(R.string.smart_to), (v.to as SmartValue.Date).date) {
                                rule = rule.copy(value = SmartValue.write(v.copy(to = SmartValue.Date(it))))
                            }
                        } else if (v is SmartValue.Date) {
                            DateButton(stringResource(R.string.smart_value), v.date) {
                                rule = rule.copy(value = SmartValue.write(SmartValue.Date(it)))
                            }
                        }
                    }
                    field.type == FieldType.BOOL -> {
                        val yes = (SmartValue.read(field.type, op, rule.value) as? SmartValue.Bool)?.yes ?: true
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(true to R.string.smart_yes, false to R.string.smart_no).forEachIndexed { i, (value, label) ->
                                SegmentedButton(yes == value, { rule = rule.copy(value = SmartValue.write(SmartValue.Bool(value))) },
                                    SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label)) }
                            }
                        }
                    }
                    field.type == FieldType.PLAYLIST -> {
                        val chosen = (SmartValue.read(field.type, op, rule.value) as? SmartValue.PlaylistRef)?.id
                        val name = chosen?.let { id -> playlists.firstOrNull { it.id == id }?.name ?: stringResource(R.string.smart_unknown_playlist) }
                        Picker(stringResource(R.string.smart_value), name ?: stringResource(R.string.smart_pick_playlist), playlists, { it.name }) { p ->
                            rule = rule.copy(value = SmartValue.write(SmartValue.PlaylistRef(p.id)))
                        }
                    }
                }
                if (error) Text(stringResource(R.string.smart_problem_value), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { built()?.let(onDone) ?: run { error = true } }) { Text(stringResource(R.string.playlist_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.playlist_cancel)) } },
    )
}

private fun initialText(type: FieldType, op: SmartOp, rule: Rule, part: Int): String {
    val v = SmartValue.read(type, op, rule.value) ?: return ""
    val one = if (v is SmartValue.Range) (if (part == 0) v.from else v.to) else if (part == 0) v else return ""
    return when (one) {
        is SmartValue.Text -> one.text
        is SmartValue.Number -> numberText(one.n)
        is SmartValue.Days -> one.days.toString()
        else -> ""
    }
}

private fun numberText(n: Double) = if (n == Math.rint(n) && kotlin.math.abs(n) < 1e15) n.toLong().toString() else n.toString()

/** A labelled button that opens a menu of [options]. */
@Composable
private fun <T> Picker(label: String, current: String, options: List<T>, text: @Composable (T) -> String, primary: Boolean = false, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().then(if (primary) Modifier.primaryFocus() else Modifier)) {
                Text(current, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(open, { open = false }, Modifier.heightIn(max = 360.dp)) {
                options.forEach { o -> DropdownMenuItem({ Text(text(o)) }, onClick = { open = false; onPick(o) }) }
            }
        }
    }
}

@Composable
private fun ValueField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, number: Boolean = false) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier.selectToEdit(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateButton(label: String, date: LocalDate, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(dateText(date), Modifier.weight(1f))
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.playlist_done)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.playlist_cancel)) } },
        ) { DatePicker(state) }
    }
}

private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private fun dateText(d: LocalDate): String = dateFormat.format(d)

/** Rules in one line, for telling versions apart: each rule (at any depth) as field, test and value. */
@Composable
fun rulesSummary(c: SmartCriteria, playlists: List<Playlist>): String {
    fun leaves(g: RuleGroup): List<RuleNode> = g.items.flatMap { if (it is RuleGroup) leaves(it) else listOf(it) }
    val parts = leaves(c.root).map { n ->
        if (n is Rule && n.understood) {
            val f = n.smartField!!
            val op = n.smartOp!!
            stringResource(R.string.smart_rule_summary, fieldLabel(f), opLabel(op), valueText(f.type, op, n, playlists))
        } else if (n is Rule) "${n.field} ${n.op} ${n.value}" else (n as RawRule).json.toString()
    }
    return parts.joinToString(" · ")
}

/** A rule's value as it reads in its row. */
@Composable
private fun valueText(type: FieldType, op: SmartOp, rule: Rule, playlists: List<Playlist>): String {
    @Composable
    fun one(v: SmartValue): String = when (v) {
        is SmartValue.Text -> stringResource(R.string.smart_quoted, v.text)
        is SmartValue.Number -> numberText(v.n)
        is SmartValue.Date -> dateText(v.date)
        is SmartValue.Days -> pluralStringResource(R.plurals.smart_days, v.days, v.days)
        is SmartValue.Bool -> stringResource(if (v.yes) R.string.smart_yes else R.string.smart_no)
        is SmartValue.PlaylistRef -> playlists.firstOrNull { it.id == v.id }?.name ?: stringResource(R.string.smart_unknown_playlist)
        is SmartValue.Range -> stringResource(R.string.smart_range, one(v.from), one(v.to))
    }
    return SmartValue.read(type, op, rule.value)?.let { one(it) }.orEmpty()
}

@Composable
fun fieldLabel(f: SmartField): String = stringResource(
    when (f) {
        SmartField.TITLE -> R.string.smart_field_title
        SmartField.ARTIST -> R.string.smart_field_artist
        SmartField.ALBUM_ARTIST -> R.string.smart_field_album_artist
        SmartField.ALBUM -> R.string.smart_field_album
        SmartField.GENRE -> R.string.smart_field_genre
        SmartField.YEAR -> R.string.smart_field_year
        SmartField.ORIGINAL_YEAR -> R.string.smart_field_original_year
        SmartField.RELEASE_DATE -> R.string.smart_field_release_date
        SmartField.DATE_ADDED -> R.string.smart_field_date_added
        SmartField.LAST_PLAYED -> R.string.smart_field_last_played
        SmartField.PLAY_COUNT -> R.string.smart_field_play_count
        SmartField.LOVED -> R.string.smart_field_loved
        SmartField.DATE_LOVED -> R.string.smart_field_date_loved
        SmartField.RATING -> R.string.smart_field_rating
        SmartField.PLAYLIST -> R.string.smart_field_playlist
        SmartField.DURATION -> R.string.smart_field_duration
        SmartField.TRACK_NUMBER -> R.string.smart_field_track_number
        SmartField.DISC_NUMBER -> R.string.smart_field_disc_number
        SmartField.COMPILATION -> R.string.smart_field_compilation
        SmartField.RELEASE_TYPE -> R.string.smart_field_release_type
        SmartField.RECORD_LABEL -> R.string.smart_field_record_label
        SmartField.COMPOSER -> R.string.smart_field_composer
        SmartField.MOOD -> R.string.smart_field_mood
        SmartField.BPM -> R.string.smart_field_bpm
        SmartField.COMMENT -> R.string.smart_field_comment
        SmartField.LYRICS -> R.string.smart_field_lyrics
        SmartField.HAS_COVER_ART -> R.string.smart_field_has_cover_art
        SmartField.FILE_TYPE -> R.string.smart_field_file_type
        SmartField.BITRATE -> R.string.smart_field_bitrate
        SmartField.FILE_PATH -> R.string.smart_field_file_path
    },
)

@Composable
private fun opLabel(o: SmartOp): String = stringResource(
    when (o) {
        SmartOp.IS -> R.string.smart_op_is
        SmartOp.IS_NOT -> R.string.smart_op_is_not
        SmartOp.CONTAINS -> R.string.smart_op_contains
        SmartOp.NOT_CONTAINS -> R.string.smart_op_not_contains
        SmartOp.STARTS_WITH -> R.string.smart_op_starts_with
        SmartOp.ENDS_WITH -> R.string.smart_op_ends_with
        SmartOp.GT -> R.string.smart_op_gt
        SmartOp.LT -> R.string.smart_op_lt
        SmartOp.BEFORE -> R.string.smart_op_before
        SmartOp.AFTER -> R.string.smart_op_after
        SmartOp.IN_THE_LAST -> R.string.smart_op_in_the_last
        SmartOp.NOT_IN_THE_LAST -> R.string.smart_op_not_in_the_last
        SmartOp.IN_THE_RANGE -> R.string.smart_op_in_the_range
        SmartOp.IN_PLAYLIST -> R.string.smart_op_in_playlist
        SmartOp.NOT_IN_PLAYLIST -> R.string.smart_op_not_in_playlist
    },
)
