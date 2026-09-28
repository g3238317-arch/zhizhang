package com.example.myledger.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * 账单的"操作说明书"。
 * 想对数据库干啥，就在这里写一条。SQL 写错了编译期就会报，不用等运行时。
 */
@Dao
interface ExpenseDao {

    @Insert
    suspend fun insert(expense: Expense)

    @Delete
    suspend fun delete(expense: Expense)

    /** 改一笔（按 id 找到它，整条覆盖） */
    @Update
    suspend fun update(expense: Expense)

    /** 全部账单，新的在上面 */
    @Query("SELECT * FROM expenses ORDER BY timestamp DESC")
    fun getAll(): LiveData<List<Expense>>

    /** 某个时间点之后一共花了多少（用来算本月支出） */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE timestamp >= :from")
    fun sumSince(from: Long): LiveData<Double>

    /** 指定时间段内，每个分类各花了多少（多的排前面） */
    @Query(
        "SELECT category, SUM(amount) AS total FROM expenses " +
            "WHERE timestamp >= :from AND timestamp < :to " +
            "GROUP BY category ORDER BY total DESC"
    )
    suspend fun sumByCategory(from: Long, to: Long): List<CategorySum>
}