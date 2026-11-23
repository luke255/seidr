package eu.bradshaw.seidr.ui.screens

import eu.bradshaw.seidr.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.BuildConfig
import eu.bradshaw.seidr.data.BadKind
import eu.bradshaw.seidr.data.BadReports
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.LocalIsTv
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.components.BackTopBar
import eu.bradshaw.seidr.ui.components.CardDivider
import eu.bradshaw.seidr.ui.components.CardGroup
import eu.bradshaw.seidr.ui.components.CardItem
import eu.bradshaw.seidr.ui.components.CardLabel
import eu.bradshaw.seidr.ui.components.ToggleItem
import eu.bradshaw.seidr.ui.components.Chevron
import eu.bradshaw.seidr.ui.components.LocalBottomChrome
import eu.bradshaw.seidr.ui.focusRing
import eu.bradshaw.seidr.ui.theme.ThemeChoice
import eu.bradshaw.seidr.ui.theme.colorSchemeFor
import eu.bradshaw.seidr.update.Updater
import eu.bradshaw.seidr.data.TrackSort
import eu.bradshaw.seidr.ui.components.SortButton
import eu.bradshaw.seidr.ui.components.label
import androidx.compose.material.icons.automirrored.outlined.Sort
import kotlin.math.roundToInt

@Composable
fun SettingsScreen() {
    val actions = LocalActions.current
    val graph = actions.graph
    val context = LocalContext.current
    val theme by graph.settings.theme.collectAsStateWithLifecycle()
    var signOut by remember { mutableStateOf(false) }
    val tv = LocalIsTv.current
    val kid = graph.settings.server.collectAsStateWithLifecycle().value?.isKid == true
    var exempt by remember { mutableStateOf(isBatteryExempt(context)) }
    LifecycleResumeEffect(Unit) { exempt = isBatteryExempt(context); onPauseOrDispose {} }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.settings_title))
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        Column(Modifier.verticalScroll(scroll)) {
            ViewingAsBanner()
            AccountCard(onSignOut = { signOut = true })

            CardLabel(stringResource(R.string.settings_appearance))
            CardGroup { ThemePicker(ThemeChoice.fromKey(theme)) { graph.settings.setTheme(it.key) } }

            CardLabel(stringResource(R.string.settings_visualiser))
            CardGroup {
                CardItem(Icons.Filled.GraphicEq, stringResource(R.string.settings_vis_item), stringResource(R.string.settings_vis_item_hint),
                    trailing = { Chevron() }) { actions.open(Route.Visualiser) }
            }

            CardLabel(stringResource(if (tv) R.string.settings_playback else R.string.settings_playback_downloads))
            CardGroup {
                val evenOut by graph.loudness.on.collectAsStateWithLifecycle()
                val tagged by graph.loudness.fraction.collectAsStateWithLifecycle()
                val hint = stringResource(R.string.settings_replay_gain_hint)
                val chosen by graph.settings.replayGainChoice.collectAsStateWithLifecycle()
                // "Once most of it is" follows the coverage sentence, so only with it.
                val auto = if (chosen == null && tagged != null) stringResource(R.string.settings_replay_gain_auto) else null
                val coverage = tagged?.let { stringResource(R.string.settings_replay_gain_coverage, (it * 100).roundToInt()) }
                ToggleItem(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.settings_replay_gain), listOfNotNull(hint, coverage, auto).joinToString(" "), evenOut) {
                    graph.settings.setReplayGain(it)
                }
                // TVs are on mains power, and don't download.
                if (!tv) {
                    CardDivider()
                    CardItem(
                        Icons.Outlined.BatteryChargingFull, stringResource(R.string.settings_background_playback),
                        stringResource(if (exempt) R.string.settings_battery_off else R.string.settings_battery_tap),
                        trailing = {
                            if (exempt) Icon(Icons.Filled.CheckCircle, stringResource(R.string.settings_on), tint = MaterialTheme.colorScheme.primary)
                            else Chevron()
                        },
                    ) { requestBatteryExemption(context) }
                    CardDivider()
                    MobileStreamingItem()
                    CardDivider()
                    CardItem(Icons.Outlined.Download, stringResource(R.string.settings_downloads_storage), stringResource(R.string.settings_downloads_storage_hint),
                        trailing = { Chevron() }) { actions.open(Route.Downloads) }
                }
            }

            // Kids don't keep other kids' logins, or report problems.
            if (!kid) KidAccountsSection()

            // The version line at the foot can't take focus, so a remote would never
            // scroll to it: reaching the last controls scrolls to the very end.
            Column(Modifier.onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(scroll.maxValue) } }) {
            // Only admins get other people's private playlists, so only they see this.
            val othersOn by graph.settings.othersPrivatePlaylists.collectAsStateWithLifecycle()
            val anyOthers = graph.library.allPlaylists.collectAsStateWithLifecycle().value.orEmpty().any(graph.library::isOthersPrivate)
            CardLabel(stringResource(R.string.settings_library))
            CardGroup {
                val sortDefault by graph.settings.playlistSortDefault.collectAsStateWithLifecycle()
                CardItem(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.settings_playlist_order), stringResource(R.string.settings_playlist_order_hint),
                    trailing = { SortButton(TrackSort.entries, sortDefault, TrackSort::label, graph.settings::setPlaylistSortDefault) })
                if (!kid) {
                    CardDivider()
                    val share by graph.settings.shareListening.collectAsStateWithLifecycle()
                    ToggleItem(Icons.Outlined.Groups, stringResource(R.string.settings_share_listening),
                        stringResource(R.string.settings_share_listening_hint), share) {
                        graph.settings.setShareListening(it)
                    }
                    CardDivider()
                    CardItem(Icons.Outlined.RestoreFromTrash, stringResource(R.string.deleted_playlists), stringResource(R.string.deleted_playlists_summary),
                        trailing = { Chevron() }) { actions.open(Route.DeletedPlaylists) }
                    if (othersOn || anyOthers) {
                        CardDivider()
                        ToggleItem(Icons.Outlined.Lock, stringResource(R.string.settings_others_private),
                            stringResource(R.string.settings_others_private_hint), othersOn) {
                            graph.settings.setOthersPrivatePlaylists(it)
                        }
                    }
                }
            }

            if (!kid) {
            CardLabel(stringResource(R.string.settings_reporting))
            val badOn by graph.settings.badReports.collectAsStateWithLifecycle()
            val found by graph.bad.playlists.collectAsStateWithLifecycle()
            val allLoaded by graph.library.allPlaylists.collectAsStateWithLifecycle()
            val missing = BadKind.entries.filter { it !in found }.map { it.playlistName }
            val reportInfo = stringResource(R.string.settings_report_info, BadKind.SONG.playlistName, BadKind.ART.playlistName, BadKind.LYRICS.playlistName)
            val reportMissing = stringResource(R.string.settings_report_missing, missing.joinToString())
            CardGroup {
                ToggleItem(Icons.Outlined.Flag, stringResource(R.string.settings_report_problems), stringResource(R.string.settings_report_problems_hint), badOn) {
                    graph.settings.setBadReports(it)
                }
                Text(
                    buildString {
                        append(reportInfo)
                        if (badOn && allLoaded != null && missing.isNotEmpty()) append(" ").append(reportMissing)
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 14.dp),
                )
                // Only once the server has any (or it's been switched off, so it can go back on).
                val hideBad by graph.settings.hideBadPlaylists.collectAsStateWithLifecycle()
                if (!hideBad || allLoaded.orEmpty().any { BadReports.isBadPlaylist(it.name) }) {
                    CardDivider()
                    ToggleItem(Icons.Outlined.VisibilityOff, stringResource(R.string.settings_hide_bad),
                        stringResource(R.string.settings_hide_lists_hint, BadKind.SONG.playlistName, BadKind.ART.playlistName, BadKind.LYRICS.playlistName), hideBad) {
                        graph.settings.setHideBadPlaylists(it)
                    }
                }
            }
            }

            CardLabel(stringResource(R.string.settings_about))
            CardGroup {
                // Release builds only: debug builds are another package and don't self-update.
                if (BuildConfig.UPDATE_URL.isNotEmpty()) {
                    val auto by graph.settings.autoUpdate.collectAsStateWithLifecycle()
                    ToggleItem(Icons.Outlined.Autorenew, stringResource(R.string.settings_auto_update), stringResource(R.string.settings_auto_update_hint), auto) {
                        graph.settings.setAutoUpdate(it)
                    }
                    CardDivider()
                    var checking by remember { mutableStateOf(false) }
                    val latestMessage = stringResource(R.string.settings_latest_version)
                    val available by Updater.available.collectAsStateWithLifecycle()
                    CardItem(
                        Icons.Outlined.SystemUpdate,
                        stringResource(if (available != null) R.string.settings_install_update else R.string.settings_check_updates),
                        when {
                            checking -> stringResource(R.string.settings_checking)
                            available != null -> stringResource(R.string.settings_version_ready, available.orEmpty())
                            else -> stringResource(R.string.settings_installs_straight_away)
                        },
                        trailing = { if (available != null) NewBadge() },
                    ) {
                        val activity = context as? android.app.Activity ?: return@CardItem
                        if (checking) return@CardItem
                        checking = true
                        Updater.refresh(activity) { version ->
                            checking = false
                            if (version == null) actions.toast(latestMessage)
                        }
                    }
                    CardDivider()
                }
                CardItem(Icons.Outlined.NewReleases, stringResource(R.string.settings_whats_new), stringResource(R.string.settings_whats_new_hint),
                    trailing = { Chevron() }) { actions.open(Route.WhatsNew) }
                CardDivider()
                CardItem(Icons.Outlined.Description, stringResource(R.string.settings_licences), stringResource(R.string.settings_licences_hint),
                    trailing = { Chevron() }) { actions.open(Route.Licences) }
                CardDivider()
                CardItem(Icons.Outlined.BugReport, stringResource(R.string.settings_share_log), stringResource(R.string.settings_share_log_hint),
                    trailing = { Chevron() }) { actions.open(Route.Log) }
                CardDivider()
                CardItem(Icons.Outlined.Info, stringResource(R.string.settings_app_version, BuildConfig.VERSION_NAME), stringResource(R.string.settings_build, BuildConfig.VERSION_CODE))
            }
            }
            Spacer(Modifier.height(24.dp + LocalBottomChrome.current))
        }
    }

    if (signOut) {
        AlertDialog(
            onDismissRequest = { signOut = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_message)) },
            confirmButton = {
                TextButton(onClick = {
                    signOut = false
                    eu.bradshaw.seidr.data.Account.signOut(graph)
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { signOut = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

/** Who's signed in, to which server, with the two things to do about it. */
@Composable
private fun AccountCard(onSignOut: () -> Unit) {
    val actions = LocalActions.current
    val server by actions.graph.settings.server.collectAsStateWithLifecycle()
    val online by actions.graph.status.online.collectAsStateWithLifecycle()
    val unprotected by actions.graph.settings.secretsUnprotected.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                Text(server?.username?.firstOrNull()?.uppercase() ?: "?", color = cs.onPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(server?.username ?: stringResource(R.string.settings_not_signed_in), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) Color(0xFF7EE08A) else cs.outline))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        server?.url?.substringAfter("://")?.trimEnd('/').let { host ->
                            if (server?.kidOf != null) stringResource(R.string.settings_server_kid, host ?: stringResource(R.string.settings_not_connected))
                            else host ?: stringResource(R.string.settings_not_connected)
                        },
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (unprotected && server != null) {
            Text(stringResource(R.string.settings_secrets_unprotected), style = MaterialTheme.typography.bodyMedium, color = cs.error,
                modifier = Modifier.padding(top = 12.dp))
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { actions.open(Route.SignInDevice) },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.weight(1f).focusRing(CircleShape),
            ) {
                Icon(Icons.Filled.Devices, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_sign_in_device), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.settings_sign_out)) }
        }
    }
}

