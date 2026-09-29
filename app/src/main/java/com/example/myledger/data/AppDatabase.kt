package com.example.myledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库本体。整个 App 只有这么一个，所以用单例。
 * 数据存在手机本地，不联网、不上传。
 */
@Database(entities = [Expense::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun expenseDao(): ExpenseDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2：给账单加小票图一栏。
         * 只加列、不动老数据 —— 用户手机里的账一条都不能丢，
         * 所以这里绝不用 fallbackToDestructiveMigration（那会清库）。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 注意：不能写 DEFAULT NULL。Room 升级后要拿数据库的默认值
                // 跟实体对账，多写这个 DEFAULT 会对不上、启动即崩。
                // 不写默认值时，SQLite 自动给老行填 NULL，效果一样。
                db.execSQL("ALTER TABLE expenses ADD COLUMN imagePath TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ledger.db"
                ).addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}