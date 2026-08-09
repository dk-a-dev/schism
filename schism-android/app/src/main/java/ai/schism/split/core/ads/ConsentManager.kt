package ai.schism.split.core.ads

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google's User Messaging Platform, wrapped so nothing outside this file sees a UMP type.
 *
 * [refresh] is called the first time the ad surface is composed in a session — i.e. before any ad is
 * ever requested — and shows the required consent form when the user's region needs one. Until UMP
 * says [canRequestAds], no ad is requested at all.
 */
@Singleton
class ConsentManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _canRequestAds = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    /** True when the user's region entitles them to a "Privacy choices" entry in Settings. */
    private val _privacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private val info: ConsentInformation by lazy { UserMessagingPlatform.getConsentInformation(context) }

    @Volatile
    private var refreshedThisSession = false

    /** Refreshes consent once per launch. Any failure simply leaves ads unrequestable. */
    fun refresh(activity: Activity) {
        if (refreshedThisSession) return
        refreshedThisSession = true
        info.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { publish() }
            },
            { publish() },
        )
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { publish() }
    }

    private fun publish() {
        _canRequestAds.value = info.canRequestAds()
        _privacyOptionsRequired.value =
            info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }
}
