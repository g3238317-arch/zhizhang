package com.example.myledger.receipt

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File

/**
 * 本机离线 OCR：ML Kit bundled 中文识别。
 *
 * 模型打包在 APK 里，识别全程不联网 —— 知账"不上传"的人设不能崩。
 * 首次调用要加载模型，可能慢几秒，所以按钮上会有"识别中…"顶着。
 */
object ReceiptOcr {

    /**
     * 认一张图，返回全文；认不出来或出错返回 null。
     *
     * 注意：Tasks.await 会阻塞当前线程，**只能在 IO 线程调**，
     * 调用方一律包在 withContext(Dispatchers.IO) 里。
     * fromFilePath 会自己按 EXIF 把图转正，不用咱操心。
     */
    fun recognize(context: Context, file: File): String? = runCatching {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            Tasks.await(recognizer.process(image)).text
        } finally {
            recognizer.close()
        }
    }.getOrNull()
}
