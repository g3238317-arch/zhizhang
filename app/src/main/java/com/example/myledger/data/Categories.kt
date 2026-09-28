package com.example.myledger.data

/**
 * 分类清单。想加分类？在这行后面加个字符串就行，界面会自动多一个按钮。
 */
object Categories {

    val ALL = listOf("餐饮", "交通", "购物", "日用", "娱乐", "医疗", "其他")

    /** 每个分类一个颜色，用来画列表左边的小圆点和统计页的横条 */
    private val COLORS = mapOf(
        "餐饮" to "#FF7A45",
        "交通" to "#4A90D9",
        "购物" to "#B07CC6",
        "日用" to "#38B2A0",
        "娱乐" to "#F2B035",
        "医疗" to "#E5645E"
    )

    /** 没配色的分类走这个低调的灰棕 */
    private const val FALLBACK = "#A9A29B"

    fun colorOf(category: String): String = COLORS[category] ?: FALLBACK
}