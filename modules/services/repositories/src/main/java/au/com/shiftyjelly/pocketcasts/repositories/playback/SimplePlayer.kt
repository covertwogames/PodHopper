package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.WearUnsuitableOutputPlaybackSuppressionResolverListener
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.to.PlaybackEffects
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.stats.PlaybackStatsCollector
import au.com.shiftyjelly.pocketcasts.repositories.user.StatsManager
import au.com.shiftyjelly.pocketcasts.utils.Util
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

// PodHopper: how many times a 416 cache-race error may restart playback for one episode
// before the error is surfaced normally. Two attempts covers the race window reopening
// once after a reset without letting a broken stream loop.
private const val MAX_CACHE_RECOVERY_ATTEMPTS = 2

class SimplePlayer(
    private val settings: Settings,
    private val statsManager: StatsManager,
    private val playbackStatsCollector: PlaybackStatsCollector,
    private val context: Context,
    private val dataSourceFactory: ExoPlayerDataSourceFactory,
    override val onPlayerEvent: (au.com.shiftyjelly.pocketcasts.repositories.playback.Player, PlayerEvent) -> Unit,
) : LocalPlayer(onPlayerEvent) {
    private val reducedBufferManufacturers = listOf("mercedes-benz")
    private val useReducedBuffer = reducedBufferManufacturers.contains(Build.MANUFACTURER.lowercase()) || Util.isWearOs(context)
    private val bufferTimeMinMillis = TimeUnit.MINUTES.toMillis(2).toInt()
    private val bufferTimeMaxMillis = if (useReducedBuffer) TimeUnit.MINUTES.toMillis(2).toInt() else TimeUnit.MINUTES.toMillis(4).toInt()

    // Be careful increasing the size of the back buffer. It can easily lead to OOM errors.
    private val backBufferTimeMillis = if (useReducedBuffer) TimeUnit.SECONDS.toMillis(30).toInt() else TimeUnit.SECONDS.toMillis(50).toInt()

    private var player: ExoPlayer? = null

    private var renderersFactory: ShiftyRenderersFactory? = null
    private var playbackEffects: PlaybackEffects? = null

    var videoWidth: Int = 0
    var videoHeight: Int = 0

    override var isPip: Boolean = false

    private var videoChangedListener: VideoChangedListener? = null

    @Volatile
    private var prepared = false

    // PodHopper: bounded restart-after-416 recovery. The 416 comes from the known cache race
    // (the CacheWorker writing an episode into the same SimpleCache the player streams from,
    // see the upstream issue links in onPlayerError). Upstream's handler resets the cache but
    // leaves the ExoPlayer dead in its error state, which on Android Automotive tears down the
    // Now Playing screen and leaves the play button unresponsive. These fields bound how many
    // times we restart playback per episode before giving up and surfacing a normal error.
    private var cacheRecoveryAttempts = 0
    private var cacheRecoveryEpisodeUuid: String? = null

    val exoPlayer: ExoPlayer?
        get() {
            return player
        }

    override suspend fun bufferedUpToMs(): Int {
        return withContext(Dispatchers.Main) {
            player?.bufferedPosition?.toInt() ?: 0
        }
    }

    override suspend fun bufferedPercentage(): Int {
        return withContext(Dispatchers.Main) {
            player?.bufferedPercentage ?: 0
        }
    }

    override suspend fun durationMs(): Int? {
        return withContext(Dispatchers.Main) {
            val duration = player?.duration ?: C.TIME_UNSET
            if (duration == C.TIME_UNSET) null else duration.toInt()
        }
    }

    override suspend fun isPlaying(): Boolean {
        return withContext(Dispatchers.Main) {
            player?.playWhenReady ?: false
        }
    }

    override fun handleCurrentPositionMs(): Int {
        return player?.currentPosition?.toInt() ?: -1
    }

    override fun handlePrepare() {
        // PodHopper: "prepared" alone is not proof of a usable player. A fatal stream error
        // leaves the ExoPlayer in an error state with this flag still true, so every play
        // command was silently ignored until something happened to rebuild the player. Treat
        // a null or errored ExoPlayer as not prepared and rebuild it; prepare() releases any
        // previous instance itself, so calling it here is safe.
        val current = player
        if (prepared && current != null && current.playerError == null) {
            return
        }
        if (prepared) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Player was marked prepared but is unusable (released or errored), rebuilding")
        }
        prepare()
    }

    @OptIn(UnstableApi::class)
    override fun handleStop() {
        try {
            player?.stop()
        } catch (e: Exception) {
            LogBuffer.e(LogBuffer.TAG_PLAYBACK, e, "Play failed to stop.")
        }

        try {
            player?.release()
        } catch (e: Exception) {
            LogBuffer.e(LogBuffer.TAG_PLAYBACK, e, "Play failed to release.")
        }

        player = null
        prepared = false

        videoChangedListener?.videoNeedsReset()
    }

    override fun handlePause() {
        player?.playWhenReady = false
    }

    override fun handlePlay() {
        player?.playWhenReady = true
    }

    override fun handleSeekToTimeMs(positionMs: Int) {
        if (player?.isCurrentMediaItemSeekable == false && player?.isPlaying == true) {
            Toast.makeText(context, "Unable to seek. File headers appear to be invalid.", Toast.LENGTH_SHORT).show()
        } else {
            player?.seekTo(positionMs.toLong())
            super.onSeekComplete(positionMs)
        }
    }

    override fun handleIsBuffering(): Boolean {
        return (player?.playbackState ?: Player.STATE_ENDED) == Player.STATE_BUFFERING
    }

    override fun handleIsPrepared(): Boolean {
        return prepared
    }

    override fun supportsTrimSilence(): Boolean {
        return true
    }

    override fun supportsVolumeBoost(): Boolean {
        return true
    }

    override suspend fun setPlaybackEffects(playbackEffects: PlaybackEffects) {
        withContext(Dispatchers.Main) {
            this@SimplePlayer.playbackEffects = playbackEffects
            setPlayerEffects()
        }
    }

    override fun supportsVideo(): Boolean {
        return true
    }

    override fun setVolume(volume: Float) {
        player?.volume = volume
    }

    fun getVolume(): Float? {
        return player?.volume
    }

    /**
     * 1.0 represents the maximum/default volume,
     * while 0.0 represents complete silence
     */
    fun restoreVolume() {
        player?.volume = 1.0f
    }

    override fun setPodcast(podcast: Podcast?) {}

    @OptIn(UnstableApi::class)
    private fun prepare() {
        val trackSelector = DefaultTrackSelector(context)

        val minBufferMillis = if (isStreaming) bufferTimeMinMillis else DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
        val maxBufferMillis = if (isStreaming) bufferTimeMaxMillis else DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
        val backBufferMillis = if (isStreaming) backBufferTimeMillis else DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMillis,
                maxBufferMillis,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setBackBuffer(backBufferMillis, DefaultLoadControl.DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME)
            .build()

        val renderer = createRenderersFactory()
        this.renderersFactory = renderer
        val player = ExoPlayer.Builder(context, renderer)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setReleaseTimeoutMs(settings.getPlayerReleaseTimeOutMs())
            .setSeekForwardIncrementMs(settings.skipForwardInSecs.value * 1000L)
            .setSeekBackIncrementMs(settings.skipBackInSecs.value * 1000L)
            .build()
        player.addListener(WearUnsuitableOutputPlaybackSuppressionResolverListener(context))
        player.addListener(PlayPauseListener(playbackStatsCollector))
        player.addAnalyticsListener(renderer)

        handleStop()
        this.player = player

        setPlayerEffects()
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                episodeUuid?.let { onEpisodeChanged(it) }
                val episodeMetadata = EpisodeFileMetadata(filenamePrefix = episodeUuid)
                episodeMetadata.read(tracks, settings.artworkConfiguration.value.useEpisodeArtwork, context)
                onMetadataAvailable(episodeMetadata)
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                onBufferingStateChanged()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        onBufferingStateChanged()
                        onDurationAvailable()
                    }

                    Player.STATE_BUFFERING -> onBufferingStateChanged()

                    Player.STATE_ENDED -> onCompletion()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Reset episode caching if 416 error response code is received
                // https://github.com/androidx/media/issues/1032#issuecomment-1921375048
                // https://github.com/google/ExoPlayer/issues/10577
                // Internal ref: p1730809737477079-slack-C02A333D8LQ
                if ((error.cause as? InvalidResponseCodeException)?.responseCode == 416) {
                    val location = episodeLocation
                    if (location != null) {
                        // PodHopper: track recovery attempts per episode so a genuinely broken
                        // stream cannot restart forever. A new episode resets the budget.
                        if (episodeUuid != cacheRecoveryEpisodeUuid) {
                            cacheRecoveryEpisodeUuid = episodeUuid
                            cacheRecoveryAttempts = 0
                        }
                        cacheRecoveryAttempts += 1

                        // Position and play state must be captured before the reset tears the
                        // player down, so the restart resumes where the error interrupted. NOTE:
                        // "player" in this listener is prepare()'s local, the exact ExoPlayer
                        // instance that errored, which is what we want to capture from.
                        val erroredPlayer = player
                        val resumePositionMs = erroredPlayer.currentPosition.toInt().coerceAtLeast(0)
                        val resumePlayWhenReady = erroredPlayer.playWhenReady

                        dataSourceFactory.resetEpisodeCaching(
                            episodeLocation = location,
                            onCachingReset = { episodeUuid -> onPlayerEvent(this@SimplePlayer, PlayerEvent.CachingReset(episodeUuid)) },
                            onCachingComplete = { episodeUuid -> onPlayerEvent(this@SimplePlayer, PlayerEvent.CachingComplete(episodeUuid)) },
                        )

                        if (cacheRecoveryAttempts <= MAX_CACHE_RECOVERY_ATTEMPTS) {
                            // PodHopper: upstream stopped here, leaving a dead player behind:
                            // the session showed no content and play commands were swallowed.
                            // Rebuild the player and resume so the 416 cache race costs a blip
                            // instead of killing playback. handleSeekToTimeMs routes through
                            // LocalPlayer's seek bookkeeping so the parent position stays true.
                            LogBuffer.i(
                                LogBuffer.TAG_PLAYBACK,
                                "416 cache race: restarting playback at %.3f (attempt %d of %d)",
                                resumePositionMs / 1000f,
                                cacheRecoveryAttempts,
                                MAX_CACHE_RECOVERY_ATTEMPTS,
                            )
                            handleStop()
                            prepare()
                            handleSeekToTimeMs(resumePositionMs)
                            // The class field now holds the rebuilt player; the closure's
                            // "player" is still the old released instance, so target the field.
                            this@SimplePlayer.player?.playWhenReady = resumePlayWhenReady
                            return
                        }
                        LogBuffer.e(LogBuffer.TAG_PLAYBACK, "416 cache race: recovery budget exhausted for episode $episodeUuid, surfacing error")
                        // Fall through to the normal error path below.
                    } else {
                        // No episode location means nothing to reset or restart; fall through.
                        LogBuffer.e(LogBuffer.TAG_PLAYBACK, "416 received with no episode location, surfacing error")
                    }
                }
                LogBuffer.e(LogBuffer.TAG_PLAYBACK, error, "Play failed.")
                val event = PlayerEvent.PlayerError(error.message ?: "", error)
                this@SimplePlayer.onError(event)
            }
        })

        addVideoListener(player)

        val episodeLocation = episodeLocation
        if (episodeLocation == null) {
            onError(PlayerEvent.PlayerError("No episode location found"))
            return
        }
        val source = dataSourceFactory.createMediaSource(episodeLocation) {
            onPlayerEvent(this, PlayerEvent.CachingComplete(it))
        }
        if (source == null) {
            onError(PlayerEvent.PlayerError("Episode has no source"))
            return
        }

        player.setMediaSource(source)
        player.prepare()

        prepared = true
    }

    private fun addVideoListener(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height

                videoChangedListener?.let {
                    Handler(Looper.getMainLooper()).post { it.videoSizeChanged(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio) }
                }
            }
        })
    }

    private fun createRenderersFactory(): ShiftyRenderersFactory {
        val playbackEffects: PlaybackEffects? = this.playbackEffects
        return if (playbackEffects == null) {
            ShiftyRenderersFactory(context = context, statsManager = statsManager, boostVolume = false)
        } else {
            ShiftyRenderersFactory(context = context, statsManager = statsManager, boostVolume = playbackEffects.isVolumeBoosted)
        }
    }

    fun setDisplay(surfaceView: SurfaceView?): Boolean {
        val player = player ?: return false

        return try {
            player.setVideoSurfaceHolder(surfaceView?.holder)
            true
        } catch (e: Exception) {
            Timber.e(e)
            false
        }
    }

    interface VideoChangedListener {
        fun videoSizeChanged(width: Int, height: Int, pixelWidthHeightRatio: Float)
        fun videoNeedsReset()
    }

    fun setVideoSizeChangedListener(videoChangedListener: VideoChangedListener) {
        this.videoChangedListener = videoChangedListener
        if (videoWidth != 0 && videoHeight != 0) {
            videoChangedListener.videoSizeChanged(videoWidth, videoHeight, 1f)
        }
    }

    private fun setPlayerEffects() {
        val player = player
        val playbackEffects = playbackEffects
        if (player == null || playbackEffects == null) return // nothing to set

        renderersFactory?.let {
            it.setPlaybackSpeed(playbackEffects.playbackSpeed.toFloat())
            it.setRemoveSilence(playbackEffects.trimMode)
            it.setBoostVolume(playbackEffects.isVolumeBoosted)
        }
        player.playbackParameters = PlaybackParameters(playbackEffects.playbackSpeed.toFloat(), 1f)
    }
}

private class PlayPauseListener(
    private val statsCollector: PlaybackStatsCollector,
) : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
        if (player.isPlaying) {
            statsCollector.onStart()
        } else {
            statsCollector.onStop()
        }
    }
}
