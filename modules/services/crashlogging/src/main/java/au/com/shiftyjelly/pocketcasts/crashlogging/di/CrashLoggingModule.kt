package au.com.shiftyjelly.pocketcasts.crashlogging.di

import au.com.shiftyjelly.pocketcasts.crashlogging.CrashLogging
import au.com.shiftyjelly.pocketcasts.crashlogging.LogBufferCrashLogging
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CrashLoggingModule {
    @Binds
    @Singleton
    internal abstract fun bindCrashLogging(crashLogging: LogBufferCrashLogging): CrashLogging
}
