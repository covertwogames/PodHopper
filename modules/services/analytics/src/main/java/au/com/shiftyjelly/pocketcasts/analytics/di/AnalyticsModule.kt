package au.com.shiftyjelly.pocketcasts.analytics.di

import au.com.shiftyjelly.pocketcasts.analytics.AccountStatusInfo
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsController
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsListener
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsTracker
import au.com.shiftyjelly.pocketcasts.analytics.EventSink
import au.com.shiftyjelly.pocketcasts.analytics.LoggingAnalyticsListener
import au.com.shiftyjelly.pocketcasts.analytics.NoOpTracker
import au.com.shiftyjelly.pocketcasts.analytics.experiments.ExperimentProvider
import com.automattic.eventhorizon.EventHorizon
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AnalyticsModule {
    companion object {
        // Necessary to satisfy Dagger injection
        @Provides
        @IntoSet
        fun provideNoOpTracker(): AnalyticsTracker = NoOpTracker

        @Provides
        @Singleton
        fun provideEventSink(
            trackers: Set<@JvmSuppressWildcards AnalyticsTracker>,
            listeners: Set<@JvmSuppressWildcards AnalyticsListener>,
        ): EventSink = EventSink(trackers, listeners)

        @Provides
        @Singleton
        fun provideEventHorizon(eventSink: EventSink): EventHorizon {
            return EventHorizon(eventSink)
        }

        @Provides
        @IntoSet
        fun provideLoggingListener(): AnalyticsListener {
            return LoggingAnalyticsListener()
        }

        // PodHopper: the experiments provider is a local no-op and the remote experiment library it
        // used to wrap has been removed, so it only needs the account status it always took.
        @Provides
        @Singleton
        fun provideExperimentProvider(
            accountStatusInfo: AccountStatusInfo,
        ): ExperimentProvider = ExperimentProvider(accountStatusInfo)
    }

    @Binds
    abstract fun bindAnalyticsController(eventSink: EventSink): AnalyticsController
}
