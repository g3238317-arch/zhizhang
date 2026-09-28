package com.example.myledger.data

/**
 * 从通知文本里猜这笔钱花在哪类。
 *
 * 猜不准就给"其他"，你手动改一下就行。
 * 想让某个店自动归到某类？在对应那行的引号里加个词，重跑就生效。
 */
object AutoCategory {

    private val RULES = listOf(
        "餐饮" to listOf(
            "餐", "饭", "食", "美团", "饿了么", "肯德基", "KFC", "麦当劳",
            "星巴克", "咖啡", "奶茶", "烧烤", "火锅", "小吃", "面馆", "汉堡", "烘焙"
        ),
        "交通" to listOf(
            "地铁", "公交", "打车", "滴滴", "出行", "加油", "停车",
            "铁路", "12306", "高速", "ETC", "单车", "哈啰", "青桔"
        ),
        "日用" to listOf(
            "便利店", "超市", "日用", "洗护", "家居", "全家", "罗森",
            "711", "永辉", "大润发", "盒马"
        ),
        "购物" to listOf(
            "淘宝", "天猫", "京东", "拼多多", "唯品会", "商场", "旗舰",
            "优衣库", "苏宁", "小米", "华为"
        ),
        "娱乐" to listOf(
            "电影", "游戏", "音乐", "视频", "KTV", "影院", "爱奇艺",
            "腾讯视频", "网易云", "哔哩哔哩", "B站", "会员", "网咖"
        ),
        "医疗" to listOf(
            "医院", "药", "诊所", "体检", "口腔", "卫生"
        )
    )

    fun guess(text: String): String {
        for ((category, keywords) in RULES) {
            if (keywords.any { text.contains(it, ignoreCase = true) }) return category
        }
        return "其他"
    }
}