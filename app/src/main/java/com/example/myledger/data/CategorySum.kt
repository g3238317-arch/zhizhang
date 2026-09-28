package com.example.myledger.data

/** 统计用：某个分类一共花了多少。不是数据库表，只是查询结果的临时载体。 */
data class CategorySum(
    val category: String,
    val total: Double
)