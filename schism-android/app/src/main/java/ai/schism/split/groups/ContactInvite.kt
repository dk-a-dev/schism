package ai.schism.split.groups

import ai.schism.split.groups.join.JoinGroupViewModel
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract

/**
 * Shared contact-picker + SMS-invite helpers used when adding people to a group (both when creating
 * and editing). Picking a phone-contact row grants temporary read access to just that row, so no
 * READ_CONTACTS permission is needed; the captured number lets the backend auto-link the friend to
 * the group the moment they register with the same phone.
 */

/** An ACTION_PICK intent for a single phone-contact row (name + number). */
fun pickPhoneContactIntent(): Intent =
    Intent(Intent.ACTION_PICK).apply { type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE }

/** Reads the display name + phone number of the picked phone-contact row. */
fun contactNameAndPhone(context: Context, uri: Uri): Pair<String, String?>? =
    context.contentResolver
        .query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            null,
        )
        ?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            name to c.getString(1)?.takeIf { it.isNotBlank() }
        }

/** Prefill an SMS to each invited number with the group's join link. */
fun sendSmsInvites(context: Context, phones: List<String>, groupName: String, groupId: String) {
    if (phones.isEmpty()) return
    val link = JoinGroupViewModel.shareLink(groupId)
    val name = groupName.ifBlank { "our group" }
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phones.joinToString(";"))).apply {
        putExtra("sms_body", "Join \"$name\" on Schism to split our expenses: $link")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
