package ai.schism.split.core.ads

import ai.schism.split.BuildConfig
import ai.schism.split.core.billing.EntitlementRepository
import ai.schism.split.core.billing.isKnown
import ai.schism.split.core.billing.isPlus
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.sms.data.TransactionDao
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Test tag for the one and only ad slot in the app. */
const val AD_SLOT_TEST_TAG = "inline_adaptive_ad"

/** No more than one ad request per minute, however often the screen recomposes. */
private const val MIN_REQUEST_INTERVAL_MS = 60_000L

@HiltViewModel
class AdSlotViewModel @Inject constructor(
    private val entitlements: EntitlementRepository,
    val consent: ConsentManager,
    dao: TransactionDao,
    settings: SettingsRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    // Account age is the install age of this device's Schism — no extra storage needed, and the ad
    // gate only ever needs "older than a week", not an exact signup timestamp.
    private val accountAgeDays: Int = runCatching {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - installed).toInt()
    }.getOrDefault(0)

    val eligible: StateFlow<Boolean> = combine(
        entitlements.state,
        entitlements.config,
        consent.canRequestAds,
        dao.observeAll(),
        settings.knownGroupIds,
    ) { entitlement, config, consented, transactions, groups ->
        AdContext(
            adsEnabledByBackend = config.adsEnabled,
            entitlementKnown = entitlement.isKnown,
            isPlus = entitlement.isPlus,
            consentGranted = consented,
            accountAgeDays = accountAgeDays,
            // "Meaningful actions" = things the person actually did with Schism: transactions they
            // captured plus groups they created or joined.
            meaningfulActions = transactions.size + groups.size,
            onAdSurface = true,
            // ponytail: "foreground" is implied by this flow only being collected while the ad
            // surface is composed (WhileSubscribed). Wire a real lifecycle observer only if a
            // background-composed host ever appears.
            foreground = true,
        ).isEligible()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

/**
 * The app's single ad placement: one inline adaptive banner, rendered *after* the Spending/Insights
 * summary and separated from it, labelled so it can never be mistaken for Schism's own content.
 *
 * It renders nothing at all unless [AdContext] says every condition holds, and it destroys the
 * [AdView] when it leaves composition. There is no interstitial, app-open, rewarded, or native ad
 * anywhere in this app.
 */
@Composable
fun InlineAdaptiveAd(
    modifier: Modifier = Modifier,
    viewModel: AdSlotViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val eligible by viewModel.eligible.collectAsState()

    // Gathering consent is what makes an ad request lawful, so ask before the first request — but
    // only on the surface that can actually show one.
    LaunchedEffect(activity) { activity?.let { viewModel.consent.refresh(it) } }

    if (!eligible || activity == null) return

    val widthDp = LocalConfiguration.current.screenWidthDp
    val adView = remember(widthDp) {
        MobileAds.initialize(context)
        AdView(context).apply {
            adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID
            setAdSize(AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(context, widthDp))
        }
    }
    // A banner holds a WebView; leaking it past the screen is the classic ad-SDK memory bug.
    DisposableEffect(adView) {
        if (AdRequestClock.mayRequest()) adView.loadAd(AdRequest.Builder().build())
        onDispose { adView.destroy() }
    }

    Column(modifier.fillMaxWidth().testTag(AD_SLOT_TEST_TAG)) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            "Sponsored",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        AndroidView(modifier = Modifier.fillMaxWidth(), factory = { adView })
    }
}

/** Enforces the minimum gap between ad requests process-wide. */
private object AdRequestClock {
    @Volatile
    private var lastRequestMs = 0L

    @Synchronized
    fun mayRequest(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRequestMs < MIN_REQUEST_INTERVAL_MS) return false
        lastRequestMs = now
        return true
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
