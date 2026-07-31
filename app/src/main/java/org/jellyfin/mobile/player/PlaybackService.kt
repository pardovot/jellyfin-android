package org.jellyfin.mobile.player

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.PLAYER_EVENT_CHANNEL
import org.jellyfin.mobile.player.interaction.PlayerEvent
import org.jellyfin.mobile.player.mediasegments.MediaSegmentAction
import org.jellyfin.mobile.player.mediasegments.MediaSegmentRepository
import org.jellyfin.mobile.player.queue.QueueManager
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.mobile.player.ui.DecoderType
import org.jellyfin.mobile.player.ui.DisplayPreferences
import org.jellyfin.mobile.player.ui.PlayState
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.applyDefaultAudioAttributes
import org.jellyfin.mobile.utils.createMediaNotificationChannel
import org.jellyfin.mobile.utils.extensions.end
import org.jellyfin.mobile.utils.extensions.scaleInRange
import org.jellyfin.mobile.utils.extensions.start
import org.jellyfin.mobile.utils.extensions.width
import org.jellyfin.mobile.utils.getVolumeLevelPercent
import org.jellyfin.mobile.utils.getVolumeRange
import org.jellyfin.mobile.utils.logTracks
import org.jellyfin.mobile.utils.seekToOffset
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.hlsSegmentApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.qualifier.named
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Hosts the integrated player.
 *
 * Playback lives in the service instead of the player UI so it keeps running in the background.
 * [MediaSessionService] handles the media notification, the foreground service and media buttons.
 */
@Suppress("TooManyFunctions")
class PlaybackService : MediaSessionService(), KoinComponent, Player.Listener {
    private val apiClient: ApiClient by inject()
    private val displayPreferencesApi by lazy { apiClient.displayPreferencesApi }
    private val playStateApi by lazy { apiClient.playStateApi }
    private val hlsSegmentApi by lazy { apiClient.hlsSegmentApi }
    private val userApi by lazy { apiClient.userApi }

    private val appPreferences: AppPreferences by inject()
    private val mediaSegmentRepository: MediaSegmentRepository by inject()
    private val playerEventChannel: Channel<PlayerEvent> by inject(named(PLAYER_EVENT_CHANNEL))
    private val audioManager: AudioManager by lazy { getSystemService()!! }

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    val queueManager = QueueManager(this)
    private val trackSelector by lazy { DefaultTrackSelector(this) }
    val trackSelectionHelper by lazy { TrackSelectionHelper(this, trackSelector) }

    private val _player = MutableLiveData<ExoPlayer?>()
    private val _playerState = MutableLiveData<Int>()
    private val _decoderType = MutableLiveData<DecoderType>()
    private val _error = MutableLiveData<String>()
    val player: LiveData<ExoPlayer?> get() = _player
    val playerState: LiveData<Int> get() = _playerState
    val decoderType: LiveData<DecoderType> get() = _decoderType
    val error: LiveData<String> get() = _error

    val playerOrNull: ExoPlayer? get() = _player.value
    val mediaSourceOrNull: JellyfinMediaSource? get() = queueManager.getCurrentMediaSourceOrNull()

    private var mediaSession: MediaSession? = null

    private val eventLogger = EventLogger()
    private var analyticsCollector = buildAnalyticsCollector()
    private val initialTracksSelected = AtomicBoolean(false)
    private var fallbackPreferExtensionRenderers = false
    private var playSpeed = 1f

    private var progressUpdateJob: Job? = null
    private var fallbackRetryJob: Job? = null

    private var displayPreferences = DisplayPreferences()
    private var autoPlayNextEpisodeEnabled = false

    /**
     * Media segments that the user should be asked to skip, exposed for the player UI.
     */
    var askToSkipMediaSegments: List<MediaSegmentDto> = emptyList()
        private set

    private val binder = PlaybackBinder()

