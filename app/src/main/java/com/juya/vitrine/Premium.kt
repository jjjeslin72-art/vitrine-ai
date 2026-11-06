package com.juya.vitrine

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.json.JSONObject

/**
 * ClickModa 구독 (Google Play 결제).
 *
 * Play Console > 수익 창출 > 구독 에서 만들 상품 (ID를 똑같이):
 *   상품 ID "clickmoda_basic"  기본 요금제 "monthly"  월 R$ 49,90  (한 달 100장)
 *   상품 ID "clickmoda_pro"    기본 요금제 "monthly"  월 R$ 99,90  (한 달 300장)
 *                            기본 요금제 "yearly"   연 R$ 899,90
 *   (원하면 "monthly"에 7일 무료 체험 혜택 추가 — 앱이 알아서 체험부터 보여줌)
 */
object Premium {
    const val BASIC = "clickmoda_basic"
    const val PRO = "clickmoda_pro"
    const val MONTHLY = "monthly"
    const val YEARLY = "yearly"
    const val REVIEW_CODE = "CLICKMODA2026"   // Google Play 심사용 (버전 글자를 5번 누르면 입력 창)

    @Volatile var plan = "none"   // none | basic | pro
        private set
    @Volatile var ready = false
        private set
    @Volatile var token = ""      // 서버에서 구독 확인할 때 쓰는 결제 토큰
        private set

    private var client: BillingClient? = null
    private val details = HashMap<String, ProductDetails>()
    private val prices = JSONObject()
    private var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null

    private fun prefs(c: Context) = c.getSharedPreferences("premium", Context.MODE_PRIVATE)

    fun onChange(l: () -> Unit) { listener = l }
    private fun changed() = main.post { listener?.invoke() }

    fun init(context: Context) {
        val c = context.applicationContext
        app = c
        plan = if (prefs(c).getBoolean("review", false)) "pro" else prefs(c).getString("plan", "none") ?: "none"
        token = prefs(c).getString("token", "") ?: ""
        if (client != null) { refresh(); return }
        client = BillingClient.newBuilder(c)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) handle(purchases)
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        connect()
    }

    private fun connect() {
        val bc = client ?: return
        bc.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    ready = true; refresh(); loadDetails(); changed()
                }
            }
            override fun onBillingServiceDisconnected() {
                ready = false
                main.postDelayed({ connect() }, 5000)
            }
        })
    }

    /** 지금 구독 중인지 Play에 다시 물어본다 (구매 복원) */
    fun refresh() {
        val bc = client ?: return
        if (!ready) return
        bc.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases, fullList = true)
        }
    }

    private fun loadDetails() {
        val bc = client ?: return
        val q = QueryProductDetailsParams.newBuilder().setProductList(listOf(BASIC, PRO).map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
        }).build()
        bc.queryProductDetailsAsync(q) { result, res ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            for (pd in res.productDetailsList) {
                details[pd.productId] = pd
                for (o in pd.subscriptionOfferDetails ?: emptyList()) {
                    val key = (if (pd.productId == BASIC) "basic_" else "pro_") + o.basePlanId
                    val phases = o.pricingPhases.pricingPhaseList
                    prices.put(key, phases.lastOrNull()?.formattedPrice ?: "")
                    if (phases.any { it.priceAmountMicros == 0L }) prices.put(key + "_trial", true)
                }
            }
            changed()
        }
    }

    private fun handle(purchases: List<Purchase>, fullList: Boolean = false) {
        var best = "none"; var tok = ""
        for (p in purchases) {
            if (p.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            val tier = when {
                PRO in p.products -> "pro"
                BASIC in p.products -> "basic"
                else -> continue
            }
            if (best != "pro") { best = tier; tok = p.purchaseToken }
            if (!p.isAcknowledged) {
                client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
            }
        }
        val c = app ?: return
        val review = prefs(c).getBoolean("review", false)
        // 결제 한 건만 받은 경우엔 기존 구독을 지우지 않는다
        val now = when {
            review -> "pro"
            best != "none" -> best
            fullList -> "none"
            else -> plan
        }
        if (best != "none") token = tok else if (fullList) token = ""
        prefs(c).edit().putString("plan", if (review) prefs(c).getString("plan", "none") else now).putString("token", token).apply()
        if (now != plan) { plan = now; changed() } else changed()
    }

    fun tryReviewCode(c: Context, code: String): Boolean {
        if (code.trim().uppercase() != REVIEW_CODE) return false
        prefs(c).edit().putBoolean("review", true).apply()
        plan = "pro"; changed()
        return true
    }

    /** 결제 창 열기. which = "basic_monthly" | "pro_monthly" | "pro_yearly" */
    fun buy(activity: Activity, which: String): Boolean {
        val bc = client ?: return false
        val (pid, base) = when (which) {
            "basic_monthly" -> BASIC to MONTHLY
            "pro_yearly" -> PRO to YEARLY
            else -> PRO to MONTHLY
        }
        val pd = details[pid] ?: return false
        val offer = (pd.subscriptionOfferDetails ?: return false).filter { it.basePlanId == base }
            .sortedByDescending { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
            .firstOrNull() ?: return false
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).setOfferToken(offer.offerToken).build()
        )).build()
        return bc.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    /** 화면에 넘길 상태 */
    fun state(): String = JSONObject()
        .put("ready", ready && details.isNotEmpty())
        .put("plan", plan)
        .put("token", token)
        .put("prices", prices)
        .toString()
}
