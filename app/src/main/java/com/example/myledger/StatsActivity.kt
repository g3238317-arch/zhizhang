package com.example.myledger

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.myledger.data.AppDatabase
import com.example.myledger.data.Categories
import com.example.myledger.data.CategorySum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * 统计页：上面一个环形图看占比，下面一张清单看具体数字。
 *
 * 环形图是 DonutChartView 自己画的，横条那一版撤了 ——
 * 饼图和横条说的是同一件事（占比），留着就是重复。
 */
class StatsActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var rowContainer: LinearLayout
    private lateinit var monthText: TextView
    private lateinit var totalText: TextView
    private lateinit var emptyText: TextView
    private lateinit var donutChart: DonutChartView

    /** 当前在看哪个月 */
    private val shown = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        db = AppDatabase.get(this)
        rowContainer = findViewById(R.id.rowContainer)
        monthText = findViewById(R.id.monthText)
        totalText = findViewById(R.id.statsTotalText)
        emptyText = findViewById(R.id.emptyText)
        donutChart = findViewById(R.id.donutChart)
        donutChart.emptyRingColor = ContextCompat.getColor(this, R.color.divider)

        findViewById<View>(R.id.prevMonth).setOnClickListener {
            shown.add(Calendar.MONTH, -1)
            load()
        }
        findViewById<View>(R.id.nextMonth).setOnClickListener {
            shown.add(Calendar.MONTH, 1)
            load()
        }

        load()
    }

    private fun load() {
        monthText.text = String.format(
            Locale.CHINA, "%d年%d月",
            shown.get(Calendar.YEAR), shown.get(Calendar.MONTH) + 1
        )

        lifecycleScope.launch {
            val sums = withContext(Dispatchers.IO) {
                db.expenseDao().sumByCategory(startOfMonth(), startOfNextMonth())
            }
            render(sums)
        }
    }

    private fun render(sums: List<CategorySum>) {
        rowContainer.removeAllViews()

        val total = sums.map { it.total }.sum()
        totalText.text = String.format(Locale.CHINA, "¥ %.2f", total)

        // 环形图：一个分类一块，颜色跟下面的色点一一对应
        donutChart.setSlices(
            sums.map {
                DonutChartView.Slice(
                    it.total.toFloat(),
                    Color.parseColor(Categories.colorOf(it.category))
                )
            }
        )

        emptyText.visibility = if (sums.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        for (item in sums) {
            val row = inflater.inflate(R.layout.item_stat_row, rowContainer, false)

            row.findViewById<View>(R.id.statDot).backgroundTintList =
                ColorStateList.valueOf(Color.parseColor(Categories.colorOf(item.category)))

            row.findViewById<TextView>(R.id.statCategory).text = item.category
            row.findViewById<TextView>(R.id.statAmount).text =
                String.format(Locale.CHINA, "¥ %.2f", item.total)
            row.findViewById<TextView>(R.id.statPercent).text =
                if (total > 0) String.format(Locale.CHINA, "%.0f%%", item.total / total * 100) else ""

            rowContainer.addView(row)
        }
    }

    private fun startOfMonth(): Long {
        val cal = shown.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun startOfNextMonth(): Long {
        val cal = shown.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MONTH, 1)
        return cal.timeInMillis
    }
}