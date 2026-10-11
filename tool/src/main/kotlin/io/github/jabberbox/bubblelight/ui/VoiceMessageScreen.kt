package io.github.jabberbox.bubblelight.ui

import android.Manifest
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioException
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioRecorder
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import io.github.jabberbox.bubblelight.BubbleGraph
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class VoiceState { PermissionRequired, Ready, Recording, Review }

/**
 * Records a voice message with the SDK's recorder, lets it be played back, and hands the file to whoever opened the
 * screen when it is sent. Nothing is recorded until the record button is pressed, and a recording that is thrown
 * away or abandoned is deleted.
 */
class VoiceMessageViewModel(private val voiceDir: File, audio: LightAudio) : LightViewModel<String>() {
    val state = MutableStateFlow(VoiceState.PermissionRequired)
    val elapsedMs = MutableStateFlow(0L)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val playing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private val recorder: LightAudioRecorder = audio.newRecorder()
    private val player: LightAudioPlayer = audio.newPlayer()
    private var file: File? = null
    private var startedAt = 0L
    private var ticker: Job? = null
    private var playerJob: Job? = null

    init {
        playerJob = viewModelScope.launch {
            launch { player.positionMs.collect(positionMs::emit) }
            launch { player.durationMs.collect(durationMs::emit) }
            launch { player.isPlaying.collect(playing::emit) }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<String>) {
        viewModelScope.launch { refreshPermission() }
    }

    override fun onScreenHide(screen: SimpleLightScreen<String>) {
        // Leaving the screen mid-recording stops it and keeps what there is, so nothing records unseen.
        if (state.value == VoiceState.Recording) finish()
        player.pause()
    }

    private suspend fun refreshPermission() {
        val granted = checkPermission(Manifest.permission.RECORD_AUDIO).asKotlinResult
            .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
            .getOrDefault(false)
        if (state.value != VoiceState.Recording && state.value != VoiceState.Review) {
            state.value = if (granted) VoiceState.Ready else VoiceState.PermissionRequired
        }
    }

    fun record() {
        if (state.value != VoiceState.Ready) return
        error.value = null
        val target = File(voiceDir, "voice-${UUID.randomUUID()}.m4a")
        try {
            recorder.start(target)
        } catch (e: LightAudioException) {
            target.delete()
            error.value = e.message ?: "Recording failed"
            return
        }
        file = target
        startedAt = SystemClock.elapsedRealtime()
        elapsedMs.value = 0L
        state.value = VoiceState.Recording
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive && state.value == VoiceState.Recording) {
                elapsedMs.value = SystemClock.elapsedRealtime() - startedAt
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        if (state.value == VoiceState.Recording) finish()
    }

    private fun finish() {
        ticker?.cancel()
        val duration = recorder.stop()
        elapsedMs.value = duration
        val recorded = file?.takeIf { duration > 0L && it.exists() }
        if (recorded == null) {
            file?.delete()
            file = null
            state.value = VoiceState.Ready
        } else {
            player.setSource(recorded)
            state.value = VoiceState.Review
        }
    }

    fun togglePlayback() {
        if (playing.value) {
            player.pause()
        } else {
            if (durationMs.value > 0L && positionMs.value >= durationMs.value) player.seekTo(0L)
            player.play()
        }
    }

    /** Throws the recording away and goes back to ready to record again. */
    fun discard() {
        player.stop()
        file?.delete()
        file = null
        elapsedMs.value = 0L
        state.value = VoiceState.Ready
    }

    /** The recorded file, which the caller now owns. */
    fun take(): File? {
        player.stop()
        return file.also { file = null }
    }

    override fun onCleared() {
        ticker?.cancel()
        playerJob?.cancel()
        recorder.release()
        player.release()
        // A recording that was never sent has nowhere to go.
        file?.delete()
        super.onCleared()
    }

    private companion object {
        const val TICK_MS = 100L
    }
}

/** The recorder: a permission prompt if needed, then record, stop, listen back, and send or discard. */
class VoiceMessageScreen(
    private val sealed: SealedLightActivity,
    private val title: String,
) : BubbleScreen<String, VoiceMessageViewModel>(sealed) {
    override val viewModelClass: Class<VoiceMessageViewModel>
        get() = VoiceMessageViewModel::class.java

    override fun createViewModel() = VoiceMessageViewModel(graph.voiceDir, DefaultLightAudio(sealed))

    @Composable
    override fun Body() {
        val permission = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)
        val state by viewModel.state.collectAsState()
        val elapsed by viewModel.elapsedMs.collectAsState()
        val position by viewModel.positionMs.collectAsState()
        val duration by viewModel.durationMs.collectAsState()
        val playing by viewModel.playing.collectAsState()
        val error by viewModel.error.collectAsState()
        BubbleTheme {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            val (message, bar) = when (state) {
                VoiceState.PermissionRequired -> "Bubble Light needs the microphone to record a voice message." to
                    listOf(LightBarButton.Text("ALLOW", onClick = { permission?.launch() }))
                VoiceState.Ready -> (error ?: "Tap to record") to
                    listOf(LightBarButton.LightIcon(LightIcons.MICROPHONE, viewModel::record))
                VoiceState.Recording -> "Recording\n${clock(elapsed)}" to
                    listOf(LightBarButton.LightIcon(LightIcons.STOP, viewModel::stop))
                VoiceState.Review -> "${clock(position)} / ${clock(duration)}" to listOf(
                    LightBarButton.LightIcon(LightIcons.DELETE, viewModel::discard),
                    LightBarButton.LightIcon(if (playing) LightIcons.PAUSE else LightIcons.PLAY, viewModel::togglePlayback),
                    LightBarButton.LightIcon(LightIcons.SEND, ::send),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LightText(
                    text = message,
                    variant = LightTextVariant.Copy,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
                )
            }
            LightBottomBar(bar)
        }
    }

    private fun send() {
        val recorded = viewModel.take() ?: return
        goBack(recorded.absolutePath)
    }

    private fun clock(ms: Long): String = MessageFormat.clock(ms)
}
