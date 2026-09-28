package com.example.myledger.notify

/**
 * 从通知文本里抠出"花了多少钱"。
 *
 * ⚠️ 这里的规则全是初版猜测 —— 各家 App 的通知格式不一样，
 * 真正准的规则得看过「通知侦察」抓到的真实文本再调。
 */
object PaymentParser {

    /** 这些 App 的通知才自动入账 */
    val PAYMENT_PACKAGES = mapOf(
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.tencent.mm" to "微信",
        "com.unionpay" to "云闪付"
    )

    /** 金额长啥样：¥12.50 / 12.5元 / RMB 12.50 */
    private val AMOUNT_PATTERNS = listOf(
        Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)"""),
        Regex("""([0-9]+(?:\.[0-9]{1,2})?)\s*元"""),
        Regex("""(?i)RMB\s*([0-9]+(?:\.[0-9]{1,2})?)""")
    )

    /** 带这些字的多半不是支出，别记 */
    private val NOT_SPENDING = listOf(
        "退款", "已退", "收款", "收入", "转入", "到账", "红包", "退还",
        "返现", "优惠", "已取消", "支付失败", "验证码", "余额", "领取"
    )

    data class Parsed(val amount: Double, val source: String)

    /** 文本里有没有金额 */
    fun findAmount(text: String): Double? {
        for (pattern in AMOUNT_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val value = match.groupValues[1].toDoubleOrNull() ?: continue
            if (value > 0) return value
        }
        return null
    }

    /** 看着像支出吗（不是收入/退款之类） */
    fun isSpending(text: String): Boolean =
        NOT_SPENDING.none { text.contains(it) }

    /** 认出是支付通知就返回金额和来源，认不出返回 null */
    fun parse(packageName: String, text: String): Parsed? {
        val source = PAYMENT_PACKAGES[packageName] ?: return null
        if (!isSpending(text)) return null
        val amount = findAmount(text) ?: return null
        return Parsed(amount, source)
    }
}