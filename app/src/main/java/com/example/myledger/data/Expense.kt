package com.example.myledger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一笔账单。
 * 每加一个字段，就是给账本多留一栏。
 */
@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,      // 金额
    val category: String,    // 分类，比如"餐饮"
    val note: String,        // 备注
    val timestamp: Long,     // 记录时间（毫秒）
    val imagePath: String? = null  // 小票图在 filesDir/receipts/ 下的文件名；null = 没配图
)