package ai.schism.split.walkthrough

import ai.schism.split.R
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * One-time contextual tips, in priority order. Each is purely informational: a hint can point at the
 * control that turns a feature on, but it never flips a setting, requests a permission, or starts a
 * download itself — there is no callback here that could.
 */
enum class FeatureHint(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
    val target: WalkthroughTargetId,
) {
    SMS_OPT_IN(
        id = "sms_opt_in",
        titleRes = R.string.hint_sms_opt_in_title,
        bodyRes = R.string.hint_sms_opt_in_body,
        target = WalkthroughTargetId.SMS_OPT_IN,
    ),
    OCR_DOWNLOAD(
        id = "ocr_download",
        titleRes = R.string.hint_ocr_download_title,
        bodyRes = R.string.hint_ocr_download_body,
        target = WalkthroughTargetId.OCR_DOWNLOAD,
    ),
    PARTICIPANT_INVITE(
        id = "participant_invite",
        titleRes = R.string.hint_participant_invite_title,
        bodyRes = R.string.hint_participant_invite_body,
        target = WalkthroughTargetId.PARTICIPANT_INVITE,
    ),
    UPI_SETTLE(
        id = "upi_settle",
        titleRes = R.string.hint_upi_settle_title,
        bodyRes = R.string.hint_upi_settle_body,
        target = WalkthroughTargetId.UPI_SETTLE,
    ),
    LIVE_SPLIT_HOST(
        id = "live_split_host",
        titleRes = R.string.hint_live_split_host_title,
        bodyRes = R.string.hint_live_split_host_body,
        target = WalkthroughTargetId.LIVE_SPLIT_HOST,
    ),
}

/**
 * At most one hint, ever: the highest-priority relevant tip the user has not dismissed. [suppressed]
 * covers the guided tour, ads, paywalls, and system dialogs — while any of those own the screen no
 * hint is eligible, so tips cannot stack on top of them or on each other.
 */
fun selectHint(
    relevant: Collection<FeatureHint>,
    dismissed: Set<String>,
    suppressed: Boolean = false,
): FeatureHint? = if (suppressed) {
    null
} else {
    FeatureHint.entries.firstOrNull { it in relevant && it.id !in dismissed }
}

/**
 * Renders the winning hint for this screen once its target has actually been laid out. Drop it at
 * the top of a screen's content and pass whichever hints that screen can honestly explain.
 */
@Composable
fun FeatureHintHost(
    relevant: Set<FeatureHint>,
    suppressed: Boolean = false,
    viewModel: WalkthroughViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val registry = LocalWalkthroughTargets.current
    val hint = selectHint(
        relevant = relevant,
        dismissed = state.dismissedHintIds,
        suppressed = suppressed || state.status == WalkthroughStatus.ACTIVE,
    ) ?: return
    // Wait for layout: an unregistered target would produce a floating, unanchored tip.
    if (registry[hint.target] == null) return

    WalkthroughOverlay(
        targetId = hint.target,
        title = stringResource(hint.titleRes),
        body = stringResource(hint.bodyRes),
        confirmLabel = stringResource(R.string.walkthrough_got_it),
        onConfirm = { viewModel.dismissHint(hint) },
    )
}
