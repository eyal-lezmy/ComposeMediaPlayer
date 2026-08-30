package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.kdroid.androidcontextprovider.ContextProvider
import io.github.kdroidfilter.composemediaplayer.util.buildLocalLogger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.*

@OptIn(UnstableApi::class)
actual fun createVideoPlayerState(): VideoPlayerState =
    try {
        DefaultVideoPlayerState()
    } catch (e: IllegalStateException) {
        PreviewableVideoPlayerState(
            hasMedia = false,
            isPlaying = false,
            isLoading = false,
            volume = 1f,
            sliderPos = 0f,
            userDragging = false,
            loop = false,
            playbackSpeed = 1f,
            leftLevel = 0f,
            rightLevel = 0f,
            positionText = "00:00",
            durationText = "00:00",
            currentTime = 0.0,
            isFullscreen = false,
            aspectRatio = 16f / 9f,
            error = VideoPlayerError.UnknownError(
                "Android context is not available (preview or missing ContextProvider initialization)."
            ),
            metadata = VideoMetadata(),
            subtitlesEnabled = false,
            currentSubtitleTrack = null,
            availableSubtitleTracks = mutableListOf(),
            subtitleTextStyle = TextStyle.Default,
            subtitleBackgroundColor = Color.Transparent
        )
    }

/**
 * Logger for WebAssembly video player surface
 */
internal val androidVideoLogger = buildLocalLogger("AndroidVideoPlayerSurface")

@UnstableApi
@Stable
open class DefaultVideoPlayerState: VideoPlayerState {
    private val context: Context = ContextProvider.getContext()
    internal var exoPlayer: ExoPlayer? = null

    /**
     * The backend's own [Player], so a host app can hand it to a media3 `MediaSession` instead of
     * writing a `Player` adapter over this state. Null before the player is built and after it is
     * released.
     *
     * Deliberately not a member of the common [VideoPlayerState] interface: `Player` is an Android
     * type, and the six other backends have nothing to return. Read it from `androidMain`, where
     * this concrete class is visible.
     */
    val player: Player? get() = exoPlayer
    private var updateJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val audioProcessor = AudioLevelProcessor()

    // Protection contre les race conditions
    private var isPlayerReleased = false
    private val playerInitializationLock = Any()
    private var playerListener: Player.Listener? = null

    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean get() = _hasMedia

    // State properties
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean get() = _isPlaying

    private var _isLoading by mutableStateOf(false)
    override val isLoading: Boolean get() = _isLoading

    private var _error by mutableStateOf<VideoPlayerError?>(null)
    override val error: VideoPlayerError? get() = _error

    private var _metadata = VideoMetadata()
    override val metadata: VideoMetadata get() = _metadata

    // --- Tracks ---
    //
    // Both lists are read back from ExoPlayer's own `currentTracks` rather than remembered from what
    // was asked for: the extractor is the only thing that knows what the media really carries, and
    // on an HLS feed the renditions appear after playback has started (see [refreshTracks]).
    //
    // A track's handle is its position in `currentTracks` — "<group>:<index>" — because Media3
    // selects by `TrackSelectionOverride(mediaTrackGroup, index)` and nothing else identifies a
    // group across calls. The pair is only ever resolved against the very list the UI was handed,
    // which [refreshTracks] republishes on every `onTracksChanged`, so it cannot go stale unnoticed.

    private var _audioTracks by mutableStateOf<List<AudioTrack>>(emptyList())
    override val availableAudioTracks: List<AudioTrack> get() = _audioTracks
    override var currentAudioTrack by mutableStateOf<AudioTrack?>(null)

