package org.jellyfin.mobile.player

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.ui.DecoderType
import org.jellyfin.mobile.player.ui.playermenuhelper.PlayerMenuHelper
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.extensions.end
import org.jellyfin.mobile.utils.extensions.start
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.extensions.ticks
import kotlin.time.Duration.Companion.milliseconds

/**
 * Connects the player UI to the [PlaybackService], which owns the player and the playback session.
 *
 * Forwards user interactions to the service and mirrors its state for the fragment.
 */
@Suppress("TooManyFunctions")
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val serviceDeferred = CompletableDeferred<PlaybackService>()

    /**
     * The bound service. It is unbound again in [onCleared], so it never outlives the view model.
     */
    @SuppressLint("StaticFieldLeak")
    private var playbackService: PlaybackService? = null

    private val _player = MediatorLiveData<ExoPlayer?>()
    private val _playerState = MediatorLiveData<Int>()
    private val _decoderType = MediatorLiveData<DecoderType>()
    private val _error = MediatorLiveData<String>()
    private val _currentMediaSource = MediatorLiveData<JellyfinMediaSource>()
    val player: LiveData<ExoPlayer?> get() = _player
    val playerState: LiveData<Int> get() = _playerState
    val decoderType: LiveData<DecoderType> get() = _decoderType
    val error: LiveData<String> get() = _error
    val currentMediaSource: LiveData<JellyfinMediaSource> get() = _currentMediaSource

    val playerOrNull: ExoPlayer? get() = playbackService?.playerOrNull
    val mediaSourceOrNull: JellyfinMediaSource? get() = playbackService?.mediaSourceOrNull

    /**
     * Whether playback ended and the player UI should close.
     *
     * Binding is asynchronous, so a missing player only counts once the service is connected.
     */
    val isPlaybackFinished: Boolean get() = playbackService?.playerOrNull == null && playbackService != null

    private var playerMenuHelper: PlayerMenuHelper? = null
    private var chapterMarkingUpdateJob: Job? = null
    private var skipMediaSegmentUpdateJob: Job? = null

    /**
     * Refreshes the overlays that track the playback position after a seek
     */
    private val positionListener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            setWatchedChapterMarkings()
            updateSkipMediaSegmentButton()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? PlaybackService.PlaybackBinder)?.service ?: return
            playbackService = service

            _player.addSource(service.player) { player ->
                _player.value?.removeListener(positionListener)
                player?.addListener(positionListener)
                _player.value = player
            }
            _playerState.addSource(service.playerState, _playerState::setValue)
            _decoderType.addSource(service.decoderType, _decoderType::setValue)
            _error.addSource(service.error, _error::setValue)
            _currentMediaSource.addSource(service.queueManager.currentMediaSource, _currentMediaSource::setValue)

            startOverlayUpdates()
            serviceDeferred.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
        }
    }

    init {
        val context = getApplication<Application>()
        context.bindService(
            PlaybackService.createLocalBindIntent(context),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private suspend fun requireService(): PlaybackService = serviceDeferred.await()

    override fun onCleared() {
        chapterMarkingUpdateJob?.cancel()
        skipMediaSegmentUpdateJob?.cancel()
        playerOrNull?.removeListener(positionListener)
        // Closing the player UI ends the playback session
        playbackService?.stop()
        getApplication<Application>().unbindService(serviceConnection)
    }

    suspend fun initializePlaybackQueue(playOptions: PlayOptions): PlayerException? =
        requireService().queueManager.initializePlaybackQueue(playOptions)

    fun hasNext(): Boolean = playbackService?.queueManager?.hasNext() == true

    suspend fun selectAudioTrack(mediaStreamIndex: Int): Boolean =
        requireService().trackSelectionHelper.selectAudioTrack(mediaStreamIndex)

    suspend fun selectSubtitleTrack(mediaStreamIndex: Int): Boolean =
        requireService().trackSelectionHelper.selectSubtitleTrack(mediaStreamIndex)

    suspend fun toggleSubtitles(): Boolean = requireService().trackSelectionHelper.toggleSubtitles()

    suspend fun changeBitrate(bitrate: Int?): Boolean = requireService().changeBitrate(bitrate)

    fun play() = withService { play() }

    fun pause() = withService { pause() }

    fun rewind() = withService { rewind() }

    fun fastForward() = withService { fastForward() }

    fun seekByOffset(offsetMs: Long) = withService { seekByOffset(offsetMs) }

    fun previousChapter() = withService { previousChapter() }

    fun nextChapter() = withService { nextChapter() }

    fun skipToPrevious() = withService { skipToPrevious() }

    fun skipToNext() = withService { skipToNext() }

    fun skipMediaSegment(mediaSegmentDto: MediaSegmentDto?) = withService { skipMediaSegment(mediaSegmentDto) }

    fun updateDecoderType(type: DecoderType) = withService { updateDecoderType(type) }

    fun setPlaybackSpeed(speed: Float): Boolean = playbackService?.setPlaybackSpeed(speed) == true

    fun setPressSpeedUp(isPressing: Boolean, speed: Float): Boolean =
        playbackService?.setPressSpeedUp(isPressing, speed) == true

    fun setPlayerMenuHelper(menuHelper: PlayerMenuHelper) {
        playerMenuHelper = menuHelper
    }

    private inline fun withService(action: PlaybackService.() -> Unit) {
        playbackService?.action()
    }

    private fun startOverlayUpdates() {
        chapterMarkingUpdateJob = viewModelScope.launch {
            while (true) {
                delay(Constants.CHAPTER_MARKING_UPDATE_DELAY)
                setWatchedChapterMarkings()
            }
        }
        skipMediaSegmentUpdateJob = viewModelScope.launch {
            while (true) {
                delay(Constants.SKIP_MEDIA_SEGMENT_UPDATE_DELAY)
                updateSkipMediaSegmentButton()
            }
        }
    }

    private fun setWatchedChapterMarkings() {
        val playbackPosition = playerOrNull?.currentPosition?.milliseconds ?: return
        val chapters = mediaSourceOrNull?.item?.chapters ?: return
        val startPositions = chapters.map { chapter -> chapter.startPositionTicks.ticks }
        val chapterMarkings = playerMenuHelper?.chapterMarkings?.markings ?: return

        startPositions.zip(chapterMarkings).forEach { (position, marking) ->
            val color = when {
                playbackPosition >= position -> R.color.jellyfin_accent
                else -> R.color.playback_timebar_unplayed
            }
            marking.setColor(color)
        }
    }

    private fun updateSkipMediaSegmentButton() {
        val mediaSegments = playbackService?.askToSkipMediaSegments.orEmpty()
        if (mediaSegments.isEmpty()) return

        val playbackPosition = playerOrNull?.currentPosition?.milliseconds ?: return
        val currentMediaSegment = mediaSegments.find { segment -> playbackPosition in segment.start..segment.end }
        val skipMediaSegmentButton = playerMenuHelper?.skipMediaSegmentButton ?: return
        when (currentMediaSegment) {
            null -> skipMediaSegmentButton.hideSkipSegmentButton()
            else -> skipMediaSegmentButton.showSkipSegmentButton(currentMediaSegment)
        }
    }
}
