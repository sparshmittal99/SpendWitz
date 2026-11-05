package com.spendwitz.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Gets a picked or captured file ready for upload. Phone camera photos are often 4–12 MB, over the server's
 * 6 MB per-file limit, so images are resized and re-encoded as JPEG of about 1 MB. A bill stays easily readable
 * at 2000 px, which is all the AI needs. PDFs and spreadsheets are passed through untouched.
 */
object UploadPreparer {

    /** Longest side of the uploaded photo, in pixels. */
    const val MAX_DIMENSION = 2000

    /** Aim for at most this many bytes per photo (the server accepts up to 6 MB). */
    const val TARGET_BYTES = 1_000_000

    private const val START_QUALITY = 85
    private const val MIN_QUALITY = 50
    private const val UPLOAD_DIR = "uploads"

    class Prepared(val uri: Uri, val originalBytes: Long, val finalBytes: Long, val compressed: Boolean)

    fun prepare(context: Context, source: Uri): Prepared {
        val name = displayName(context, source)
        val size = sizeOf(context, source)
        val unchanged = Prepared(source, size, size, false)
        if (!isCompressibleImage(context, source, name)) return unchanged
        return try {
            val file = compress(context, source, name, size) ?: return unchanged
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            Prepared(uri, size, file.length(), true)
        } catch (e: Exception) {
            unchanged
        } catch (e: OutOfMemoryError) {
            unchanged
        }
    }

    /**
     * Deletes every photo the app took or compressed. Called as soon as an upload has gone through (the next page
     * has loaded) and on app start and exit, so no bill photos are kept on the phone.
     */
    fun deleteAll(context: Context) {
        listOf(UPLOAD_DIR, "camera").forEach { dir ->
            val root = File(context.cacheDir, dir)
            root.walkBottomUp().filter { it != root }.forEach { it.delete() }
        }
    }

    private fun isCompressibleImage(context: Context, uri: Uri, name: String): Boolean {
        val type = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        // GIFs may be animated; leave them alone
        return type != null && type.startsWith("image/") && type != "image/gif"
    }

    /** Returns the compressed file, or null when the original is already small enough (or can't be decoded). */
    private fun compress(context: Context, source: Uri, name: String, originalSize: Long): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, source) { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null // e.g. HEIC on Android 8: the server reads it as is

        val orientation = readOrientation(context, source)
        val alreadySmall = originalSize in 1..TARGET_BYTES.toLong() && max(width, height) <= MAX_DIMENSION
        if (alreadySmall && orientation.isNormal) return null

        // Decode at roughly the target size straight away to keep memory low on 50 MP photos
        var sample = 1
        while (max(width, height) / (sample * 2) >= MAX_DIMENSION) sample *= 2
        val decoded = open(context, source) {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        var bitmap = fitAndRotate(decoded, MAX_DIMENSION, orientation)
        var bytes = encode(bitmap)
        var attempts = 0
        // Very detailed photos can stay big even at low quality: shrink further
        while (bytes.size > TARGET_BYTES && attempts < 3) {
            val smaller = (max(bitmap.width, bitmap.height) * 0.75f).roundToInt()
            bitmap = fitAndRotate(bitmap, smaller, Orientation.NORMAL)
            bytes = encode(bitmap)
            attempts++
        }
        bitmap.recycle()

        // A folder per photo keeps the original file name (shown on the page) without clashes
        val dir = File(File(context.cacheDir, UPLOAD_DIR), UUID.randomUUID().toString()).apply { mkdirs() }
        val base = name.substringBeforeLast('.').ifBlank { "photo" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        return File(dir, "$base.jpg").apply { writeBytes(bytes) }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        var quality = START_QUALITY
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= TARGET_BYTES || quality <= MIN_QUALITY) return out.toByteArray()
            quality -= 10
        }
    }

    /** Scales so the longest side is at most [maxSide], applies the EXIF rotation and puts transparency on white. */
    private fun fitAndRotate(source: Bitmap, maxSide: Int, orientation: Orientation): Bitmap {
        val scale = min(1f, maxSide.toFloat() / max(source.width, source.height))
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (orientation.flipped) postScale(-1f, 1f)
            if (orientation.degrees != 0) postRotate(orientation.degrees.toFloat())
        }
        var result = if (matrix.isIdentity) source
        else Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (result !== source) source.recycle()

        if (result.hasAlpha()) { // JPEG has no transparency: screenshots with alpha would turn black
            val opaque = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
            Canvas(opaque).apply {
                drawColor(Color.WHITE)
                drawBitmap(result, 0f, 0f, null)
            }
            result.recycle()
            result = opaque
        }
        return result
    }

    private class Orientation(val degrees: Int, val flipped: Boolean) {
        val isNormal get() = degrees == 0 && !flipped

        companion object {
            val NORMAL = Orientation(0, false)
        }
    }

    private fun readOrientation(context: Context, uri: Uri): Orientation = try {
        open(context, uri) { stream ->
            val exif = ExifInterface(stream)
            Orientation(exif.rotationDegrees, exif.isFlipped)
        } ?: Orientation.NORMAL
    } catch (e: Exception) {
        Orientation.NORMAL
    }

    private fun <T> open(context: Context, uri: Uri, block: (InputStream) -> T): T? =
        context.contentResolver.openInputStream(uri)?.use(block)

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "photo.jpg"
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: "photo.jpg"
    }

    private fun sizeOf(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: -1
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: -1
    }
}
