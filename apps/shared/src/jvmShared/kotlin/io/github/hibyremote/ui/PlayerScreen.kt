package io.github.hibyremote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowRightAlt
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hibyremote.remote.nowMs
import io.github.hibyremote.protocol.PlayMode
import io.github.hibyremote.remote.LinkState
import io.github.hibyremote.remote.PlayerState
import io.github.hibyremote.remote.RemoteController
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    controller: RemoteController,
    state: PlayerState,
    snackbar: SnackbarHostState,
    onOpenDevices: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val title = state.device.model.ifBlank { state.target?.name ?: "HiBy Remote" }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (state.link) {
                                LinkState.CONNECTED -> state.target?.label ?: "Connected"
                                LinkState.CONNECTING -> "Connecting…"
                                LinkState.IDLE -> "Not connected"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenLibrary, enabled = state.connected) {
                        Icon(Icons.Rounded.LibraryMusic, contentDescription = "Library")
                    }
                    IconButton(onClick = onOpenDevices) {
                        Icon(Icons.Rounded.Devices, contentDescription = "Players")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.connected) {
                LinkBanner(state, onRetry = { state.target?.let(controller::connect) }, onOpenDevices = onOpenDevices)
                Spacer(Modifier.height(16.dp))
            }
            Cover(state)
            Spacer(Modifier.height(20.dp))
            TrackInfo(state)
            Spacer(Modifier.height(12.dp))
            ProgressBar(state, enabled = state.connected, onSeek = controller::seek)
            Spacer(Modifier.height(8.dp))
            Transport(state, controller)
            Spacer(Modifier.height(16.dp))
            VolumeBar(state, controller)
            Spacer(Modifier.height(12.dp))
            ModeSelector(state, controller)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LinkBanner(state: PlayerState, onRetry: () -> Unit, onOpenDevices: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (state.link == LinkState.CONNECTING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("Connecting to ${state.target?.name ?: "the player"}…")
                }
            } else {
                Text(
                    if (state.target == null) "No player selected."
                    else (state.error ?: "Not connected to ${state.target.name}."),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.target != null) Button(onClick = onRetry) { Text("Connect") }
                    OutlinedButton(onClick = onOpenDevices) { Text("Choose a player") }
                }
            }
        }
    }
}

@Composable
private fun Cover(state: PlayerState) {
    val image = state.cover
    Box(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = "Album cover", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackInfo(state: PlayerState) {
    val m = state.meta
    val titleText = m?.title?.ifBlank { null } ?: m?.path?.substringAfterLast('/')?.ifBlank { null }
        ?: if (state.connected) "Nothing playing" else "—"
    Text(
        titleText,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    val sub = listOfNotNull(m?.artist?.ifBlank { null }, m?.album?.ifBlank { null }).joinToString(" — ")
    if (sub.isNotEmpty()) {
        Text(
            sub,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (m != null) {
        val quality = listOfNotNull(
            fileExtension(m.path).ifBlank { null },
            m.sampleBits.takeIf { it > 0 }?.let { "$it-bit" },
            formatRate(m.sampleRate).ifBlank { null },
            m.bitrate.takeIf { it > 0 }?.let { "$it kbps" },
        ).joinToString(" · ")
        if (quality.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(quality, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProgressBar(state: PlayerState, enabled: Boolean, onSeek: (Long) -> Unit) {
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(state.playing, state.progressAt) {
        now = nowMs()
        while (state.playing) {
            delay(250)
            now = nowMs()
        }
    }
    val duration = state.meta?.durationMs ?: 0L
    var dragging by remember { mutableStateOf<Float?>(null) }
    val position = state.positionMs(now)
    val shown = dragging?.toLong() ?: position

    Slider(
        value = if (duration > 0) shown.coerceIn(0, duration).toFloat() else 0f,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { onSeek(it.toLong()) }
            dragging = null
        },
        valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
        enabled = enabled && duration > 0,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(shown), style = MaterialTheme.typography.labelMedium)
        Text(if (duration > 0) formatTime(duration) else "--:--", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Transport(state: PlayerState, controller: RemoteController) {
    val enabled = state.connected
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = controller::stop, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Stop, contentDescription = "Stop")
        }
        IconButton(onClick = controller::previous, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
        }
        FilledIconButton(
            onClick = controller::togglePlay,
            enabled = enabled,
            modifier = Modifier.size(76.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            Icon(
                if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.playing) "Pause" else "Play",
                modifier = Modifier.size(44.dp),
            )
        }
        IconButton(onClick = controller::next, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun VolumeBar(state: PlayerState, controller: RemoteController) {
    val max = state.maxVolume.coerceAtLeast(1)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val volume = state.volume
    val enabled = state.connected && volume != null
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = { controller.volumeStep(-1) }, enabled = enabled) {
            Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = "Volume down")
        }
        Slider(
            value = dragging ?: (volume ?: 0).toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { controller.setVolume(Math.round(it)) }
                dragging = null
            },
            valueRange = 0f..max.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { controller.volumeStep(1) }, enabled = enabled) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Volume up")
        }
        Text(
            (dragging?.let { Math.round(it) } ?: volume)?.toString() ?: "–",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.widthIn(min = 32.dp),
            textAlign = TextAlign.End,
        )
    }
}

private data class ModeOption(val mode: Int, val label: String, val icon: ImageVector)

private val MODES = listOf(
    ModeOption(PlayMode.ORDER, "In order", Icons.AutoMirrored.Rounded.ArrowRightAlt),
    ModeOption(PlayMode.REPEAT_ALL, "Repeat all", Icons.Rounded.Repeat),
    ModeOption(PlayMode.REPEAT_ONE, "Repeat one", Icons.Rounded.RepeatOne),
    ModeOption(PlayMode.SHUFFLE, "Shuffle", Icons.Rounded.Shuffle),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(state: PlayerState, controller: RemoteController) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        MODES.forEachIndexed { i, opt ->
            SegmentedButton(
                selected = state.playMode == opt.mode,
                onClick = { controller.setPlayMode(opt.mode) },
                enabled = state.connected,
                shape = SegmentedButtonDefaults.itemShape(i, MODES.size),
                icon = {},
                label = { Icon(opt.icon, contentDescription = opt.label) },
            )
        }
    }
}
