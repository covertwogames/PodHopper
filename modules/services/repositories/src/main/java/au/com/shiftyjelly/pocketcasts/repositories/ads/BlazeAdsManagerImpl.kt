package au.com.shiftyjelly.pocketcasts.repositories.ads

import au.com.shiftyjelly.pocketcasts.models.db.AppDatabase
import au.com.shiftyjelly.pocketcasts.models.entity.BlazeAd
import au.com.shiftyjelly.pocketcasts.models.type.BlazeAdLocation
import au.com.shiftyjelly.pocketcasts.models.type.MembershipFeature
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Singleton
class BlazeAdsManagerImpl @Inject constructor(
    private val settings: Settings,
    appDatabase: AppDatabase,
) : BlazeAdsManager {

    private val blazeAdDao = appDatabase.blazeAdDao()

    override suspend fun updateAds() {
        // PodHopper: Blaze ad payloads were fetched from the Pocket Casts static server on every
        // app foreground. PodHopper shows no Blaze ads, so never fetch, and clear anything a
        // previous build may have cached so no stale ad can ever surface.
        blazeAdDao.replaceAll(emptyList())
    }

    override fun findPodcastListAd(): Flow<BlazeAd?> {
        return findBlazeAdByLocation(BlazeAdLocation.PodcastList)
    }

    override fun findPlayerAd(): Flow<BlazeAd?> {
        return findBlazeAdByLocation(BlazeAdLocation.Player)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun findBlazeAdByLocation(location: BlazeAdLocation): Flow<BlazeAd?> {
        val featureFlag = location.feature
        if (location == BlazeAdLocation.Unknown || featureFlag == null) {
            return flowOf(null)
        }
        return combine(
            settings.cachedMembership.flow,
            FeatureFlag.isEnabledFlow(featureFlag),
            ::Pair,
        ).flatMapLatest { (membership, isEnabled) ->
            if (isEnabled && !membership.hasFeature(MembershipFeature.NoBannerAds)) {
                blazeAdDao.findByLocationFlow(location)
                    .map { promotions -> promotions.firstOrNull() }
            } else {
                flowOf(null)
            }
        }
    }
}
