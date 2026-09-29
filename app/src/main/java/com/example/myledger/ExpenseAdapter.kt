package com.example.myledger

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myledger.data.Categories
import com.example.myledger.data.Expense
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 列表的"翻译官"：
 * 把一条条 Expense 数据，翻译成 item_expense.xml 那个样子，一行行摆上去。
 *
 * 点某一行会回调 onItemClick，交给 MainActivity 去弹修改面板。
 */
class ExpenseAdapter(
    private val onItemClick: (Expense) -> Unit
) : RecyclerView.Adapter<ExpenseAdapter.Holder>() {

    private var items: List<Expense> = emptyList()

    fun setData(list: List<Expense>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_expense, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // 每个 Holder 各持一个：SimpleDateFormat 不是线程安全的，独占更稳
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

        private val dot: View = itemView.findViewById(R.id.itemDot)
        private val categoryText: TextView = itemView.findViewById(R.id.itemCategory)
        private val noteText: TextView = itemView.findViewById(R.id.itemNote)
        private val amountText: TextView = itemView.findViewById(R.id.itemAmount)
        private val timeText: TextView = itemView.findViewById(R.id.itemTime)
        private val receiptIcon: ImageView = itemView.findViewById(R.id.itemReceipt)

        fun bind(expense: Expense) {
            categoryText.text = expense.category
            noteText.text = if (expense.note.isEmpty()) "—" else expense.note
            amountText.text = String.format(Locale.CHINA, "-¥%.2f", expense.amount)
            timeText.text = dateFormat.format(Date(expense.timestamp))

            // 行是回收复用的：有小票才亮角标，没有必须按回去，不然会串到别的行上
            receiptIcon.visibility =
                if (expense.imagePath != null) View.VISIBLE else View.GONE

            // 小圆点按分类染色
            dot.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor(Categories.colorOf(expense.category)))

            itemView.setOnClickListener { onItemClick(expense) }
        }
    }
}