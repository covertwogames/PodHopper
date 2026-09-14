package au.com.shiftyjelly.pocketcasts.payment

import android.app.Activity
import kotlinx.coroutines.flow.SharedFlow

interface PaymentDataSource {
    val purchaseResults: SharedFlow<PaymentResult<List<Purchase>>>

    suspend fun loadProducts(): PaymentResult<List<Product>>

    suspend fun loadPurchases(): PaymentResult<List<Purchase>>

    suspend fun launchBillingFlow(key: SubscriptionPlan.Key, activity: Activity): PaymentResult<Unit>

    suspend fun acknowledgePurchase(purchase: Purchase): PaymentResult<Purchase>

    companion object {
        fun fake() = FakePaymentDataSource()
    }
}
