package au.com.shiftyjelly.pocketcasts.repositories.di

import android.accounts.AccountManager
import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import au.com.shiftyjelly.pocketcasts.payment.PaymentClient
import au.com.shiftyjelly.pocketcasts.payment.PaymentDataSource
import au.com.shiftyjelly.pocketcasts.repositories.payment.AnalyticsPaymentListener
import au.com.shiftyjelly.pocketcasts.repositories.payment.LoggingPaymentListener
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager
import au.com.shiftyjelly.pocketcasts.servers.sync.TokenHandler
import au.com.shiftyjelly.pocketcasts.utils.AppPlatform
import au.com.shiftyjelly.pocketcasts.utils.Util
import com.automattic.eventhorizon.EventHorizon
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class RepositoryProviderModule {

    @Provides
    @Singleton
    fun provideTokenHandler(syncAccountManager: SyncAccountManager): TokenHandler = syncAccountManager

    @Provides
    @Singleton
    fun provideAppPlatform(@ApplicationContext context: Context): AppPlatform = Util.getAppPlatform(context)

    @Provides
    @Singleton
    @ProcessLifecycle
    fun processLifecycle(): LifecycleOwner = ProcessLifecycleOwner.get()

    @Provides
    @IntoSet
    fun provideLoggingListener(): PaymentClient.Listener {
        return LoggingPaymentListener()
    }

    @Provides
    @IntoSet
    fun provideAnalyticsListener(eventHorizon: EventHorizon): PaymentClient.Listener {
        return AnalyticsPaymentListener(eventHorizon)
    }

    // PodHopper: Google Play Billing has been removed. The Google-backed data source only ever ran
    // when the package name was Pocket Casts' own, which no PodHopper build has, so every build has
    // always used this in-memory stand-in.
    @Provides
    @Singleton
    fun providePaymentDataSource(): PaymentDataSource {
        return PaymentDataSource.fake()
    }

    @Provides
    @Singleton
    fun provideAccountManager(@ApplicationContext context: Context): AccountManager {
        return AccountManager.get(context)
    }
}
