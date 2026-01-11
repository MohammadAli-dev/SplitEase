package com.splitease.ui.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * Shares the invite link via Android system share sheet.
 */
/**
 * Opens the system share sheet to share an invite message that includes the given deep link.
 *
 * @param deepLink The invite deep link to include in the shared message.
 */
/**
 * Opens the system share sheet to share an invite message containing the given deep link.
 *
 * @param deepLink The deep link to include in the invite message. 
 */
fun Context.shareInviteLink(deepLink: String) {
    val text = "Join me on SplitEase:\n$deepLink"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, "Share invite").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(chooser)
}

/**
 * Places the given text into the system clipboard as plain text labeled "Invite Link".
 *
 * @param text The text to copy to the clipboard.
 */
fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Invite Link", text))
}