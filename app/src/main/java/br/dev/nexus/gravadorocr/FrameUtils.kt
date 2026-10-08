package br.dev.nexus.gravadorocr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import br.dev.nexus.gravadorocr.core.Box
import br.dev.nexus.gravadorocr.core.MatchKind
import br.dev.nexus.gravadorocr.core.MatchResult
import br.dev.nexus.gravadorocr.core.OcrToken
import com.google.mlkit.vision.text.Text

object FrameUtils {

    /** Gira o quadro da câmera para ficar "em pé" (como a pessoa vê). */
    fun upright(src: Bitmap, rotationDegrees: Int): Bitmap {
        val r = ((rotationDegrees % 360) + 360) % 360
        if (r == 0) return src
        val m = Matrix().apply { postRotate(r.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Brilho médio (0..255), amostrando uma grade — serve para detectar câmera tapada. */
    fun meanLuma(b: Bitmap): Int {
        val stepX = maxOf(1, b.width / 32)
        val stepY = maxOf(1, b.height / 32)
        var sum = 0L
        var n = 0
        var y = stepY / 2
        while (y < b.height) {
            var x = stepX / 2
            while (x < b.width) {
                val c = b.getPixel(x, y)
                sum += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    private fun rect(b: Box, s: Float) = RectF(b.left * s, b.top * s, b.right * s, b.bottom * s)

    /** Imagem pequena para a tela: todo texto lido com contorno fino, achados em vermelho/amarelo. */
    fun monitor(src: Bitmap, tokens: List<OcrToken>, results: List<MatchResult>, maxWidth: Int = 480): Bitmap {
        val scale = if (src.width > maxWidth) maxWidth.toFloat() / src.width else 1f
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(src, w, h, true).let {
            if (it !== src && it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true)
        }
        val c = Canvas(out)
        val thin = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.argb(160, 120, 200, 255) }
        for (t in tokens) t.box?.let { c.drawRect(rect(it, scale), thin) }
        val hit = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(255, 64, 64) }
        val near = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.rgb(255, 200, 0) }
        for (r in results) {
            val p = if (r.kind == MatchKind.FULL) hit else near
            for (b in r.boxes) c.drawRect(rect(b, scale), p)
        }
        return out
    }

    /** Foto do momento da detecção, com a palavra marcada em vermelho e legenda. */
    fun annotate(src: Bitmap, result: MatchResult, caption: String): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val stroke = maxOf(4f, out.width / 160f)
        val hit = Paint().apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.rgb(255, 40, 40) }
        for (b in result.boxes) {
            val r = rect(b, 1f)
            r.inset(-stroke, -stroke)
            c.drawRect(r, hit)
        }
        val textSize = maxOf(22f, out.width / 28f)
        val bg = Paint().apply { color = Color.argb(190, 0, 0, 0) }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; this.textSize = textSize }
        c.drawRect(0f, 0f, out.width.toFloat(), textSize * 1.8f, bg)
        c.drawText(caption, textSize * 0.5f, textSize * 1.25f, tp)
        return out
    }
}

object OcrText {
    fun tokens(text: Text): List<OcrToken> {
        val out = ArrayList<OcrToken>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val r = el.boundingBox
                    out.add(OcrToken(el.text, r?.let { Box(it.left, it.top, it.right, it.bottom) }))
                }
            }
        }
        return out
    }
}

/** Grava fotos e textos nas pastas públicas (aparecem na Galeria / Arquivos). */
object MediaSaver {
    private const val TAG = "MediaSaver"
    const val VIDEO_DIR = "Movies/GravadorOCR"
    const val PICTURES_DIR = "Pictures/GravadorOCR"
    const val DOCS_DIR = "Download/GravadorOCR"

    fun saveJpeg(ctx: Context, bitmap: Bitmap, name: String): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, PICTURES_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao salvar foto", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun saveText(ctx: Context, fileName: String, mime: String, content: String): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DOCS_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao salvar texto", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
