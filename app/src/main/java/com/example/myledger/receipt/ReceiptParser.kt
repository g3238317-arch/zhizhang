package com.example.myledger.receipt

import com.example.myledger.data.AutoCategory

/**
 * OCR 认完字之后，从一堆文字里抠出"这笔账该怎么记"。
 *
 * 它是猜，不是算 —— 小票格式千奇百怪，谁也不敢保证全对。
 * 所以猜完一律进确认框给人过目，猜错了改一下就是。
 *
 * 踩过的坑（2026-09-29 真机翻车记录）：
 *   海底捞小票写"实收金额 199.45"，关键词和数字之间隔了"金额"俩字，
 *   老正则扑空后掉进"全文最大数"兜底，把日期里的年份 2024 当成了钱。
 *   于是：关键词和数字之间允许夹字；兜底前先抹掉日期时间；年份单独防一道。
 */
data class ReceiptDraft(
    val amount: Double?,   // 没认出来就是 null，确认框里留空让人填
    val note: String,      // 备注，形如"小票 · 某某超市"
    val category: String,  // 分类，交给 AutoCategory 按关键词猜
    val rawText: String    // OCR 原文，确认框里摆出来给人对账
)

object ReceiptParser {

    /** 金额上下限：超出这个范围的"金额"基本不可能是这顿饭的钱 */
    private const val MIN_AMOUNT = 0.01
    private const val MAX_AMOUNT = 99999.99

    /**
     * 关键词锚定：合计/实付 后面的数字，可信度最高。
     * 关键词和数字之间允许夹最多 4 个非数字字符 —— "金额"、冒号、空格之流，
     * 但禁止跨行（排除换行符），免得 grabbing 到下一行的无关数字。
     */
    private val ANCHORED = Regex(
        // 一级关键词：这些后面的数字就是"真付的钱"，优先级最高。
        // "台计"是 OCR 把"合计"看走眼的常见错字，一并收编；"成交价"是电商截图的说法。
        // 坑史：结尾曾经写 \s*，而 \s 吃换行 —— 前门禁了换行、后门大开，
        // "消费台计:" 隔着换行抓走了下一行"1大点"的 1。现在只许空格和 Tab。
        """(?:实付|实收|应付|应收|成交价|付款)[^0-9¥￥\n]{0,4}[¥￥]?[ \t]*([0-9]+(?:\.[0-9]{1,2})?)"""
    )

    /** 二级关键词：合计/总价之流。只有当一级词全缺席时，它们才说话 */
    private val ANCHORED_T2 = Regex(
        """(?:合计|台计|总计|总额|总价|消费|支付)[^0-9¥￥\n]{0,4}[¥￥]?[ \t]*([0-9]+(?:\.[0-9]{1,2})?)"""
    )

    /** 最后兜底：任何像钱的数字 */
    private val BARE = Regex("""[0-9]+(?:\.[0-9]{1,2})?""")

    /** 带可选¥前缀的数字：组1=符号（钱的制服），组2=数字本体 */
    private val MONEYISH = Regex("""([¥￥]?)[ \t]*([0-9]+(?:\.[0-9]{1,2})?)""")

    /** 日期和时间：兜底找金额前先把这些抹掉，免得年份冒充钱 */
    private val DATE_TIME = Regex(
        """[0-9]{4}\s*[-/年.]\s*[0-9]{1,2}\s*[-/月.]\s*[0-9]{1,2}|[0-9]{1,2}\s*:\s*[0-9]{2}(?::[0-9]{2})?"""
    )

    /** 标签行里的这些词 = 这行不是店名 */
    private val NAME_BLOCK_WORDS = listOf(
        "合计", "总计", "实付", "实收", "应付", "应收", "总额", "总价",
        "付款", "支付", "优惠", "折扣", "找零", "现金", "余额", "单号",
        "订单", "地址", "电话", "日期", "时间", "台号", "收银", "服务",
        "客数", "结账", "账单", "开票", "发票", "菜品", "规格", "数量",
        "微信", "支付宝", "银联", "云闪付", "谢谢", "欢迎",
        // 电商/二手平台截图的状态行，不是店名
        "交易", "评价", "感谢", "成功", "编号", "复制", "保障", "发货",
        "退款", "推荐", "删除", "联系", "卖家", "查看", "钱款"
    )

    /** 地址碎片的特征字：带这些字又带数字的行，是地址不是店名 */
    private val ADDRESS_MARKS = listOf("层", "栋", "幢", "室", "路", "街", "号", "/")

