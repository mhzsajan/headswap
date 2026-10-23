package com.yegorkapomidorka.headswap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface

/**
 * H.264-энкодер (Surface input) и рисовалка заглушки моста (анимация, пока AA не подключился).
 * Кадры отдаются в Annex-B с 4-байтными стартовыми кодами; codec config приходит отдельно (onConfig).
 */
class VideoSource(
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val listener: Listener,
    /** Подпись под иконкой; null — системный monospace (тесты и снимки без контекста). */
    private val labelTypeface: Typeface? = null,
) {
    interface Listener {
        fun onConfig(csd: ByteArray)
        fun onFrame(data: ByteArray, key: Boolean)
        fun onError(msg: String, t: Throwable?)
    }

    @Volatile var framesRendered = 0L
        private set

    /** Включённая заглушка; текст статуса остаётся только в логе. null — видео AA. */
    @Volatile var banner: String? = null

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var drainThread: Thread? = null
    private var renderThread: Thread? = null
    var encoderName: String = "?"
        private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    @Synchronized
    fun start() {
        if (running) return
        val c = MediaCodec.createEncoderByType(MIME)
        encoderName = c.name
        var s: Surface? = null
        try {
            var fmt = format(withExtras = true)
            try {
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                AppLog.e("configure с profile/CBR не прошёл, пробую минимальный формат", e)
                c.reset()
                fmt = format(withExtras = false)
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            s = c.createInputSurface()
            c.start()
        } catch (e: Exception) {
            // codec ещё null: stop() вышел бы сразу, аппаратный энкодер освобождаем здесь
            try {
                s?.release()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
            throw e
        }
        surface = s
        codec = c
        running = true
        drainThread = Thread({ drainLoop(c) }, "video-enc-drain").also { it.start() }
        renderThread = Thread({ renderLoop() }, "video-render").also { it.start() }
    }

    private fun format(withExtras: Boolean): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (withExtras) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        return f
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            AppLog.e("requestKeyFrame", e)
        }
    }

    /** Битрейт на лету (воздух: политика битрейта ведёт и заглушку). false = кодека нет или параметр не принят. */
    fun setBitrate(bps: Int): Boolean {
        val c = codec ?: return false
        return try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
            true
        } catch (e: Exception) {
            AppLog.e("битрейт заглушки", e)
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!running && codec == null) return
        running = false
        renderThread?.join(1000)
        drainThread?.join(1000)
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        codec = null
        surface = null
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 50_000)
                when {
                    idx >= 0 -> {
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val arr = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(arr)
                            val data = AnnexB.normalize(arr)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                listener.onConfig(data)
                            } else {
                                listener.onFrame(data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            }
                        }
                        c.releaseOutputBuffer(idx, false)
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        AppLog.i("энкодер: формат вывода $encoderName: ${c.outputFormat}", ui = false)
                }
            }
        } catch (e: Exception) {
            if (running) listener.onError("drain энкодера", e)
        }
    }

    private fun renderLoop() {
        val s = surface ?: return
        val frameMs = 1000L / fps
        var next = SystemClock.elapsedRealtime()
        try {
            while (running) {
                // программный Canvas: lockHardwareCanvas на Surface энкодера валит media.codec на Xiaomi с vndk v30
                // (Redmi 9T/9S, POCO X3 Pro: SIGSEGV в FenceTime::Snapshot::getFlattenedSize, за ним abort нашего процесса)
                val canvas = s.lockCanvas(null)
                try {
                    draw(canvas)
                } finally {
                    s.unlockCanvasAndPost(canvas)
                }
                framesRendered++
                next += frameMs
                val d = next - SystemClock.elapsedRealtime()
                if (d > 0) Thread.sleep(d) else next = SystemClock.elapsedRealtime()
            }
        } catch (e: Exception) {
            if (running) listener.onError("рендер на Surface энкодера", e)
        }
    }

    /** Текущий кадр заглушки в Bitmap: с него транскодер начинает зум-растворение в картинку AA. Любая нить. */
    fun snapshot(): Bitmap? {
        if (banner == null) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawBanner(Canvas(bmp), width, height, framesRendered, Paint(fill))
        return bmp
    }

    /**
     * Заглушка без текста: знак Android Auto, стрелки конвертации, подпись «By Sajan» и полоса
     * прогресса. Цвета плоские: на CBR слабый энкодер рисует градиенты полосами. Размеры берутся
     * от высоты кадра, но снизу ограничены пикселями: на 800x480 тонкие штрихи иначе исчезают в
     * кодировании. Состояние только от t = n/fps, поэтому snapshot() с любой нити рисует тот же кадр.
     */
    private fun drawBanner(c: Canvas, width: Int, height: Int, n: Long, p: Paint) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(BG)

        val s = h / 1080f
        val t = n / fps.toFloat()

        // Знак Android Auto: белая «A», красная дорога. Координаты — из viewBox 24x24.
        val glyph = maxOf(84f, 190f * s)
        val g = glyph / 24f
        c.save()
        c.translate(w / 2f - 12f * g, h * 0.36f - 12f * g)
        c.scale(g, g)
        val a = Path()
        a.moveTo(12f, 0f)
        a.cubicTo(11.4f, 0f, 10.89f, 0.32f, 10.61f, 0.8f)
        a.lineTo(0.48f, 18.4f)
        a.quadTo(0.48f, 20.8f, 1.87f, 20.8f)
        a.lineTo(3.87f, 20.8f)
        a.lineTo(11.57f, 7.22f)
        a.lineTo(12f, 6.45f)
        a.lineTo(20.13f, 20.8f)
        a.lineTo(22.13f, 20.8f)
        a.quadTo(23.52f, 20.8f, 23.52f, 18.4f)
        a.lineTo(13.39f, 0.8f)
        a.quadTo(13.39f, 0f, 12f, 0f)
        a.close()
        val road = Path()
        road.moveTo(12f, 7.47f)
        road.lineTo(2.93f, 23.47f)
        road.lineTo(3.47f, 24f)
        road.lineTo(12f, 20.8f)
        road.lineTo(20.53f, 24f)
        road.lineTo(21.07f, 23.47f)
        road.close()
        p.style = Paint.Style.FILL
        p.alpha = 255
        p.color = Color.WHITE
        c.drawPath(a, p)
        p.color = RED
        c.drawPath(road, p)
        c.restore()

        // Конвертация: верхняя стрелка вправо (белая), нижняя влево (красная).
        val signCx = w / 2f
        val signCy = h * 0.515f
        val half = maxOf(40f, 90f * s)
        val gap = maxOf(6f, 13f * s)
        val stroke = maxOf(6f, 7f * s)
        val headLen = maxOf(14f, 20f * s)
        val headHalf = maxOf(10f, 14f * s)
        val topY = signCy - gap
        val bottomY = signCy + gap
        p.style = Paint.Style.STROKE
        p.strokeWidth = stroke
        p.strokeCap = Paint.Cap.BUTT
        p.strokeJoin = Paint.Join.MITER
        p.color = Color.WHITE
        c.drawLine(signCx - half, topY, signCx + half - headLen * 0.5f, topY, p)
        val head = Path()
        head.moveTo(signCx + half, topY)
        head.lineTo(signCx + half - headLen, topY - headHalf)
        head.lineTo(signCx + half - headLen, topY + headHalf)
        head.close()
        p.style = Paint.Style.FILL
        c.drawPath(head, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = stroke
        p.color = RED
        c.drawLine(signCx + half, bottomY, signCx - half + headLen * 0.5f, bottomY, p)
        head.reset()
        head.moveTo(signCx - half, bottomY)
        head.lineTo(signCx - half + headLen, bottomY - headHalf)
        head.lineTo(signCx - half + headLen, bottomY + headHalf)
        head.close()
        p.style = Paint.Style.FILL
        p.color = RED
        c.drawPath(head, p)

        // Подпись: моноширинный Google Sans, мелко — это кредит, а не заголовок.
        p.typeface = labelTypeface ?: Typeface.MONOSPACE
        p.textSize = maxOf(12f, 24f * s)
        p.textAlign = Paint.Align.CENTER
        p.letterSpacing = 0.03f
        p.style = Paint.Style.FILL
        p.color = TEXT
        c.drawText("By Sajan", w / 2f, h * 0.605f, p)
        p.letterSpacing = 0f

        // Полоса: сегмент идёт слева направо и гаснет на краях.
        val lineH = maxOf(4f, 5f * s)
        val trackW = w * TRACK_W
        val barW = trackW * BAR_W
        val left = (w - trackW) / 2f
        val top = h * 0.69f - lineH / 2f
        p.style = Paint.Style.FILL
        p.color = TRACK
        c.drawRoundRect(left, top, left + trackW, top + lineH, lineH / 2f, lineH / 2f, p)

        val phase = (t % SWEEP_S) / SWEEP_S
        val ease = phase * phase * (3f - 2f * phase)
        val fade = minOf(1f, minOf(phase / 0.12f, (1f - phase) / 0.18f)).coerceAtLeast(0f)
        val x = left + (trackW - barW) * ease
        p.color = RED
        p.alpha = (255 * fade).toInt()
        c.drawRoundRect(x, top, x + barW, top + lineH, lineH / 2f, lineH / 2f, p)
        p.alpha = 255
    }

    private fun draw(c: Canvas) {
        drawBanner(c, width, height, framesRendered, fill)
    }

    companion object {
        const val MIME = "video/avc"

        private val BG = Color.rgb(0x0B, 0x0F, 0x12) // фон иконки
        private val RED = Color.rgb(0xE5, 0x39, 0x35)
        private val TRACK = Color.rgb(0x2A, 0x1D, 0x1D)
        private val TEXT = Color.rgb(0xDC, 0xE3, 0xE8)

        /** Период хода полосы, секунды. */
        private const val SWEEP_S = 1.8f

        /** Полоса под кольцом: доля ширины кадра и доля трека, которую занимает сегмент. */
        private const val TRACK_W = 0.2396f
        private const val BAR_W = 0.3478f
    }
}
