package au.com.shiftyjelly.pocketcasts.models.type

import au.com.shiftyjelly.pocketcasts.payment.SubscriptionTier
import com.automattic.eventhorizon.UserType
import com.squareup.moshi.JsonClass
import java.time.Instant

@JsonClass(generateAdapter = true)
data class Membership(
    val subscription: Subscription?,
    val createdAt: Instant?,
    val features: List<MembershipFeature>,
) {
    val analyticsValue get() = when {
        subscription?.tier == SubscriptionTier.Plus -> UserType.Paid
        subscription?.tier == SubscriptionTier.Patron -> UserType.Paid
        createdAt != null -> UserType.Free
        else -> UserType.Unsigned
    }

    fun hasFeature(feature: MembershipFeature): Boolean {
        val isSubscriptionFeature = subscription?.tier?.hasFeature(feature) == true
        return isSubscriptionFeature || feature in features
    }

    companion object {
        val Empty = Membership(
            subscription = null,
            createdAt = null,
            features = emptyList(),
        )

        // PodHopper: there is no Plus tier to buy, so every install is permanently entitled. This
        // synthetic lifetime subscription is the cachedMembership default, which unlocks every
        // membership gated feature (bookmarks, chapter deselection, headphone bookmark action,
        // banner ad suppression) with no billing and no Pocket Casts account. Gift platform with
        // zero gift days keeps isChampion false; auto renewing keeps isExpiring false.
        val PodHopperLifetime = Membership(
            subscription = Subscription(
                tier = SubscriptionTier.Plus,
                billingCycle = null,
                platform = SubscriptionPlatform.Gift,
                expiryDate = Instant.parse("9999-12-31T23:59:59Z"),
                isAutoRenewing = true,
                giftDays = 0,
            ),
            createdAt = null,
            features = emptyList(),
        )
    }
}

@JsonClass(generateAdapter = false)
enum class MembershipFeature {
    NoBannerAds,
    NoDiscoverAds,
}

private fun SubscriptionTier.hasFeature(feature: MembershipFeature) = when (this) {
    SubscriptionTier.Plus -> when (feature) {
        MembershipFeature.NoBannerAds -> true
        MembershipFeature.NoDiscoverAds -> true
    }

    SubscriptionTier.Patron -> when (feature) {
        MembershipFeature.NoBannerAds -> true
        MembershipFeature.NoDiscoverAds -> true
    }
}
