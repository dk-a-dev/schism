package ai.schism.split.groups.join

import android.content.Context
import android.content.Intent

/**
 * Share a group's invite link via the system share sheet. QR-code rendering is deferred until a
 * QR dependency (e.g. ZXing) is added to the build; the deep link works today.
 */
fun shareGroupInvite(context: Context, groupId: String, groupName: String) {
    val link = JoinGroupViewModel.shareLink(groupId)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Join \"$groupName\" on Schism")
        putExtra(Intent.EXTRA_TEXT, "Join my group \"$groupName\" on Schism: $link")
    }
    context.startActivity(Intent.createChooser(send, "Share invite").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