/** Save mobile data: lower-bitrate streams on metered networks (see `StreamQuality`). */
@Composable
private fun MobileStreamingItem() {
    val graph = LocalActions.current.graph
    val on by graph.settings.saveMobileData.collectAsStateWithLifecycle()
    val supported by graph.streamQuality.supported.collectAsStateWithLifecycle()
    LaunchedEffect(on) { if (on) graph.streamQuality.check() }
    ToggleItem(
        Icons.Outlined.SignalCellularAlt, stringResource(R.string.settings_save_mobile_data),
        stringResource(if (on && supported == false) R.string.settings_mobile_unsupported else R.string.settings_save_mobile_data_hint),
        on,
    ) { graph.settings.setSaveMobileData(it) }
}

@Composable
private fun NewBadge() {
    Text(
        stringResource(R.string.settings_new_badge), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onTertiary,
        modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.tertiary).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun ThemePicker(selected: ThemeChoice, onSelect: (ThemeChoice) -> Unit) {
    val context = LocalContext.current
    val swatches = remember(context) { ThemeChoice.entries.associateWith { colorSchemeFor(it, context).primary } }
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_colour), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                when (selected) {
                    ThemeChoice.SYSTEM -> stringResource(R.string.settings_theme_system)
                    ThemeChoice.NEUTRAL -> stringResource(R.string.settings_theme_neutral)
                    else -> stringResource(selected.labelRes)
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ThemeChoice.entries.forEach { choice ->
                val isSelected = choice == selected
                val label = stringResource(choice.labelRes)
                val fill = if (choice == ThemeChoice.NEUTRAL) Color(0xFF2C2C2C) else swatches.getValue(choice)
                Box(
                    Modifier.size(42.dp)
                        .focusRing(CircleShape)
                        .border(2.dp, if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(fill)
                        .then(if (choice == ThemeChoice.NEUTRAL) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier)
                        .selectable(isSelected, role = Role.RadioButton) { onSelect(choice) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    val tint = if (choice == ThemeChoice.NEUTRAL) Color.White else Color.Black.copy(alpha = 0.7f)
                    when {
                        isSelected -> Icon(Icons.Default.Check, null, tint = tint, modifier = Modifier.size(20.dp))
                        choice == ThemeChoice.SYSTEM -> Icon(Icons.Default.AutoAwesome, null, tint = tint, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

fun isBatteryExempt(context: android.content.Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/** Sideloaded, so the direct exemption request is fine (no Play policy applies). */
fun requestBatteryExemption(context: android.content.Context) {
    val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(direct) }.onFailure {
        runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
