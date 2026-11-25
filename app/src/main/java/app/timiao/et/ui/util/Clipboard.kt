package app.timiao.et.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import app.timiao.et.R

fun copyToClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, context.getString(R.string.label_copied, text), Toast.LENGTH_SHORT).show()
}
