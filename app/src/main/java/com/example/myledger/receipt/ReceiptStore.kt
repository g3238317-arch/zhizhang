package com.example.myledger.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 小票图的"仓库"：相机临时落点、压缩存档、缩略图、清理，全在这儿。
 *
 * 为什么要压缩：相机原图一张动辄五六 MB，原样塞进去存储很快见底。
 * 长边缩到 1600px 以内、JPEG 质量 80 —— 看清字、够 OCR，就这两件事。
 *
 * 文件 IO 的写法照 notify/NotificationLog.kt 的家风：runCatching 兜底、.use{} 关流。
 */
object ReceiptStore {

    private const val DIR = "receipts"
    private const val MAX_EDGE = 1600
    private const val JPEG_QUALITY = 80

    /** FileProvider 的 authority，跟 Manifest 里登记的那个对暗号 */
    fun authority(context: Context): String = context.packageName + ".fileprovider"

    /** 正式存档目录：filesDir/receipts/ */
    fun receiptsDir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** 系统相机拍照的临时落点：cacheDir/receipts/cam_<时间戳>.jpg，确认后才搬进正式目录 */
    fun cameraTempFile(context: Context): File =
        File(File(context.cacheDir, DIR).apply { mkdirs() }, "cam_${System.currentTimeMillis()}.jpg")

    /** 文件 → 可对外授权的 Uri（相机往这儿写、看大图从这儿读） */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /** 相册选中的 content Uri → 压缩存档，返回正式文件；失败返回 null */
    fun importFromUri(context: Context, uri: Uri): File? =
        importStream(context) { context.contentResolver.openInputStream(uri) }

    /** 相机临时文件 → 压缩存档；失败返回 null */
    fun importFromFile(context: Context, src: File): File? =
        importStream(context) { FileInputStream(src) }

    /**
     * 压缩存档五步：量尺寸 → 算缩放 → 真解码 → 按 EXIF 转正 → 落盘。
     * 量尺寸在先、真解码在后，是为了让一万像素的全景图也撑不爆内存。
     */
    private fun importStream(context: Context, open: () -> InputStream?): File? = runCatching {
        // ① 旋转角：竖着拍的小票，存下来也得是正的
        val degrees = open()?.use { stream ->
            runCatching { ExifInterface(stream).rotationDegrees }.getOrDefault(0)
        } ?: 0

        // ② 第一遍解码：只量尺寸不装像素
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        // 缩放倍数必须是 2 的幂：长边超标的就一直对半砍
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2

        // ③ 第二遍解码：这次才真把像素读进来
        val raw = open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null

        // ④ 转正。老系统上 Bitmap 不是 Closeable，用不了 use()，老实手动回收
        val bitmap = if (degrees != 0) {
            val rotated = Bitmap.createBitmap(
                raw, 0, 0, raw.width, raw.height,
                Matrix().apply { postRotate(degrees.toFloat()) }, true
            )
            if (rotated !== raw) raw.recycle()
            rotated
        } else raw

        // ⑤ 落盘
        val target = File(receiptsDir(context), "receipt_${System.currentTimeMillis()}.jpg")
        try {
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
        target
    }.getOrNull()

    /**
     * 小尺寸解码给对话框当缩略图用 —— 160dp 的小框，犯不着把整图像素全读进来。
     * 失败返回 null，调用方让缩略图继续隐身就是。
     */
    fun loadThumbnail(file: File, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(file).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2

        FileInputStream(file).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** 文件名 → 真文件。存进数据库的就是文件名，全路径在这儿现拼 */
    fun fileFor(context: Context, name: String): File = File(receiptsDir(context), name)

    /**
     * OCR 黑匣子：每次识别的原文追加存一份到 receipts/ocr_debug.log。
     * 解析猜错了不用瞎猜现场，连上电脑 cat 这个文件就知道 OCR 到底读出了啥。
     * 文件超 1MB 就推倒重来，不让它无限长。
     */
    fun appendOcrLog(context: Context, text: String) {
        runCatching {
            val log = File(receiptsDir(context), "ocr_debug.log")
            if (log.length() > 1024 * 1024) log.delete()
            log.appendText("\n===== ${System.currentTimeMillis()} =====\n$text\n")
        }
    }

    /** 取消或识别失败时清现场。删失败也静默 —— 最坏就是留一张孤儿图，不碍事 */
    fun deleteQuietly(file: File?) {
        runCatching { file?.delete() }
    }
}
