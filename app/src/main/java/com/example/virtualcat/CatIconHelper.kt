package com.example.virtualcat // ←パッケージ名はプロジェクトに合わせてください

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

enum class CatPreset(val id: Int, val label: String, val bgColor: Int, val earColor: Int) {
    ORANGE(0, "茶トラ", Color.parseColor("#FFB74D"), Color.parseColor("#E65100")),
    BLACK(1, "黒猫", Color.parseColor("#424242"), Color.parseColor("#212121")),
    WHITE(2, "白猫", Color.parseColor("#F5F5F5"), Color.parseColor("#BDBDBD")),
    CALICO(3, "三毛猫", Color.parseColor("#FFE0B2"), Color.parseColor("#5D4037")),
    GRAY(4, "サバトラ", Color.parseColor("#B0BEC5"), Color.parseColor("#455A64"))
}

object CatIconHelper {
    private const val CUSTOM_IMAGE_FILE = "custom_cat_icon.png"

    // UriからExifの向きを補正したBitmapを読み込む（トリミング画面の表示用）
    fun loadOrientedBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val rotationDegrees = context.contentResolver.openInputStream(uri)?.use { exifStream ->
                val exif = ExifInterface(exifStream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f

            // メモリ不足を防ぐため長辺が1600px以内に収まるようサンプリングして読み込む
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }
            var sampleSize = 1
            while (maxOf(boundsOptions.outWidth, boundsOptions.outHeight) / sampleSize > 1600) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val original = context.contentResolver.openInputStream(uri)?.use { imageStream ->
                BitmapFactory.decodeStream(imageStream, null, decodeOptions)
            } ?: return null

            if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            } else {
                original
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ユーザーが調整したズーム倍率・移動位置を反映して256x256で保存する
    fun saveCroppedBitmap(
        context: Context,
        bitmap: Bitmap,
        userScale: Float,
        offsetX: Float,
        offsetY: Float,
        boxSizePx: Float
    ): Boolean {
        return try {
            val outputSize = 256
            val outBitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outBitmap)

            val baseScale = maxOf(
                boxSizePx / bitmap.width.toFloat(),
                boxSizePx / bitmap.height.toFloat()
            )
            val totalScale = baseScale * userScale
            val outRatio = outputSize.toFloat() / boxSizePx

            val matrix = Matrix().apply {
                postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
                postScale(totalScale * outRatio, totalScale * outRatio)
                postTranslate(outputSize / 2f + offsetX * outRatio, outputSize / 2f + offsetY * outRatio)
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bitmap, matrix, paint)

            val file = File(context.filesDir, CUSTOM_IMAGE_FILE)
            FileOutputStream(file).use { out ->
                outBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadCurrentCatBitmap(context: Context): Bitmap {
        val prefs = context.getSharedPreferences("cat_prefs", Context.MODE_PRIVATE)
        val iconType = prefs.getString("icon_type", "preset") ?: "preset"

        if (iconType == "custom") {
            val file = File(context.filesDir, CUSTOM_IMAGE_FILE)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) return bitmap
            }
        }

        val presetId = prefs.getInt("preset_id", 0)
        val preset = CatPreset.entries.find { it.id == presetId } ?: CatPreset.ORANGE
        return createPresetBitmap(preset)
    }

    fun createPresetBitmap(preset: CatPreset): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = preset.bgColor
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.color = preset.earColor
        val leftEar = Path().apply {
            moveTo(45f, 110f); lineTo(65f, 35f); lineTo(115f, 80f); close()
        }
        val rightEar = Path().apply {
            moveTo(211f, 110f); lineTo(191f, 35f); lineTo(141f, 80f); close()
        }
        canvas.drawPath(leftEar, paint)
        canvas.drawPath(rightEar, paint)

        paint.color = preset.bgColor
        canvas.drawCircle(128f, 145f, 75f, paint)

        if (preset == CatPreset.CALICO) {
            paint.color = Color.parseColor("#E65100")
            canvas.drawCircle(85f, 115f, 28f, paint)
        }

        paint.color = if (preset == CatPreset.BLACK) Color.parseColor("#FFD54F") else Color.parseColor("#3E2723")
        canvas.drawCircle(100f, 138f, 9f, paint)
        canvas.drawCircle(156f, 138f, 9f, paint)

        paint.color = Color.parseColor("#F48FB1")
        canvas.drawCircle(128f, 155f, 7f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = if (preset == CatPreset.BLACK) Color.LTGRAY else Color.DKGRAY
        canvas.drawLine(128f, 162f, 118f, 172f, paint)
        canvas.drawLine(128f, 162f, 138f, 172f, paint)
        canvas.drawLine(45f, 148f, 80f, 152f, paint)
        canvas.drawLine(45f, 165f, 80f, 162f, paint)
        canvas.drawLine(211f, 148f, 176f, 152f, paint)
        canvas.drawLine(211f, 165f, 176f, 162f, paint)

        return bitmap
    }
}