    // Subtitle state
    override var subtitlesEnabled by mutableStateOf(false)
    override var currentSubtitleTrack by mutableStateOf<SubtitleTrack?>(null)
    // Observable list: the track panel is recomposed by it appearing, seconds after the media opened.
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableStateListOf()
    override var subtitleTextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    )

    override var subtitleBackgroundColor by mutableStateOf(Color.Black.copy(alpha = 0.5f))

    private var playerView: PlayerView? = null

    override fun selectAudioTrack(track: AudioTrack?) {
        val override = track?.let { trackOverride(C.TRACK_TYPE_AUDIO, it.id) } ?: return
        currentAudioTrack = track
        applyOverride(C.TRACK_TYPE_AUDIO, override)
    }

    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        if (track == null) {
            disableSubtitles()
            return
        }
        val override = trackOverride(C.TRACK_TYPE_TEXT, track.src) ?: return

        currentSubtitleTrack = track
        subtitlesEnabled = true

        // The type has to be re-enabled as well as overridden: disableSubtitles() left it disabled,
        // and an override on a disabled track type selects nothing.
        applyOverride(C.TRACK_TYPE_TEXT, override, disabled = false)
        playerView?.subtitleView?.visibility = android.view.View.VISIBLE
    }

    override fun disableSubtitles() {
        currentSubtitleTrack = null
        subtitlesEnabled = false

        exoPlayer?.let { player ->
            val parameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(null)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            player.trackSelectionParameters = parameters

            playerView?.subtitleView?.visibility = android.view.View.GONE
        }
    }

    /**
     * Resolves a handle produced by [refreshTracks] back into the Media3 override that selects it,
     * or null when the media changed under it — a stale handle must be a no-op, never a crash.
     */
    private fun trackOverride(trackType: Int, handle: String): TrackSelectionOverride? {
        val player = exoPlayer ?: return null
        if (isPlayerReleased) return null
        val (groupIndex, trackIndex) = handle.split(':')
            .takeIf { it.size == 2 }
            ?.let { (group, track) -> (group.toIntOrNull() ?: return null) to (track.toIntOrNull() ?: return null) }
            ?: return null
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return null
        if (group.type != trackType || trackIndex !in 0 until group.length) return null
        return TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
    }

    private fun applyOverride(trackType: Int, override: TrackSelectionOverride, disabled: Boolean = false) {
        val player = exoPlayer ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, disabled)
            .setOverrideForType(override)
            .build()
    }

    /**
     * Re-reads both track lists from ExoPlayer. Called on every `onTracksChanged`, which is the only
     * moment the extractor has anything to say: the lists are empty until the media is prepared, and
     * HLS renditions keep arriving after playback has started.
     *
     * `isSelected` is read back rather than remembered, so a track the player picked by itself —
     * which is every track before the user touches the panel — is the one the UI shows as current.
     */
    private fun refreshTracks() {
        val player = exoPlayer ?: return
        if (isPlayerReleased) return

        val audio = mutableListOf<AudioTrack>()
        var selectedAudio: AudioTrack? = null
        val subtitles = mutableListOf<SubtitleTrack>()
        var selectedSubtitle: SubtitleTrack? = null

        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val handle = "$groupIndex:$trackIndex"
                val language = format.language.orEmpty()
                val label = format.label ?: language
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> {
                        val track = AudioTrack(label = label, language = language, id = handle)
                        audio += track
                        if (group.isTrackSelected(trackIndex)) selectedAudio = track
                    }

                    C.TRACK_TYPE_TEXT -> {
                        val track = SubtitleTrack(label = label, language = language, src = handle)
                        subtitles += track
                        if (group.isTrackSelected(trackIndex)) selectedSubtitle = track
                    }
                }
            }
        }

        _audioTracks = audio
        currentAudioTrack = selectedAudio
        availableSubtitleTracks.clear()
        availableSubtitleTracks.addAll(subtitles)
        currentSubtitleTrack = selectedSubtitle
        subtitlesEnabled = selectedSubtitle != null
    }

    private fun clearTracks() {
        _audioTracks = emptyList()
        currentAudioTrack = null
        availableSubtitleTracks.clear()
        currentSubtitleTrack = null
        subtitlesEnabled = false
    }

    internal fun attachPlayerView(view: PlayerView?) {
        if (view == null) {
            // Détacher la vue actuelle
            playerView?.player = null
            playerView = null
            return
        }

        playerView = view
        exoPlayer?.let { player ->
            try {
                view.player = player
                view.subtitleView?.setStyle(CaptionStyleCompat.DEFAULT)
            } catch (e: Exception) {
                androidVideoLogger.e { "Error attaching player to view: ${e.message}" }
            }
        }
    }

    // Volume control
    private var _volume by mutableFloatStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f, 1f)
            exoPlayer?.volume = _volume
        }

    // Slider position
    private var _sliderPos by mutableFloatStateOf(0f)
    override var sliderPos: Float
        get() = _sliderPos
        set(value) {
            _sliderPos = value.coerceIn(0f, 1000f)
            if (!userDragging) {
                seekTo(value)
            }
        }

    // User interaction states
    override var userDragging by mutableStateOf(false)

    // Loop control
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
            exoPlayer?.repeatMode = if (value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        }

    // Playback speed control
    private var _playbackSpeed by mutableFloatStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            _playbackSpeed = value.coerceIn(0.5f, 2.0f)
            exoPlayer?.let { player ->
                player.playbackParameters = PlaybackParameters(_playbackSpeed)
            }
        }

    // Audio levels
    private var _leftLevel by mutableFloatStateOf(0f)
    private var _rightLevel by mutableFloatStateOf(0f)
    override val leftLevel: Float get() = _leftLevel
    override val rightLevel: Float get() = _rightLevel

    // Aspect ratio
    private var _aspectRatio by mutableFloatStateOf(16f / 9f)
    override val aspectRatio: Float get() = _aspectRatio

    // Fullscreen state
    private var _isFullscreen by mutableStateOf(false)
    override var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            _isFullscreen = value
        }

    // Time tracking
    private var _currentTime by mutableDoubleStateOf(0.0)
    private var _duration by mutableDoubleStateOf(0.0)
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)
    override val currentTime: Double get() = _currentTime


    init {
        audioProcessor.setOnAudioLevelUpdateListener { left, right ->
            _leftLevel = left
            _rightLevel = right
        }
        initializePlayer()
    }

    private fun shouldUseConservativeCodecHandling(): Boolean {
        val device = android.os.Build.DEVICE
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL

        // Liste des appareils connus pour avoir des problèmes MediaCodec
        val problematicDevices = setOf(
            "SM-A155F", // Galaxy A15
            "SM-A156B", // Galaxy A15 5G
            // Ajouter d'autres modèles problématiques ici
        )

        return device in problematicDevices ||
                model in problematicDevices ||
                manufacturer.equals("mediatek", ignoreCase = true)
    }

    private fun initializePlayer() {
        synchronized(playerInitializationLock) {
            if (isPlayerReleased) return

            val audioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(audioProcessor))
                .build()

            val renderersFactory = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink = audioSink
            }.apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                // Activer le fallback du décodeur pour une meilleure stabilité
                setEnableDecoderFallback(true)

                // Sur les appareils problématiques, utiliser des paramètres plus conservateurs
                if (shouldUseConservativeCodecHandling()) {
                    // On ne peut pas désactiver l'async queueing car la méthode n'existe pas
                    // Mais on peut utiliser le MediaCodecSelector par défaut
                    setMediaCodecSelector(MediaCodecSelector.DEFAULT)
                }
            }

            exoPlayer = ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setHandleAudioBecomingNoisy(true)
                // Audio focus is handled by the player, not by the host app: an incoming call, a
                // navigation prompt or another media app ducks or pauses us and playback comes back
                // afterwards. Mandatory once playback can continue with the screen off — otherwise
                // the stream talks over the call.
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                // NETWORK rather than LOCAL: a background stream also needs the WifiLock, or the
                // radio idles the socket out from under it once the screen is off.
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setPauseAtEndOfMediaItems(false)
                .setReleaseTimeoutMs(2000) // Augmenter le timeout de libération
                .build()
                .apply {
                    playerListener = createPlayerListener()
                    addListener(playerListener!!)
                    volume = _volume
                }
        }
    }

    private fun createPlayerListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // Ajouter une vérification de sécurité
            if (isPlayerReleased) return

            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _isLoading = true
                }

                Player.STATE_READY -> {
                    _isLoading = false
                    exoPlayer?.let { player ->
                        if (!isPlayerReleased) {
                            _duration = player.duration.toDouble() / 1000.0
                            _isPlaying = player.isPlaying
                            if (player.isPlaying) startPositionUpdates()
                            extractFormatMetadata(player)
                        }
                    }
                }

                Player.STATE_ENDED -> {
                    _isLoading = false
                    stopPositionUpdates()
                    _isPlaying = false
                }

                Player.STATE_IDLE -> {
                    _isLoading = false
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (isPlayerReleased) return
            refreshTracks()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            if (!isPlayerReleased) {
                _isPlaying = playing
                if (playing) {
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _aspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                _metadata.width = videoSize.width
                _metadata.height = videoSize.height
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            androidVideoLogger.e { "Player error occurred: ${error.errorCode} - ${error.message}" }

            // Créer un rapport d'erreur détaillé
            val errorDetails = mapOf(
                "error_code" to error.errorCode.toString(),
                "error_message" to (error.message ?: "Unknown"),
                "device" to android.os.Build.DEVICE,
                "model" to android.os.Build.MODEL,
                "manufacturer" to android.os.Build.MANUFACTURER,
                "android_version" to android.os.Build.VERSION.SDK_INT.toString(),
                "codec_info" to error.cause?.message
            )

            // Log the error details (you can send this to your crash reporting service)
            androidVideoLogger.e { "Detailed error info: $errorDetails" }

            // Gestion des erreurs spécifiques au codec
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> {
                    _error = VideoPlayerError.CodecError("Decoder error: ${error.message}")
                    // Tenter une récupération pour les erreurs de codec
                    attemptPlayerRecovery()
                }
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                    _error = VideoPlayerError.NetworkError("Network error: ${error.message}")
                }
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                    _error = VideoPlayerError.SourceError("Invalid media source: ${error.message}")
                }
                else -> {
                    _error = VideoPlayerError.UnknownError("Playback error: ${error.message}")
                }
            }
            _isPlaying = false
            _isLoading = false
        }
    }

    private fun attemptPlayerRecovery() {
        coroutineScope.launch {
            delay(100) // Petit délai pour laisser le système nettoyer

            synchronized(playerInitializationLock) {
                if (!isPlayerReleased) {
                    exoPlayer?.let { player ->
                        val currentPosition = player.currentPosition
                        val currentMediaItem = player.currentMediaItem
                        val wasPlaying = player.isPlaying

                        try {
                            // Retirer le listener avant de libérer
                            playerListener?.let { player.removeListener(it) }

                            // Libérer le lecteur actuel
                            player.release()

                            // Réinitialiser
                            initializePlayer()

                            // Restaurer l'élément média et la position
                            currentMediaItem?.let {
                                exoPlayer?.apply {
                                    setMediaItem(it)
                                    prepare()
                                    seekTo(currentPosition)
                                    // Restaurer l'état de lecture si nécessaire
                                    if (wasPlaying) {
                                        play()
                                    } else {
                                        pause()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            androidVideoLogger.e { "Error during player recovery: ${e.message}" }
                            _error = VideoPlayerError.UnknownError("Recovery failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        updateJob = coroutineScope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    if (player.playbackState == Player.STATE_READY && !isPlayerReleased) {
                        _currentTime = player.currentPosition.toDouble() / 1000.0
                        if (!userDragging && _duration > 0) {
                            _sliderPos = (_currentTime / _duration * 1000).toFloat()
                        }
                    }
                }
                delay(16) // ~60fps update rate
            }
        }
    }

    private fun stopPositionUpdates() {
        updateJob?.cancel()
        updateJob = null
    }

    override fun openUri(uri: String, initializeplayerState: InitialPlayerState) {
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        val mediaItem = mediaItemBuilder.build()
        openFromMediaItem(mediaItem, initializeplayerState)
    }

    override fun openFile(file: PlatformFile, initializeplayerState: InitialPlayerState) {
        val mediaItemBuilder = MediaItem.Builder()
        val videoUri: Uri = when (val androidFile = file.androidFile) {
            is AndroidFile.UriWrapper -> androidFile.uri
            is AndroidFile.FileWrapper -> Uri.fromFile(androidFile.file)
        }
        mediaItemBuilder.setUri(videoUri)
        val mediaItem = mediaItemBuilder.build()
        openFromMediaItem(mediaItem, initializeplayerState)
    }

    private fun openFromMediaItem(mediaItem: MediaItem, initializeplayerState: InitialPlayerState) {
        synchronized(playerInitializationLock) {
            if (isPlayerReleased) return

            exoPlayer?.let { player ->
                player.stop()
                player.clearMediaItems()
                try {
                    _error = null
                    resetStates(keepMedia = true)

                    // Extraire les métadonnées avant de préparer le lecteur
                    extractMediaItemMetadata(mediaItem)

                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.volume = volume
                    player.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

                    // Contrôler l'état de lecture initial
                    if (initializeplayerState == InitialPlayerState.PLAY) {
                        player.play()
                        _hasMedia = true
                    } else {
                        player.pause()
                        _isPlaying = false
                        _hasMedia = true
                    }
                } catch (e: Exception) {
                    androidVideoLogger.d { "Error opening media: ${e.message}" }
                    _isPlaying = false
                    _hasMedia = false
                    _error = VideoPlayerError.SourceError("Failed to load media: ${e.message}")
                }
            }
        }
    }

    override fun play() {
        synchronized(playerInitializationLock) {
            if (!isPlayerReleased) {
                exoPlayer?.let { player ->
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    }
                    player.play()
                }
                _hasMedia = true
            }
        }
    }

    override fun pause() {
        synchronized(playerInitializationLock) {
            if (!isPlayerReleased) {
                exoPlayer?.pause()
            }
        }
    }

    override fun stop() {
        synchronized(playerInitializationLock) {
            if (!isPlayerReleased) {
                exoPlayer?.let { player ->
                    player.stop()
                    player.seekTo(0)
                }
                _hasMedia = false
                resetStates(keepMedia = true)
            }
        }
    }

    override fun seekTo(value: Float) {
        if (_duration > 0 && !isPlayerReleased) {
            val targetTime = (value / 1000.0) * _duration
            exoPlayer?.seekTo((targetTime * 1000).toLong())
        }
    }

    override fun clearError() {
        _error = null
    }

    override fun toggleFullscreen() {
        _isFullscreen = !_isFullscreen
    }

    private fun extractFormatMetadata(player: Player) {
        try {
            if (player.duration > 0 && player.duration != C.TIME_UNSET) {
                _metadata.duration = player.duration
            }

            player.currentTracks.groups.forEach { group ->
                for (i in 0 until group.length) {
                    val trackFormat = group.getTrackFormat(i)

                    when (group.type) {
                        C.TRACK_TYPE_VIDEO -> {
                            if (trackFormat.frameRate > 0) {
                                _metadata.frameRate = trackFormat.frameRate
                            }

                            if (trackFormat.bitrate > 0) {
                                _metadata.bitrate = trackFormat.bitrate.toLong()
                            }

                            trackFormat.sampleMimeType?.let {
                                _metadata.mimeType = it
                            }
                        }

                        C.TRACK_TYPE_AUDIO -> {
                            if (trackFormat.channelCount > 0) {
                                _metadata.audioChannels = trackFormat.channelCount
                            }

                            if (trackFormat.sampleRate > 0) {
                                _metadata.audioSampleRate = trackFormat.sampleRate
                            }
                        }
                    }
                }
            }

            extractMediaItemMetadata(player.currentMediaItem)

            androidVideoLogger.d { "Metadata extracted: $_metadata" }
        } catch (e: Exception) {
            androidVideoLogger.e { "Error extracting format metadata: ${e.message}" }
        }
    }

    private fun extractMediaItemMetadata(mediaItem: MediaItem?) {
        try {
            mediaItem?.mediaMetadata?.let { metadata ->
                metadata.title?.toString()?.let { _metadata.title = it }
            }
        } catch (e: Exception) {
            androidVideoLogger.e { "Error extracting media item metadata: ${e.message}" }
        }
    }

    private fun resetStates(keepMedia: Boolean = false) {
        _currentTime = 0.0
        _duration = 0.0
        _sliderPos = 0f
        _leftLevel = 0f
        _rightLevel = 0f
        _isPlaying = false
        _isLoading = false
        _error = null
        _aspectRatio = 16f / 9f
        _playbackSpeed = 1.0f
        _metadata = VideoMetadata()
        // The next media's tracks are a different set, and onTracksChanged is what publishes them —
        // leaving the previous title's list up would offer renditions that no longer exist.
        clearTracks()
        exoPlayer?.playbackParameters = PlaybackParameters(_playbackSpeed)
        if (!keepMedia) {
            _hasMedia = false
        }
    }

    override fun dispose() {
        synchronized(playerInitializationLock) {
            isPlayerReleased = true
            stopPositionUpdates()
            coroutineScope.cancel()
            playerView?.player = null
            playerView = null

            try {
                exoPlayer?.let { player ->
                    // Retirer le listener spécifiquement
                    playerListener?.let { listener ->
                        player.removeListener(listener)
                    }
                    player.stop()
                    player.clearMediaItems()
                    player.release()
                }
            } catch (e: Exception) {
                androidVideoLogger.e { "Error during player disposal: ${e.message}" }
            }

            playerListener = null
            exoPlayer = null
            resetStates()
        }
    }
}
