package com.example.myledger.notify

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.myledger.data.AppDatabase
import com.example.myledger.data.AutoCategory
import com.example.myledger.data.Expense
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 竖着耳朵听通知的那个"耳朵"。
 *
 * 系统一旦把「通知使用权」给了知账，所有 App 弹的通知都会送到这里。
 * 干两件事：① 原样记进侦察日志 ② 认出是支付通知就自动记一笔账。
 *
 * 这个服务由【系统】托管 —— 不需要 App 开着，不需要留在最近任务里。
 * 每次连上/断开我都会往日志里记一笔，翻「通知侦察」就能看出它断过没有。
 */
class PaymentNotificationListener : NotificationListenerService() {

    companion object {
        private const val PREFS = "auto_status"
        private const val KEY_LAST_CONNECTED = "last_connected"

        /**
         * 这个进程里，服务现在连着吗？
         *
         * ⚠️ 只能说"这个进程里"。进程刚起来、系统还没绑好的那一瞬间它也是 false，
         * 所以用的人不能一看 false 就下结论，得耐心等一等。
         */
        @Volatile
        var connected = false

        /**
         * 上一次连上是什么时候。这个存在 SharedPreferences 里，
         * 【进程死了也还在】，所以能拿来判断"它到底多久没干活了"。
         */
        fun lastConnectedAt(context: Context): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_CONNECTED, 0L)

        private fun markConnected(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_CONNECTED, System.currentTimeMillis())
                .apply()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 同一条通知常被系统重复推送，短时间内只认第一次
    private var lastKey = ""
    private var lastTime = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        markConnected(applicationContext)
        NotificationLog.appendEvent(applicationContext, "✅ 监听服务已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        NotificationLog.appendEvent(applicationContext, "⚠️ 监听服务已断开（系统通知的）")
    }

    override fun onDestroy() {
        connected = false
        NotificationLog.appendEvent(applicationContext, "💀 监听服务被销毁")
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        val body = listOf(title, text, bigText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (body.isBlank()) return

        val pkg = sbn.packageName
        val fromPaymentApp = PaymentParser.PAYMENT_PACKAGES.containsKey(pkg)
        val looksLikeMoney = PaymentParser.findAmount(body) != null

        // 无关的通知直接扔掉，别把日志塞满
        if (!fromPaymentApp && !looksLikeMoney) return

        // 去重：同样的内容 20 秒内只处理一次
        val key = "$pkg|$body"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastTime < 20_000) return
        lastKey = key
        lastTime = now

        // ① 原样抄进侦察日志
        NotificationLog.append(applicationContext, pkg, title, text, bigText)

        // ② 认出是支付通知就自动入账
        val parsed = PaymentParser.parse(pkg, body) ?: return
        val category = AutoCategory.guess(body)

        scope.launch {
            AppDatabase.get(applicationContext).expenseDao().insert(
                Expense(
                    amount = parsed.amount,
                    category = category,
                    note = "自动 · ${parsed.source}",
                    timestamp = now
                )
            )
        }
    }
}