    fun parse(rawText: String): ReceiptDraft {
        // 先修 OCR 的手抖："346. 00"→"346.00"、"6, 00"→"6.00"。
        // 小数点/逗号后面跟空格的，一律把空格吃掉（真千分位逗号后面不跟空格，误伤不到）
        val repaired = rawText.replace(Regex("([0-9])[.,]\\s+([0-9]{1,2})(?![0-9])"), "$1.$2")
        // 表格小票会把"实收金额"和"199.45"读成两行，先接龙回一行再找钱
        val joined = joinLabelLines(repaired)
        // 冒号、连续空格压成单个空格："实收金额:   199.45" → "实收金额 199.45"
        // 括号注释整段剥掉："成交价（已到达卖家账户）¥6.58"→"成交价 ¥6.58"，
        // "商品总价（已优惠¥1.41）"里的假钱也随括号一起蒸发。
        // 但纯金额的括号要留："合计（¥25.00）"剥了就把真钱剥没了。
        // 括号内容禁止跨行：OCR 常读出一个没闭合的孤括号（"(O郭帅豪153…"），
        // 允许跨行的话它会一路吞到下一个右括号，把中间的"应付 9099"整行吃掉。
        // 不闭合的括号匹配不上，原样留着，无害。
        val flat = joined.replace(Regex("[ \t:：]+"), " ")
            .replace(Regex("[（(](?![¥￥]?[ \t]*[0-9][0-9.,]*[ \t]*[）)])[^）)\n]*[）)]"), " ")
        return ReceiptDraft(
            amount = pickAmount(flat),
            note = pickNote(rawText),
            category = AutoCategory.guess(rawText),
            rawText = rawText
        )
    }

    /** 纯数字行（可带小数点/逗号）：接龙只认这种，"1大点"这种数量行不配 */
    private val PURE_NUMBER_LINE = Regex("^[0-9][0-9.,]*$")

    /**
     * 接龙：一行里没有数字（纯标签），下一行是【纯数字行】或¥开头，才并成一行。
     * "实收金额" + "199.45" → "实收金额 199.45"，关键词锚定才接得上。
     * 上一版的教训：只看"开头是数字"太松，"消费合计:"接上了路过的"1大点"，
     * 关键词顺势抓走那个 1，记出一块钱的海鲜局。
     */
    private fun joinLabelLines(text: String): String {
        val src = text.lines()
        val out = ArrayList<String>(src.size)
        var i = 0
        while (i < src.size) {
            var line = src[i]
            val next = src.getOrNull(i + 1)?.trim().orEmpty()
            val nextHead = next.firstOrNull()
            if (line.isNotBlank() &&
                !line.contains(Regex("[0-9¥￥]")) &&
                (PURE_NUMBER_LINE.matches(next) || nextHead == '¥' || nextHead == '￥')
            ) {
                line = "$line $next"
                i++
            }
            out.add(line)
            i++
        }
        return out.joinToString("\n")
    }

    /** 这些行上的数字是"打折前的旧账"，不是真付的钱，兜底时一票否决 */
    // 注意："原价"不在黑名单里 —— 超市小票那仗，真总额 53.54 只在
    // "|53.54 找零"和"订单原价:53.54"两行露面，把原价行禁投它就只剩一票、
    // 被重复的单品价（5.17×2）压死。原价行参与投票，真总额才能凑够票。
    // "原单"（海底捞的"原单金额"）才是纯干扰，继续禁。
    private val AMOUNT_EXCLUDE_WORDS = listOf("原单", "折扣", "优惠", "减免", "定金", "找零", "赠送", "贈送")

    /** 一行里数字个数达到这个数 = 菜品/商品表格行，行里的数字不许投票 */
    private const val TABLE_LINE_TOKENS = 3

    /**
     * 两级找金额，可信度从高到低：
     *   ① 关键词锚定，取最后一个（"合计/实收/成交价"通常压轴出场）
     *   ② 票数投票：全文所有带小数的数字，谁出现次数多谁是真总额。
     *      真付的钱会在"实收/成交价/查看钱款"里反复露面，
     *      标价、原单、单品价都只露一次脸 —— 数人头比比大小聪明。
     *      （曾经的"¥档取最大"在闲鱼截图上抓了标价 7.99，已废除）
     */
    private fun pickAmount(text: String): Double? {
        // 一级词优先：拼多多那票"应付 9099"是真钱、"合计补贴价 ¥9699"是标价，
        // 曾经"取最后一个"看 OCR 心情，现在按关键词辈分来
        ANCHORED.findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it in MIN_AMOUNT..MAX_AMOUNT }
            .lastOrNull()?.let { return it }

