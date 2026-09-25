package au.com.shiftyjelly.pocketcasts.servers.di

import au.com.shiftyjelly.pocketcasts.servers.analytics.AnalyticsLiveServiceManager
import au.com.shiftyjelly.pocketcasts.servers.analytics.AnalyticsLiveServiceManagerImpl
import au.com.shiftyjelly.pocketcasts.servers.list.ListServiceManager
import au.com.shiftyjelly.pocketcasts.servers.list.ListServiceManagerImpl
import au.com.shiftyjelly.pocketcasts.servers.refresh.RefreshServiceManager
import au.com.shiftyjelly.pocketcasts.servers.refresh.RefreshServiceManagerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ServersModuleBinds {

    @Binds
    @Singleton
    abstract fun provideRefreshServiceManager(refreshServiceManagerImpl: RefreshServiceManagerImpl): RefreshServiceManager

    @Binds
    @Singleton
    abstract fun provideShareServiceManager(shareServiceManagerImpl: ListServiceManagerImpl): ListServiceManager

    @Binds
    @Singleton
    abstract fun provideAnalyticsLiveServiceManager(impl: AnalyticsLiveServiceManagerImpl): AnalyticsLiveServiceManager
}
