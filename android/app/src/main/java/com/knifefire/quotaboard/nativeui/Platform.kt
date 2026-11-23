package com.knifefire.quotaboard.nativeui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Platform {
    fun copyText(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("额度看板凭证备份", text))
    }

    fun readClipboard(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = cm.primaryClip?.getItemAt(0)
        item?.text?.toString()?.trim() ?: ""
    } catch (e: Exception) {
        ""
    }

    fun shareText(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, "额度看板凭证备份")
        }
        ctx.startActivity(Intent.createChooser(send, "保存凭证备份"))
    }

    fun fmtTime(ms: Long): String {
        if (ms <= 0) return "—"
        return SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(ms))
    }

    /** 毫秒级时间戳或原始字符串的重置时间，友好显示 */
    fun fmtReset(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val n = raw.toLongOrNull()
            ?: return raw.replace('T', ' ').take(16)
        return fmtTime(n)
    }
}
