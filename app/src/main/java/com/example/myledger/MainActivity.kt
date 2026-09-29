package com.example.myledger

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myledger.data.AppDatabase
import com.example.myledger.data.Categories
import com.example.myledger.data.Expense
import com.example.myledger.notify.PaymentNotificationListener
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class AutoStatus { OK, CHECKING, NO_PERMISSION, DISCONNECTED }

    private companion object {
        /** 体检间隔 */
        const val CHECK_INTERVAL_MS = 2000L

        /** 最多查几轮。6 × 2 秒 = 给系统 12 秒时间去绑服务 */
        const val MAX_CHECKS = 6

        /** 等到第几轮还不行，才去敲系统请求重绑 */
        const val REBIND_AT_CHECK = 3
    }

    private lateinit var db: AppDatabase
    private lateinit var adapter: ExpenseAdapter

    private lateinit var amountInput: EditText
    private lateinit var noteInput: EditText
    private lateinit var categoryGroup: ChipGroup
    private lateinit var totalText: TextView

    private lateinit var autoStatus: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    /** 震动马达。老手机可能没有，拿不到就让它一直 null，震的时候跳过 */
    private var vibrator: Vibrator? = null

    /** 这一轮体检查到第几次了 */
    private var checkCount = 0

    /** 记住"哪个分类按钮"对应"哪个分类名" */
    private val chipIdToCategory = mutableMapOf<Int, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.get(this)

        // 老写法拿马达：compileSdk 30 里没有 Android 12 的 VibratorManager，
        // 这条 deprecated 的路从 API 1 用到今天都通，够咱用
        vibrator = @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        amountInput = findViewById(R.id.amountInput)
        noteInput = findViewById(R.id.noteInput)
        categoryGroup = findViewById(R.id.categoryGroup)
        totalText = findViewById(R.id.totalText)
        autoStatus = findViewById(R.id.autoStatus)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)

        buildCategoryChips()

        adapter = ExpenseAdapter { expense -> showEditDialog(expense) }
        val listView = findViewById<RecyclerView>(R.id.expenseList)
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        findViewById<Button>(R.id.saveButton).setOnClickListener { saveExpense() }

        findViewById<Button>(R.id.debugButton).setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
        findViewById<View>(R.id.statsButton).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        observeData()
    }

    override fun onResume() {
        super.onResume()
        checkCount = 0
        checkAutoStatus()
    }

    // ==================== 自动记账的"体检" ====================

    /**
     * 体检。核心是【耐心】。
     *
     * 系统重新绑定监听服务是异步的，OPPO 上慢起来要十几秒。
     * 之前我只等 3.3 秒就下结论，结果它还在连我就喊"断开了"，纯属误报。
     * 而且我还在它没连好的时候急着 requestRebind 去踹一脚，雪上加霜。
     *
     * 现在：
     *   每 2 秒看一次，最多看 6 次（给足 12 秒）
     *   等到第 3 次（约 4 秒）还不行，才去敲系统请求重绑
     *   一直连上就停，不折腾
     */
    private fun checkAutoStatus() {
        if (isFinishing || isDestroyed) return

        val granted = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        if (!granted) {
            showStatus(AutoStatus.NO_PERMISSION)
            return
        }

        if (PaymentNotificationListener.connected) {
            showStatus(AutoStatus.OK)
            return
        }

        checkCount++

        if (checkCount >= MAX_CHECKS) {
            showStatus(AutoStatus.DISCONNECTED)
            return
        }

        showStatus(AutoStatus.CHECKING)
        if (checkCount == REBIND_AT_CHECK) requestRebindQuietly()
        autoStatus.postDelayed({ checkAutoStatus() }, CHECK_INTERVAL_MS)
    }

    private fun showStatus(status: AutoStatus) {
        when (status) {
            AutoStatus.OK -> {
                statusText.text = "自动记账运行中"
                setStatusLook(R.color.ok, R.color.text_tertiary, clickable = false)
                autoStatus.setOnClickListener(null)
            }

            AutoStatus.CHECKING -> {
                statusText.text = "正在连接自动记账…"
                setStatusLook(R.color.text_tertiary, R.color.text_tertiary, clickable = false)
                autoStatus.setOnClickListener(null)
            }

            AutoStatus.NO_PERMISSION -> {
                statusText.text = "自动记账还没开启 · 点这里去打开「通知使用权」"
                setStatusLook(R.color.accent, R.color.accent, clickable = true)
                autoStatus.setOnClickListener { openNotificationAccess() }
            }

            AutoStatus.DISCONNECTED -> {
                statusText.text = getString(R.string.auto_disconnected, lastConnectedText())
                setStatusLook(R.color.accent, R.color.accent, clickable = true)
                autoStatus.setOnClickListener { fixDisconnection() }
            }
        }
    }

    /** "上次连接 09-28 21:05"，从没连上过就说"从未" */
    private fun lastConnectedText(): String {
        val last = PaymentNotificationListener.lastConnectedAt(this)
        if (last <= 0L) return "从未连接过"
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(last))
        return "上次 $time"
    }

    private fun setStatusLook(dotColorRes: Int, textColorRes: Int, clickable: Boolean) {
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(dotColorRes))
        statusText.setTextColor(getColor(textColorRes))
        autoStatus.setBackgroundResource(if (clickable) R.drawable.bg_hint else 0)
        autoStatus.isClickable = clickable
        autoStatus.isFocusable = clickable
    }

    private fun openNotificationAccess() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure {
            Toast.makeText(this, "这台手机找不到那个设置页，去设置里搜「通知使用权」", Toast.LENGTH_LONG).show()
        }
    }

    /** 礼貌地敲一下系统：绑定好像掉了，麻烦重新拉一下（不保证有用） */
    private fun requestRebindQuietly() {
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(this, PaymentNotificationListener::class.java)
            )
        }
    }

    /**
     * 用户点"重连"时怎么办。
     *
     * 实测下来（真机验证过）：
     *   requestRebind() 这个 API 在国产系统上不太靠得住；
     *   但【打开"通知使用权"这个设置页】几乎必然会触发系统重新绑定。
     *
     * 所以这里先敲一下 API（成本为零），然后直接跳设置页 —— 那才是真管用的。
     */
    private fun fixDisconnection() {
        requestRebindQuietly()
        Toast.makeText(
            this,
            "进这个页面等几秒就会自动重连；要是还没好，把知账的开关关掉再打开",
            Toast.LENGTH_LONG
        ).show()
        openNotificationAccess()
        checkCount = 0
        showStatus(AutoStatus.CHECKING)
        autoStatus.postDelayed({ checkAutoStatus() }, CHECK_INTERVAL_MS)
    }

    // ==================== 分类按钮 ====================

    /** 按分类清单，动态生成一排按钮。想加分类只要改 Categories.ALL */
    private fun buildCategoryChips() {
        Categories.ALL.forEachIndexed { index, name ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = name
                isCheckable = true
                isClickable = true
            }
            styleChip(chip)
            chipIdToCategory[chip.id] = name
            categoryGroup.addView(chip)
            if (index == 0) chip.isChecked = true
        }
    }

    /**
     * 分类按钮的两种样子：
     *   选中 = 暖橘实心 + 白字（全 App 只有这一处用主色填满）
     *   未选 = 浅暖灰底 + 灰字
     * 圆角统一 14dp，跟输入框、按钮对齐，不许各写各的。
     */
    private fun styleChip(chip: Chip) {
        // chipCornerRadius 被官方废弃了，新写法是改 shapeAppearanceModel 里的圆角
        chip.shapeAppearanceModel = chip.shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(resources.getDimension(R.dimen.chip_corner_radius))
            .build()
        chip.chipStrokeWidth = 0f
        chip.isCheckedIconVisible = false
        chip.setTextSize(14f)
        chip.setEnsureMinTouchTargetSize(false)

        val checked = intArrayOf(android.R.attr.state_checked)
        val normal = intArrayOf()
        val states = arrayOf(checked, normal)

        chip.chipBackgroundColor = ColorStateList(
            states,
            intArrayOf(getColor(R.color.accent), getColor(R.color.input_bg))
        )
        chip.setTextColor(
            ColorStateList(
                states,
                intArrayOf(Color.WHITE, getColor(R.color.text_secondary))
            )
        )
    }

    // ==================== 记账 ====================

    /** 数据库一变，界面自己就刷新，不用手动通知 */
    private fun observeData() {
        db.expenseDao().getAll().observe(this) { list ->
            adapter.setData(list)
        }
        db.expenseDao().sumSince(startOfMonth()).observe(this) { sum ->
            totalText.text = String.format(Locale.CHINA, "¥ %.2f", sum ?: 0.0)
        }
    }

    /**
     * 记账成功的"手感"：震 70 毫秒、振幅拉满 255。
     * VibrationEffect 是 API 26 的，咱最低就支持 26，直接用不用判断版本。
     * 注意：振幅有一半看硬件脸色——老马达只认开关不认振幅，
     * 那种机器上真正管用的是时长，所以两个旋钮一起拧。
     * 马达不存在、或被系统静音策略拦了，都静默跳过——震不动不该影响记账。
     */
    private fun buzz() {
        val v = vibrator ?: return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(70, 255))
        }
    }

    private fun saveExpense() {
        val amount = amountInput.text.toString().trim().toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(this, "先输个金额吧", Toast.LENGTH_SHORT).show()
            return
        }

        val category = chipIdToCategory[categoryGroup.checkedChipId]
            ?: Categories.ALL.first()

        val expense = Expense(
            amount = amount,
            category = category,
            note = noteInput.text.toString().trim(),
            timestamp = System.currentTimeMillis()
        )

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.expenseDao().insert(expense) }
            amountInput.setText("")
            noteInput.setText("")
            buzz()
            Toast.makeText(this@MainActivity, "记下了 ✓", Toast.LENGTH_SHORT).show()
        }
    }

    /** 点某条账单 → 弹面板：改分类 或 删掉 */
    private fun showEditDialog(expense: Expense) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_expense, null)

        view.findViewById<TextView>(R.id.dialogAmount).text =
            String.format(Locale.CHINA, "-¥%.2f", expense.amount)

        val time = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
            .format(Date(expense.timestamp))
        view.findViewById<TextView>(R.id.dialogInfo).text =
            if (expense.note.isEmpty()) time else "$time · ${expense.note}"

        // 动态生成分类按钮，当前分类预先选中
        val group = view.findViewById<ChipGroup>(R.id.dialogCategoryGroup)
        val idToCategory = mutableMapOf<Int, String>()
        Categories.ALL.forEach { name ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = name
                isCheckable = true
                isClickable = true
                isChecked = (name == expense.category)
            }
            styleChip(chip)
            idToCategory[chip.id] = name
            group.addView(chip)
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()

        view.findViewById<Button>(R.id.deleteButton).setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { db.expenseDao().delete(expense) }
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "删掉了", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<Button>(R.id.saveButton).setOnClickListener {
            val newCategory = idToCategory[group.checkedChipId] ?: expense.category
            if (newCategory == expense.category) {
                dialog.dismiss()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    db.expenseDao().update(expense.copy(category = newCategory))
                }
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "改好了", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    /** 本月 1 号 0 点那一刻的时间戳 */
    private fun startOfMonth(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}