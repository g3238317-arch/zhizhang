package com.example.myledger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myledger.notify.NotificationLog

/**
 * 「通知侦察」：把抓到的原始通知原样显示出来。
 *
 * 这页面是给咱们调规则用的，不是给日常用的。
 * 等解析规则调准了，它就可以退休了。
 */
class DebugActivity : AppCompatActivity() {

    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        logText = findViewById(R.id.logText)

        findViewById<Button>(R.id.refreshButton).setOnClickListener { refresh() }

        findViewById<Button>(R.id.copyButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("通知日志", logText.text))
            Toast.makeText(this, "复制好了，去粘给我", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            NotificationLog.clear(this)
            refresh()
        }

        refresh()
    }

    private fun refresh() {
        val content = NotificationLog.readAll(this)
        logText.text = content.ifBlank {
            "还没抓到任何通知。\n\n去付一笔钱（买瓶水就行），回来点「刷新」。"
        }
    }
}