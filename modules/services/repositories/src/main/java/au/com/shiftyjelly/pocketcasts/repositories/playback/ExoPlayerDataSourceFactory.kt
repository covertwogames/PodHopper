package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.servers.di.Player
import au.com.shiftyjelly.pocketcasts.utils.Util
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.automattic.android.tracks.crashlogging.CrashLogging
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import timber.log.Timber

@Singleton
@OptIn(UnstableApi::class)
class ExoPlayerDataSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    @Player private val httpClient: Lazy<OkHttpClient>,
    private val settings: Settings,
    private val crashLogging: CrashLogging,
) {
    private val cache = runCatching {
        // PodHopper: the streaming cache used to live in a folder named after the upstream project.
        // Deliberately NOT migrated: this is a cache, so the cheapest correct move is to delete the
        // old folder and let the new one refill on demand. Renaming it instead would leave the
        // cache index (held in a separate database) pointing at paths that no longer exist.
        File(context.cacheDir, LEGACY_CACHE_DIR_NAME).takeIf(File::exists)?.deleteRecursively()
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        val size = settings.getExoPlayerCacheEntirePlayingEpisodeSizeInMB() * 1024 * 1024L
        val evictor = LeastRecentlyUsedCacheEvictor(size)
        SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(context))
    }.onFailure { e ->
        val errorMessage = "Failed to instantiate ExoPlayer cache ${e.message}"
        crashLogging.sendReport(Exception(errorMessage))
        LogBuffer.e(LogBuffer.TAG_PLAYBACK, errorMessage)
    }.getOrNull()

    private val defaultFactory = DefaultDataSource.Factory(
        context,
        OkHttpDataSource.Factory { request -> httpClient.get().newCall(request) },
    )

    val cacheFactory get() = CacheDataSource.Factory()
        .setUpstreamDataSourceFactory(defaultFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .let { if (cache != null) it.setCache(cache) else it }

    fun createMediaSource(
        episodeLocation: EpisodeLocation,
        clipRange: ClosedRange<Long>? = null,
        onCachingComplete: (String) -> Unit = {},
    ): MediaSource? {
        val episodeUri = episodeLocation.uri ?: return null
        val mediaItem = MediaItem.Builder()
            .setUri(episodeUri)
            .apply {
                if (episodeLocation.isHlsStream) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                if (clipRange != null) {
                    setClippingConfiguration(clipRange.toClippingConfiguration())
                }
            }
            .setCustomCacheKey(episodeLocation.episode.uuid)
            .build()

        val extractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        if (settings.prioritizeSeekAccuracy.value) {
            extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        }

        val cacheFactory = cacheFactory
            .setCacheWriteDataSinkFactory(null)
            .takeIf { episodeLocation.shouldUseCache() }

        startCachingEntireEpisodeIfNeeded(
            cacheDataSourceFactory = cacheFactory,
            episodeLocation = episodeLocation,
            onCachingComplete = onCachingComplete,
        )

        val dataFactory = cacheFactory ?: defaultFactory
        // Only DefaultMediaSourceFactory applies the ClippingConfiguration, so clipping must win over HLS.
        val factory = when {
            (clipRange != null) -> DefaultMediaSourceFactory(dataFactory, extractorsFactory)
            episodeLocation.isHlsStream -> HlsMediaSource.Factory(dataFactory)
            else -> ProgressiveMediaSource.Factory(dataFactory, extractorsFactory)
        }
        // PodHopper: Android Automotive always gets the resilient retry policy. Car cellular
        // connections drop and stall far more than phone networks, and the Media3 default
        // policy turns many of those transient failures into instant fatal errors, which on
        // AAOS tears down the Now Playing screen. Phone release builds keep the flag-gated
        // default behavior, unchanged.
        if (FeatureFlag.isEnabled(Feature.LOAD_ERROR_HANDLING_POLICY) || Util.isAutomotive(context)) {
            factory.setLoadErrorHandlingPolicy(PocketCastsLoadErrorHandlingPolicy())
        }
        return factory.createMediaSource(mediaItem)
    }

    private fun startCachingEntireEpisodeIfNeeded(
        cacheDataSourceFactory: CacheDataSource.Factory? = null,
        episodeLocation: EpisodeLocation,
        onCachingComplete: (String) -> Unit,
    ) {
        if (episodeLocation.isHlsStream) return
        val episodeUri = episodeLocation.uri ?: return
        val cacheFactory = cacheDataSourceFactory ?: cacheFactory
            .setCacheWriteDataSinkFactory(null)
            .takeIf { episodeLocation.shouldUseCache() }

        if (cacheFactory != null) {
            CacheWorker.startCachingEntireEpisode(
                context = context,
                url = episodeUri,
                episodeUuid = episodeLocation.episode.uuid,
                onCachingComplete = onCachingComplete,
            )
        }
    }

    fun resetEpisodeCaching(
        episodeLocation: EpisodeLocation,
        onCachingReset: (String) -> Unit,
        onCachingComplete: (String) -> Unit,
    ) {
        val episodeUuid = episodeLocation.episode.uuid
        try {
            if (episodeLocation.shouldUseCache().not()) return

            cache?.removeResource(episodeUuid)
            onCachingReset(episodeUuid)

            startCachingEntireEpisodeIfNeeded(
                cacheDataSourceFactory = cacheFactory,
                episodeLocation = episodeLocation,
                onCachingComplete = onCachingComplete,
            )
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Caching reset complete for episode id: $episodeUuid")
        } catch (e: Exception) {
            val errorMessage = "Failed to reset episode caching for episode $episodeUuid"
            Timber.e(e, errorMessage)
            crashLogging.sendReport(e, message = errorMessage)
            LogBuffer.e(LogBuffer.TAG_PLAYBACK, errorMessage)
        }
    }

    private fun EpisodeLocation.shouldUseCache() = !episode.isDownloaded && !episode.isDownloading && !isHlsStream && settings.cacheEntirePlayingEpisode.value

    private fun ClosedRange<Long>.toClippingConfiguration() = ClippingConfiguration.Builder()
        .setStartPositionMs(start)
        .setEndPositionMs(endInclusive)
        .build()

    private companion object {
        const val CACHE_DIR_NAME = "podhopper-exoplayer-cache"
        const val LEGACY_CACHE_DIR_NAME = "pocketcasts-exoplayer-cache"
    }
}
