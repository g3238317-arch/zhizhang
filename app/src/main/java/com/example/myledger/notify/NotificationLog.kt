package com.example.myledger.notify

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「通知侦察」用的原始记录本。
 *
 * 记两种东西：
 *   ① 抓到的通知原文（不做解析，原样抄）
 *   ② 监听服务的生死事件（连上了 / 断开了 / 被销毁了）
 *
 * ② 特别有用：把两样东西混在一个时间轴上，你就能看出
 * "我把 App 从后台划掉之后，服务到底还在不在"。
 */
object NotificationLog {

    private const val FILE_NAME = "notifications.log"

    /** 文件超过这个大小就清空重来，免得越攒越大 */
    private const val MAX_BYTES = 300_000

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    private fun write(context: Context, text: String) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.length() > MAX_BYTES) file.delete()
            context.openFileOutput(FILE_NAME, Context.MODE_APPEND).use {
                it.write(text.toByteArray())
            }
        }
    }

    fun append(context: Context, packageName: String, title: String, text: String, bigText: String) {
        val entry = buildString {
            append("[").append(stamp()).append("] ").append(packageName).append("\n")
            append("  标题: ").append(title).append("\n")
            append("  正文: ").append(text).append("\n")
            if (bigText.isNotEmpty() && bigText != text) {
                append("  长文: ").append(bigText).append("\n")
            }
            append("---\n")
        }
        write(context, entry)
    }

    /** 记一条服务生死事件 */
    fun appendEvent(context: Context, message: String) {
        write(context, "[${stamp()}] $message\n---\n")
    }

    fun readAll(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}