    private val backgroundObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (!appPreferences.exoPlayerAllowBackgroundAudio) pause()
        }
    }

    inner class PlaybackBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()

        getSystemService<NotificationManager>()?.let { notificationManager ->
            createMediaNotificationChannel(notificationManager)
        }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(Constants.VIDEO_PLAYER_NOTIFICATION_ID)
                .setChannelId(Constants.MEDIA_NOTIFICATION_CHANNEL_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )

        setListener(
            object : Listener {
                override fun onForegroundServiceStartNotAllowedException() {
                    Timber.e("Not allowed to start the playback foreground service")
                }
            },
        )

        setupPlayer()
        loadUserPreferences()

        ProcessLifecycleOwner.get().lifecycle.addObserver(backgroundObserver)

        serviceScope.launch {
            for (event in playerEventChannel) {
                when (event) {
                    PlayerEvent.Pause -> pause()
                    PlayerEvent.Resume -> play()
                    PlayerEvent.Stop, PlayerEvent.Destroy -> stop()
                    is PlayerEvent.Seek -> playerOrNull?.seekTo(event.duration.inWholeMilliseconds)
                    is PlayerEvent.SetVolume -> {
                        setVolume(event.volume)
                        playerOrNull?.reportPlaybackState()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = when (intent?.action) {
        ACTION_BIND_LOCAL -> binder
        else -> super.onBind(intent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The player UI is gone along with the task, so end the playback session
        stop()
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(backgroundObserver)
        releasePlayer()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildAnalyticsCollector() = DefaultAnalyticsCollector(Clock.DEFAULT).apply {
        addListener(eventLogger)
    }

    private fun loadUserPreferences() {
        serviceScope.launch {
            var customPrefs: Map<String, String?>? = null
            try {
                val displayPreferencesDto = withContext(Dispatchers.IO) {
                    displayPreferencesApi.getDisplayPreferences(
                        displayPreferencesId = Constants.DISPLAY_PREFERENCES_ID_USER_SETTINGS,
                        client = Constants.DISPLAY_PREFERENCES_CLIENT_EMBY,
                    ).content
                }

                customPrefs = displayPreferencesDto.customPrefs
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to load display preferences from API")
            }

            displayPreferences = DisplayPreferences(
                skipBackLength = customPrefs?.get(Constants.DISPLAY_PREFERENCES_SKIP_BACK_LENGTH)?.toLongOrNull()
                    ?: Constants.DEFAULT_SEEK_TIME_MS,
                skipForwardLength = customPrefs?.get(Constants.DISPLAY_PREFERENCES_SKIP_FORWARD_LENGTH)?.toLongOrNull()
                    ?: Constants.DEFAULT_SEEK_TIME_MS,
            )
        }

        serviceScope.launch {
            try {
                val userConfig = withContext(Dispatchers.IO) {
                    userApi.getCurrentUser().content.configuration
                }
                autoPlayNextEpisodeEnabled = userConfig?.enableNextEpisodeAutoPlay ?: false
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to load auto play preference")
            }
        }
    }

    /**
     * Setup a new [ExoPlayer] for video playback, register callbacks and set attributes
     */
    private fun setupPlayer() {
        val player = ExoPlayer.Builder(this, buildRenderersFactory(), get<MediaSource.Factory>()).apply {
            setUsePlatformDiagnostics(false)
            setTrackSelector(trackSelector)
            setAnalyticsCollector(analyticsCollector)
            setLoadControl(buildLoadControl())
            // Keep the CPU and network available while playing, required for background playback
            setWakeMode(C.WAKE_MODE_NETWORK)
        }.build().apply {
            addListener(this@PlaybackService)
            applyDefaultAudioAttributes(C.AUDIO_CONTENT_TYPE_MOVIE)
        }
        _player.value = player

        val sessionPlayer = QueueNavigationPlayer(player)
        val session = mediaSession
        if (session != null) {
            session.player = sessionPlayer
        } else {
            mediaSession = MediaSession.Builder(this, sessionPlayer)
                .setId(MEDIA_SESSION_ID)
                .setSessionActivity(buildSessionActivityIntent())
                .build()
                // The player UI binds to this service directly instead of connecting a
                // MediaController, so the session has to be registered explicitly for the
                // media notification to be published
                .also(::addSession)
        }
    }

    @Suppress("MagicNumber")
    private fun buildLoadControl() = when (appPreferences.exoPlayerNetworkBuffer) {
        Constants.NETWORK_BUFFER_LARGE -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 120_000, 2_500, 5_000)
            .build()
        Constants.NETWORK_BUFFER_EXTRA_LARGE -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(80_000, 240_000, 5_000, 10_000)
            .build()
        else -> DefaultLoadControl()
    }

    private fun buildRenderersFactory() = DefaultRenderersFactory(this).apply {
        setEnableDecoderFallback(true) // Fallback only works if initialization fails, not decoding at playback time
        val rendererMode = when {
            fallbackPreferExtensionRenderers -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        setExtensionRendererMode(rendererMode)
        setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoderInfoList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
            // Allow decoder selection only for video track
            if (!MimeTypes.isVideo(mimeType)) {
                return@setMediaCodecSelector decoderInfoList
            }
            val filteredDecoderList = when (decoderType.value) {
                DecoderType.HARDWARE -> decoderInfoList.filter(MediaCodecInfo::hardwareAccelerated)
                DecoderType.SOFTWARE -> decoderInfoList.filterNot(MediaCodecInfo::hardwareAccelerated)
                else -> decoderInfoList
            }
            // Update the decoderType based on the first decoder selected
            filteredDecoderList.firstOrNull()?.let { decoder ->
                val decoderType = when {
                    decoder.hardwareAccelerated -> DecoderType.HARDWARE
                    else -> DecoderType.SOFTWARE
                }
                _decoderType.postValue(decoderType)
            }

            filteredDecoderList
        }
    }

    private fun buildSessionActivityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        return PendingIntent.getActivity(this, 0, intent, Constants.PENDING_INTENT_FLAGS)
    }

    /**
     * Release the current [ExoPlayer] and the [MediaSession]
     */
    private fun releasePlayer() {
        // Releasing the session does not release its player
        mediaSession?.let { session ->
            removeSession(session)
            session.release()
        }
        mediaSession = null
        playerOrNull?.run {
            removeListener(this@PlaybackService)
            release()
        }
        _player.value = null
    }

    fun load(jellyfinMediaSource: JellyfinMediaSource, exoMediaSource: MediaSource, playWhenReady: Boolean) {
        val player = playerOrNull ?: return

        player.setMediaSource(exoMediaSource)
        player.prepare()

        initialTracksSelected.set(false)

        val startTime = jellyfinMediaSource.startTime
        if (startTime > Duration.ZERO) player.seekTo(startTime.inWholeMilliseconds)

        applyMediaSegments(jellyfinMediaSource)

        player.playWhenReady = playWhenReady

        if (jellyfinMediaSource is RemoteJellyfinMediaSource) {
            serviceScope.launch {
                player.reportPlaybackStart(jellyfinMediaSource)
            }
        }
    }

    private fun startProgressUpdates() {
        if (mediaSourceOrNull != null && mediaSourceOrNull !is RemoteJellyfinMediaSource) return
        progressUpdateJob = serviceScope.launch {
            while (true) {
                delay(Constants.PLAYER_TIME_UPDATE_RATE)
                playerOrNull?.reportPlaybackState()
            }
        }
    }

    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
    }

    /**
     * Updates the decoder of the player. This will destroy the current player and
     * recreate the player with the selected decoder type
     */
    fun updateDecoderType(type: DecoderType) {
        _decoderType.postValue(type)
        analyticsCollector.release()
        val playedTime = (playerOrNull?.currentPosition ?: 0L).milliseconds
        // Stop and release the player without ending playback
        playerOrNull?.run {
            removeListener(this@PlaybackService)
            release()
        }
        analyticsCollector = buildAnalyticsCollector()
        setupPlayer()
        queueManager.getCurrentMediaSourceOrNull()?.startTime = playedTime
        queueManager.tryRestartPlayback()
    }

    private suspend fun Player.reportPlaybackStart(mediaSource: RemoteJellyfinMediaSource) {
        try {
            val isPaused = !isPlaying
            withContext(Dispatchers.IO) {
                playStateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = mediaSource.itemId,
                        playMethod = mediaSource.playMethod,
                        playSessionId = mediaSource.playSessionId,
                        liveStreamId = mediaSource.liveStreamId,
                        audioStreamIndex = mediaSource.selectedAudioStream?.index,
                        subtitleStreamIndex = mediaSource.selectedSubtitleStream?.index,
                        isPaused = isPaused,
                        isMuted = false,
                        canSeek = true,
                        positionTicks = mediaSource.startTime.inWholeTicks,
                        volumeLevel = audioManager.getVolumeLevelPercent(),
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            }
        } catch (e: ApiClientException) {
            Timber.e(e, "Failed to report playback start")
        }
    }

    private suspend fun Player.reportPlaybackState() {
        val mediaSource = mediaSourceOrNull as? RemoteJellyfinMediaSource ?: return
        val playbackPosition = currentPosition.milliseconds
        if (playbackState == Player.STATE_ENDED) return

        val stream = AudioManager.STREAM_MUSIC
        val volumeRange = audioManager.getVolumeRange(stream)
        val currentVolume = audioManager.getStreamVolume(stream)
        val isPaused = !isPlaying
        try {
            withContext(Dispatchers.IO) {
                playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = mediaSource.itemId,
                        playMethod = mediaSource.playMethod,
                        playSessionId = mediaSource.playSessionId,
                        liveStreamId = mediaSource.liveStreamId,
                        audioStreamIndex = mediaSource.selectedAudioStream?.index,
                        subtitleStreamIndex = mediaSource.selectedSubtitleStream?.index,
                        isPaused = isPaused,
                        isMuted = false,
                        canSeek = true,
                        positionTicks = playbackPosition.inWholeTicks,
                        volumeLevel = (currentVolume - volumeRange.first) * Constants.PERCENT_MAX / volumeRange.width,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            }
        } catch (e: ApiClientException) {
            Timber.e(e, "Failed to report playback progress")
        }
    }

    private fun reportPlaybackStop() {
        val mediaSource = mediaSourceOrNull as? RemoteJellyfinMediaSource ?: return
        val player = playerOrNull ?: return
        val hasFinished = player.playbackState == Player.STATE_ENDED
        val lastPositionTicks = when {
            hasFinished -> mediaSource.runTime.inWholeTicks
            else -> player.currentPosition.milliseconds.inWholeTicks
        }

        // serviceScope may already be cancelled at this point, so we need to fallback
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Report stopped playback
                withContext(Dispatchers.IO) {
                    playStateApi.reportPlaybackStopped(
                        PlaybackStopInfo(
                            itemId = mediaSource.itemId,
                            positionTicks = lastPositionTicks,
                            playSessionId = mediaSource.playSessionId,
                            liveStreamId = mediaSource.liveStreamId,
                            failed = false,
                        ),
                    )
                }

                // Mark video as watched if playback finished
                if (hasFinished) {
                    withContext(Dispatchers.IO) {
                        playStateApi.markPlayedItem(itemId = mediaSource.itemId)
                    }
                }

                // Stop active encoding if transcoding
                stopTranscoding(mediaSource)
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to report playback stop")
            }
        }
    }

    suspend fun stopTranscoding(mediaSource: RemoteJellyfinMediaSource) {
        if (mediaSource.playMethod == PlayMethod.TRANSCODE) {
            withContext(Dispatchers.IO) {
                hlsSegmentApi.stopEncodingProcess(
                    deviceId = apiClient.deviceInfo.id,
                    playSessionId = mediaSource.playSessionId,
                )
            }
        }
    }

    private fun applyMediaSegments(jellyfinMediaSource: JellyfinMediaSource) {
        askToSkipMediaSegments = emptyList()

        serviceScope.launch {
            val item = jellyfinMediaSource.item ?: return@launch
            val mediaSegments = mediaSegmentRepository.getSegmentsForItem(item)
            val newAskToSkipMediaSegments = mutableListOf<MediaSegmentDto>()

            for (mediaSegment in mediaSegments) {
                when (mediaSegmentRepository.getMediaSegmentAction(mediaSegment)) {
                    MediaSegmentAction.SKIP -> addSkipAction(mediaSegment)
                    MediaSegmentAction.ASK_TO_SKIP -> newAskToSkipMediaSegments.add(mediaSegment)
                    MediaSegmentAction.NOTHING -> Unit
                }
            }

            askToSkipMediaSegments = newAskToSkipMediaSegments
        }
    }

    private fun addSkipAction(mediaSegment: MediaSegmentDto) {
        val player = playerOrNull ?: return

        player
            .createMessage { _, _ ->
                serviceScope.launch {
                    player.seekTo(mediaSegment.end.inWholeMilliseconds)
                }
            }
            // Segments at position 0 will never be hit by ExoPlayer so we need to add a minimum value
            .setPosition(mediaSegment.start.inWholeMilliseconds.coerceAtLeast(1))
            .setDeleteAfterDelivery(false)
            .send()
    }

    // Player controls
    fun play() {
        playerOrNull?.play()
    }

    fun pause() {
        playerOrNull?.pause()
    }

    fun rewind() {
        playerOrNull?.seekToOffset(displayPreferences.skipBackLength.unaryMinus())
    }

    fun fastForward() {
        playerOrNull?.seekToOffset(displayPreferences.skipForwardLength)
    }

    fun seekByOffset(offsetMs: Long) {
        playerOrNull?.seekToOffset(offsetMs)
    }

    private fun getCurrentChapterStartPosition(chapters: List<ChapterInfo>, playbackPosition: Duration): Duration? {
        val startPositions = chapters.map { c -> c.startPositionTicks.ticks }
        return startPositions.findLast { pos -> playbackPosition >= pos }
    }

    private fun getNextChapterStartPosition(chapters: List<ChapterInfo>, playbackPosition: Duration): Duration? {
        val startPositions = chapters.map { c -> c.startPositionTicks.ticks }
        val currentChapterIdx = startPositions.indexOfLast { pos -> playbackPosition >= pos }
        if (currentChapterIdx == -1) return null
        val nextChapterIndex = currentChapterIdx + 1
        return startPositions.getOrElse(nextChapterIndex) { _ -> Duration.INFINITE }
    }

    fun previousChapter() {
        val chapters = mediaSourceOrNull?.item?.chapters ?: return
        val currentPosition = playerOrNull?.currentPosition?.milliseconds ?: return

        // Update the playback position to be slightly in the past, to check if we should go back to the beginning of the current
        // chapter or the previous one, if not enough time has elapsed since the start of the current chapter
        val skipToPreviousDuration = Constants.MAX_SKIP_TO_PREV_CHAPTER_MS.milliseconds
        val playbackPosition = currentPosition - skipToPreviousDuration
        // If we'd end up with a negative position then we need to play the previous item
        if (playbackPosition < Duration.ZERO) {
            skipToPrevious()
        } else {
            val seekToPosition = getCurrentChapterStartPosition(chapters, playbackPosition) ?: return
            playerOrNull?.seekTo(seekToPosition.inWholeMilliseconds)
        }
    }

    fun nextChapter() {
        val chapters = mediaSourceOrNull?.item?.chapters ?: return
        val currentPosition = playerOrNull?.currentPosition?.milliseconds ?: return
        val playbackPosition = getNextChapterStartPosition(chapters, currentPosition) ?: return

        if (playbackPosition == Duration.INFINITE) {
            skipToNext()
        } else {
            playerOrNull?.seekTo(playbackPosition.inWholeMilliseconds)
        }
    }

    fun skipToPrevious() {
        val player = playerOrNull ?: return
        when {
            // Skip to previous element
            player.currentPosition <= Constants.MAX_SKIP_TO_PREV_MS -> serviceScope.launch {
                pause()
                if (!queueManager.previous()) {
                    // Skip to previous failed, go to start of video anyway
                    playerOrNull?.seekTo(0)
                    play()
                }
            }
            // Rewind to start of track if not at the start already
            else -> player.seekTo(0)
        }
    }

    fun skipToNext() {
        serviceScope.launch {
            queueManager.next()
        }
    }

    fun skipMediaSegment(mediaSegmentDto: MediaSegmentDto?) {
        val player = playerOrNull ?: return
        val mediaSegment = mediaSegmentDto ?: return
        player.seekTo(mediaSegment.end.inWholeMilliseconds + 1)
    }

    fun getStateAndPause(): PlayState? {
        val player = playerOrNull ?: return null

        val playWhenReady = player.playWhenReady
        player.pause()
        val position = player.contentPosition.milliseconds

        return PlayState(playWhenReady, position)
    }

    fun logTracks() {
        playerOrNull?.logTracks(analyticsCollector)
    }

    suspend fun changeBitrate(bitrate: Int?): Boolean = queueManager.changeBitrate(bitrate)

    /**
     * Set the playback speed to [speed]
     *
     * @return true if the speed was changed
     */
    fun setPlaybackSpeed(speed: Float): Boolean {
        val player = playerOrNull ?: return false

        val parameters = player.playbackParameters
        if (parameters.speed != speed) {
            player.playbackParameters = parameters.withSpeed(speed)
            return true
        }
        return false
    }

    fun setPressSpeedUp(isPressing: Boolean, speed: Float): Boolean {
        if (!isPressing) {
            return setPlaybackSpeed(playSpeed)
        }
        val player = playerOrNull ?: return false
        val parameters = player.playbackParameters
        playSpeed = parameters.speed
        return setPlaybackSpeed(speed)
    }

    fun stop() {
        pause()
        reportPlaybackStop()
        releasePlayer()
        stopSelf()
    }

    private fun setVolume(percent: Int) {
        if (audioManager.isVolumeFixed) return
        val stream = AudioManager.STREAM_MUSIC
        val volumeRange = audioManager.getVolumeRange(stream)
        val scaled = volumeRange.scaleInRange(percent)
        audioManager.setStreamVolume(stream, scaled, 0)
    }

    fun cancelFallbackRetry() {
        fallbackRetryJob?.cancel()
        fallbackRetryJob = null
    }

    @SuppressLint("SwitchIntDef")
    override fun onPlaybackStateChanged(playbackState: Int) {
        val player = playerOrNull ?: return

        // Notify the UI of the current state
        _playerState.value = playbackState

        if (playbackState == Player.STATE_READY && !initialTracksSelected.getAndSet(true)) {
            trackSelectionHelper.selectInitialTracks()
        }

        // Force update playback state and position
        serviceScope.launch {
            when (playbackState) {
                Player.STATE_READY, Player.STATE_BUFFERING -> player.reportPlaybackState()
                Player.STATE_ENDED -> {
                    reportPlaybackStop()
                    if (!autoPlayNextEpisodeEnabled || !queueManager.next()) {
                        releasePlayer()
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        onPlaybackChanged()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        onPlaybackChanged()
    }

    private fun onPlaybackChanged() {
        val player = playerOrNull ?: return

        // Republish the state so the UI can react to pause and resume as well
        _playerState.value = player.playbackState

        stopProgressUpdates()
        if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
            startProgressUpdates()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (error.cause is MediaCodecDecoderException && !fallbackPreferExtensionRenderers) {
            Timber.e(error.cause, "Decoder failed, attempting to restart playback with decoder extensions preferred")
            playerOrNull?.run {
                removeListener(this@PlaybackService)
                release()
            }
            fallbackPreferExtensionRenderers = true
            setupPlayer()
            queueManager.tryRestartPlayback()
        } else {
            Timber.w(error, "Playback error, attempting fallback")
            val startPosition = (playerOrNull?.currentPosition ?: 0L).milliseconds
            fallbackRetryJob?.cancel()
            fallbackRetryJob = serviceScope.launch {
                val retried = queueManager.restartPlaybackWithFallback(startPosition)
                if (!retried) {
                    _error.postValue(error.localizedMessage.orEmpty())
                }
            }
        }
    }

    /**
     * Exposes the queue as previous and next actions for the media session, which only ever holds
     * the item that is currently playing.
     */
    private inner class QueueNavigationPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands()
            .buildUpon()
            .addIf(COMMAND_SEEK_TO_PREVIOUS, queueManager.hasPrevious())
            .addIf(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, queueManager.hasPrevious())
            .addIf(COMMAND_SEEK_TO_NEXT, queueManager.hasNext())
            .addIf(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, queueManager.hasNext())
            .build()

        override fun hasPreviousMediaItem(): Boolean = queueManager.hasPrevious()

        override fun hasNextMediaItem(): Boolean = queueManager.hasNext()

        override fun seekToPrevious() = skipToPrevious()

        override fun seekToPreviousMediaItem() = skipToPrevious()

        override fun seekToNext() = skipToNext()

        override fun seekToNextMediaItem() = skipToNext()
    }

    companion object {
        private const val ACTION_BIND_LOCAL = "org.jellyfin.mobile.player.BIND_LOCAL"
        private const val MEDIA_SESSION_ID = "PlaybackService"

        fun createLocalBindIntent(context: Context): Intent =
            Intent(context, PlaybackService::class.java).apply { action = ACTION_BIND_LOCAL }
    }
}