        ANCHORED_T2.findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it in MIN_AMOUNT..MAX_AMOUNT }
            .lastOrNull()?.let { return it }

        // ② 投票：所有带小数的数字（¥ 后面的也算，OCR 把 ¥ 读成 Y 也拦不住 BARE）。
        // 真付的钱会在"成交价/商品总价/查看钱款"里反复露面，标价只露一次 ——
        // 闲鱼截图那仗：6.58 三票，7.99 一票。曾经的"¥档取最大"就是抓了标价。
        val votes = linkedMapOf<Double, Int>()
        for (line in text.lines()) {
            val cleaned = line.replace(DATE_TIME, " ")
            // 表格行禁投：超市小票的单价"12.80/12.80"每行重复，三十票淹死了真总额。
            // 真总额住在只有一两个数字的汇总行里，表格行（数量+单价+金额≥3个数）闭嘴。
            if (BARE.findAll(cleaned).count() >= TABLE_LINE_TOKENS) continue
            // 加权投票：带文字标签的行（"折后实收"、"订单原价"）一票顶两票，
            // 光数字行（OCR 把金额栏倒出来的"3.90"）只算一票。
            // 真总额永远有标签傍身，金额栏永远是光杆 —— 权重就是它们的身份差。
            val weight = if (cleaned.contains(Regex("[一-龥A-Za-z]"))) 2 else 1
            voteNumbersFrom(cleaned)
                .filter { it in MIN_AMOUNT..MAX_AMOUNT }
                .forEach { votes[it] = (votes[it] ?: 0) + weight }
        }
        // 票多者赢；平票时大的赢（真总额就算只露一次脸，也通常是全场最大）
        return votes.entries
            .sortedWith(compareByDescending<Map.Entry<Double, Int>> { it.value }.thenByDescending { it.key })
            .firstOrNull()?.key
    }

    /**
     * 从一行里提取"有资格投票的数字"。
     * 入场规则：带小数点（金额相），或者穿着¥符号（钱的制服）；
     * 光杆无符号整数 = 数量/台号/年份，继续门外站着；
     * 但 ¥9699 这种"被 OCR 吃掉小数点的整数金额"穿着制服，放行。
     * 回归教训：上一版只认小数点，拼多多那张票金额全是光杆整数，
     * 投票箱空了，直接"没认出金额"。
     *
     * 黑名单标签精准处刑："|53.54 找零:0.00" 这行里，
     * "找零"只处刑属于它自己的 0.00，前面的 53.54 照常投票 ——
     * 老版本整行处刑，把无辜的真总额一起带走了。
     */
    private fun voteNumbersFrom(line: String): List<Double> {
        val matches = MONEYISH.findAll(line)
            .filter { it.groupValues[2].contains('.') || it.groupValues[1].isNotEmpty() }
            .toList()
        val word = AMOUNT_EXCLUDE_WORDS.firstOrNull { line.contains(it) }
        if (word == null) return matches.mapNotNull { it.groupValues[2].toDoubleOrNull() }
        val cut = line.indexOf(word) + word.length
        val survivors = matches.filter { it.range.first < cut } +
            matches.filter { it.range.first >= cut }.drop(1)
        return survivors.mapNotNull { it.groupValues[2].toDoubleOrNull() }
    }

    /**
     * 店名 = 开头几行里第一行"像店名"的短文字。
     * 只在开头找：店名都在抬头，跑到菜名堆里捞只会捞出"清水锅"。
     * 标签行（"收银员: 张煌林"）按冒号前的词判死刑、冒号后的值才有资格候选。
     * 找不到就老实写"小票记账" —— 照片顶部糊掉的小票，神仙也猜不出店名。
     */
    private fun pickNote(text: String): String {
        val merchant = text.lineSequence()
            .map { it.trim() }
            .take(6)
            .filter { line -> line.isNotEmpty() && NAME_BLOCK_WORDS.none { line.contains(it) } }
            .map { line ->
                // "商户全称: 星巴克" → 只要冒号后面的值
                listOf(':', '：').filter { line.contains(it) }
                    .fold(line) { acc, c -> acc.substringAfterLast(c) }
                    // "【充自己号】夸克网盘" → 剥掉标签前缀，留"夸克网盘"
                    .replace(Regex("^【[^】]*】\\s*"), "")
                    .trim()
            }
            .firstOrNull { line ->
                // 20 字上限：容得下"皓景海鲜餐厅不踩雷不宰客"这种带口号的店名；
                // 更长的基本是地址整行，交给地址特征字和行数限制去拦
                line.length in 2..20 &&
                    !line.startsWith("(") && !line.startsWith("（") &&
                    !line.first().isDigit() &&
                    line.any { it in '一'..'龥' } &&
                    // "L2层33号/室商铺"这种地址碎片：特征字+数字，毙掉
                    !(line.any { it.isDigit() } && ADDRESS_MARKS.any { line.contains(it) })
            }
        return if (merchant == null) "小票记账" else "小票 · $merchant"
    }
}
