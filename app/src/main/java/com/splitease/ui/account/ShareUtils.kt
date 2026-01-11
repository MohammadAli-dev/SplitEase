package com.splitease.ui.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * Shares the invite link via Android system share sheet.
 */
fun Context.shareInviteLink(deepLink: String) {
    val text = "Join me on SplitEase:\n$deepLink"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, "Share invite"))
}

/**
 * Copies text to clipboard.
 */
fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Invite Link", text))
}
