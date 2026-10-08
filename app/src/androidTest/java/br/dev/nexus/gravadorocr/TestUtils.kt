package br.dev.nexus.gravadorocr

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

object TestUtils {
    const val TAG = "GravadorOcrTest"

    fun permissions(): Array<String> {
        val p = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p.toTypedArray()
    }

    fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (cond()) return
            Thread.sleep(200)
        }
        fail("Tempo esgotado esperando: $what | status=${Live.status.value}")
    }

    /** Desenha texto preto em fundo branco, como um papel na frente da câmera. */
    fun renderText(lines: List<String>, w: Int = 1280, h: Int = 720, textSize: Float = 72f): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        var y = textSize * 1.6f
        for (l in lines) {
            c.drawText(l, 60f, y, p)
            y += textSize * 1.5f
        }
        return bmp
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap = FrameUtils.upright(src, degrees)

    /** Confere que o vídeo existe, tem tamanho, duração mínima e dá para decodificar um quadro. */
    fun assertValidVideo(ctx: Context, uriStr: String, minDurationMs: Long) {
        val uri = Uri.parse(uriStr)
        val size = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        Log.i(TAG, "Vídeo $uriStr tamanho=$size")
        assertTrue("arquivo de vídeo vazio ou pequeno demais ($size bytes)", size > 20_000)
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            val hasVideo = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            Log.i(TAG, "Vídeo duração=${dur}ms ${w}x$h hasVideo=$hasVideo")
            assertEquals("o arquivo não tem trilha de vídeo", "yes", hasVideo)
            assertTrue("duração ${dur}ms menor que ${minDurationMs}ms", dur >= minDurationMs)
            val frame = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            assertNotNull("não consegui decodificar um quadro do vídeo", frame)
        } finally {
            mmr.release()
        }
    }
}
