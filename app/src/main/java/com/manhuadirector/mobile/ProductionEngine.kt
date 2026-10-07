package com.manhuadirector.mobile

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.graphics.pdf.PdfRenderer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream
import kotlin.math.max
import kotlin.math.min

class ProductionEngine(private val context: Context) {

    data class Result(val uri: Uri, val displayName: String)
    data class Beat(val atUs: Long, val panelNumber: Int)
    data class Segment(val startUs: Long, val endUs: Long, val panel: Bitmap, val index: Int)
    private data class Encoded(val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private val fps = 15
    private val width = 1280
    private val height = 720

    fun produce(
        chapterUri: Uri,
        scriptUri: Uri?,
        audioUri: Uri,
        progress: (Float, String) -> Unit
    ): Result {
        progress(0.02f, "Reading narration duration…")
        val durationUs = audioDurationUs(audioUri).coerceAtLeast(1L)

        progress(0.05f, "Rendering chapter pages…")
        val panels = extractPanels(chapterUri) { p ->
            progress(0.05f + p * 0.30f, "Finding panels… ${(p * 100).toInt()}%")
        }
        if (panels.isEmpty()) error("No usable panels found in the chapter PDF.")

        progress(0.37f, "Reading timestamp map…")
        val beats = scriptUri?.let { parseScriptPdf(it) }.orEmpty()
        val segments = buildSegments(panels, beats, durationUs)

        progress(0.42f, "Encoding video…")
        val output = createOutput("manhua_${System.currentTimeMillis()}.mp4")
        try {
            encodeVideoWithAudio(segments, audioUri, durationUs, output) { p ->
                progress(0.42f + p * 0.55f, "Encoding video… ${(p * 100).toInt()}%")
            }
            output.closeAndFinalize(context)
        } catch (t: Throwable) {
            output.closeQuietly()
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.delete(output.uri, null, null)
            throw t
        }
        progress(1f, "Video complete.")
        return Result(output.uri, output.fileName)
    }

    private fun audioDurationUs(uri: Uri): Long {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        try {
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    return if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else scanDuration(ex, i)
                }
            }
            error("No audio track found.")
        } finally {
            ex.release()
        }
    }

    private fun scanDuration(ex: MediaExtractor, index: Int): Long {
        ex.selectTrack(index)
        var last = 0L
        val buf = ByteBuffer.allocate(64 * 1024)
        while (true) {
            val t = ex.sampleTime
            if (t < 0) break
            last = t
            if (ex.readSampleData(buf, 0) < 0) break
            ex.advance()
        }
        return last + 100_000L
    }

    private fun extractPanels(uri: Uri, progress: (Float) -> Unit): List<Bitmap> {
        val tmp = File(context.cacheDir, "chapter_${System.nanoTime()}.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            FileOutputStream(tmp).use { out ->
                input?.copyTo(out) ?: error("Cannot read chapter PDF.")
            }
        }
        val out = mutableListOf<Bitmap>()
        val pfd = android.os.ParcelFileDescriptor.open(tmp, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            val total = renderer.pageCount
            for (i in 0 until total) {
                renderer.openPage(i).use { page ->
                    val scale = 1.35f
                    val bw = (page.width * scale).toInt().coerceIn(900, 1800)
                    val bh = (page.height * scale).toInt().coerceIn(1200, 3600)
                    val pageBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    pageBitmap.eraseColor(Color.WHITE)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.addAll(splitByYellow(pageBitmap))
                    pageBitmap.recycle()
                }
                progress((i + 1f) / total.coerceAtLeast(1))
            }
        } finally {
            renderer.close()
            pfd.close()
            tmp.delete()
        }
        return out
    }

    private fun splitByYellow(bitmap: Bitmap): List<Bitmap> {
        val w = bitmap.width
        val h = bitmap.height
        val rows = mutableListOf<Int>()
        val pixels = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
            var yellow = 0
            val step = max(1, w / 700)
            for (x in 0 until w step step) {
                val c = pixels[x]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (r > 200 && g > 160 && b < 150 && r > b * 1.6f) yellow++
            }
            if (yellow > max(3, w / 220)) rows.add(y)
        }

        val runs = mutableListOf<IntRange>()
        var start = -1
        var last = -2
        for (y in rows) {
            if (start < 0) start = y
            if (y > last + 2) {
                if (start >= 0 && last >= start) runs.add(start..last)
                start = y
            }
            last = y
        }
        if (start >= 0 && last >= start) runs.add(start..last)

        if (runs.isEmpty()) return listOf(trimWhitespace(bitmap, 0, h))

        val pieces = mutableListOf<Bitmap>()
        var top = 0
        for (run in runs) {
            val bottom = run.first
            if (bottom - top > 40) pieces.add(trimWhitespace(bitmap, top, bottom))
            top = (run.last + 1).coerceAtMost(h)
        }
        if (h - top > 40) pieces.add(trimWhitespace(bitmap, top, h))
        return pieces.filter { it.width > 80 && it.height > 80 }
    }

    private fun trimWhitespace(bitmap: Bitmap, top: Int, bottom: Int): Bitmap {
        val w = bitmap.width
        var left = w - 1
        var right = 0
        var yTop = bottom - 1
        var yBottom = top
        val row = IntArray(w)
        for (y in top until bottom step 4) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w step 4) {
                val c = row[x]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (!(r > 247 && g > 247 && b > 247)) {
                    left = min(left, x)
                    right = max(right, x)
                    yTop = min(yTop, y)
                    yBottom = max(yBottom, y)
                }
            }
        }
        if (right <= left || yBottom <= yTop) {
            return Bitmap.createBitmap(bitmap, 0, top, w, (bottom - top).coerceAtLeast(1))
        }
        val pad = 4
        val l = (left - pad).coerceAtLeast(0)
        val r = (right + pad + 1).coerceAtMost(w)
        val t = (yTop - pad).coerceAtLeast(top)
        val b = (yBottom + pad + 1).coerceAtMost(bottom)
        return Bitmap.createBitmap(bitmap, l, t, (r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1))
    }

    private fun parseScriptPdf(uri: Uri): List<Beat> {
        val bytes = context.contentResolver.openInputStream(uri).use { it?.readBytes() ?: return emptyList() }
        val text = PdfTextLite.extract(bytes)
        val result = mutableListOf<Beat>()
        for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val match = Regex("(?<!\\d)(\\d{1,2}):([0-5]\\d)(?::([0-5]\\d))?").find(line) ?: continue
            val numbers = Regex("\\b\\d{1,4}\\b").findAll(line).map { it.value.toInt() }.toList()
            if (numbers.isEmpty()) continue
            val a = match.groupValues[1].toLong()
            val b = match.groupValues[2].toLong()
            val c = if (match.groupValues[3].isNotEmpty()) match.groupValues[3].toLong() else -1L
            val seconds = if (c >= 0) a * 3600 + b * 60 + c else a * 60 + b
            result.add(Beat(seconds * 1_000_000L, numbers.last()))
        }
        return result.distinctBy { it.atUs to it.panelNumber }.sortedBy { it.atUs }
    }

    private fun buildSegments(panels: List<Bitmap>, beats: List<Beat>, durationUs: Long): List<Segment> {
        val usable = beats.filter { it.atUs in 0 until durationUs && it.panelNumber in 1..panels.size }
            .sortedBy { it.atUs }
            .fold(mutableListOf<Beat>()) { acc, b ->
                if (acc.lastOrNull()?.atUs != b.atUs) acc.add(b)
                acc
            }

        if (usable.isEmpty()) {
            val per = durationUs / panels.size.coerceAtLeast(1)
            return panels.mapIndexed { i, p ->
                Segment(i * per, if (i == panels.lastIndex) durationUs else (i + 1) * per, p, i)
            }
        }

        val segments = mutableListOf<Segment>()
        for (i in usable.indices) {
            val start = usable[i].atUs
            val end = if (i + 1 < usable.size) usable[i + 1].atUs else durationUs
            if (end > start) segments.add(Segment(start, end, panels[usable[i].panelNumber - 1], usable[i].panelNumber - 1))
        }
        return if (segments.isEmpty()) buildSegments(panels, emptyList(), durationUs) else segments
    }

    private fun createOutput(name: String): OutputHandle {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ManhuaDirector")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create output file.")
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("Cannot open output file.")
            return OutputHandle(uri, name, pfd)
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        dir.mkdirs()
        val file = File(dir, name)
        return OutputHandle(Uri.fromFile(file), name, null, file)
    }

    private fun encodeVideoWithAudio(
        segments: List<Segment>,
        audioUri: Uri,
        durationUs: Long,
        output: OutputHandle,
        done: (Float) -> Unit
    ) {
        val muxer = if (output.pfd != null) {
            MediaMuxer(output.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            MediaMuxer(output.file!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        val extractor = MediaExtractor()
        extractor.setDataSource(context, audioUri, null)
        val audioIndex = (0 until extractor.trackCount).firstOrNull {
            (extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")
        } ?: error("No audio track found.")

        val vf = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()

        val info = MediaCodec.BufferInfo()
        val pending = mutableListOf<Encoded>()
        var videoTrack = -1
        var muxerStarted = false
        val stepUs = 1_000_000L / fps
        val frames = max(1L, (durationUs + stepUs - 1) / stepUs)

        fun drain() {
            while (true) {
                val idx = encoder.dequeueOutputBuffer(info, 0)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.addTrack(extractor.getTrackFormat(audioIndex))
                    muxer.start()
                    muxerStarted = true
                    pending.forEach { e -> muxer.writeSampleData(videoTrack, e.data.duplicate(), e.info) }
                    pending.clear()
                    continue
                }
                if (idx >= 0) {
                    val buf = encoder.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val copy = ByteBuffer.allocate(info.size)
                        copy.put(buf)
                        copy.flip()
                        val ni = MediaCodec.BufferInfo()
                        ni.set(0, info.size, info.presentationTimeUs, info.flags)
                        if (muxerStarted) muxer.writeSampleData(videoTrack, copy.duplicate(), ni) else pending.add(Encoded(copy, ni))
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(idx, false)
                    if (eos) break
                }
            }
        }

        try {
            for (frame in 0 until frames) {
                val tUs = min(durationUs - 1L, frame * stepUs)
                val seg = segmentAt(segments, tUs)
                val canvas = surface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.BLACK)
                    drawPanel(canvas, seg.panel, tUs - seg.startUs, max(1L, seg.endUs - seg.startUs), seg.index)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drain()
                done(frame.toFloat() / frames.toFloat())
            }

            encoder.signalEndOfInputStream()
            var eosSeen = false
            while (!eosSeen) {
                val idx = encoder.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.addTrack(extractor.getTrackFormat(audioIndex))
                    muxer.start()
                    muxerStarted = true
                    continue
                }
                if (idx >= 0) {
                    val buf = encoder.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val copy = ByteBuffer.allocate(info.size)
                        copy.put(buf)
                        copy.flip()
                        val ni = MediaCodec.BufferInfo()
                        ni.set(0, info.size, info.presentationTimeUs, info.flags)
                        if (muxerStarted) muxer.writeSampleData(videoTrack, copy.duplicate(), ni)
                        else pending.add(Encoded(copy, ni))
                    }
                    eosSeen = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(idx, false)
                }
            }

            extractor.selectTrack(audioIndex)
            val ab = ByteBuffer.allocate(512 * 1024)
            val ai = MediaCodec.BufferInfo()
            val audioOutTrack = 1
            while (true) {
                val t = extractor.sampleTime
                if (t < 0 || t > durationUs) break
                val size = extractor.readSampleData(ab, 0)
                if (size < 0) break
                ai.set(0, size, t, extractor.sampleFlags)
                muxer.writeSampleData(audioOutTrack, ab, ai)
                extractor.advance()
            }
        } finally {
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { surface.release() }
            runCatching { extractor.release() }
        }
    }

    private fun segmentAt(segments: List<Segment>, timeUs: Long): Segment {
        var low = 0
        var high = segments.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val s = segments[mid]
            if (timeUs < s.startUs) high = mid - 1
            else if (timeUs >= s.endUs) low = mid + 1
            else return s
        }
        return segments.last()
    }

    private fun drawPanel(c: Canvas, bitmap: Bitmap, elapsedUs: Long, durationUs: Long, index: Int) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val fit = min(width / bw, height / bh)
        val baseW = bw * fit
        val baseH = bh * fit
        val p = (elapsedUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
        val phase = index % 7
        val zoom = 1f + when (phase) {
            0 -> 0.035f * p
            1 -> 0.035f * (1f - p)
            2 -> 0.022f
            3 -> 0.025f
            4 -> 0.028f * p
            5 -> 0.018f
            else -> 0.03f * (1f - p)
        }
        val panX = when (phase) {
            2 -> -baseW * 0.018f * p
            3 -> baseW * 0.018f * p
            4 -> baseW * 0.012f * p
            else -> 0f
        }
        val panY = when (phase) {
            5 -> -baseH * 0.018f * p
            6 -> baseH * 0.018f * p
            else -> 0f
        }

        val matrix = Matrix()
        matrix.postScale(zoom, zoom)
        matrix.postTranslate(
            (width - baseW * zoom) / 2f + panX,
            (height - baseH * zoom) / 2f + panY
        )
        c.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private class OutputHandle(
        val uri: Uri,
        val fileName: String,
        val pfd: android.os.ParcelFileDescriptor?,
        val file: File? = null
    ) {
        fun closeAndFinalize(context: Context) {
            pfd?.close()
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
        }
        fun closeQuietly() = runCatching { pfd?.close() }
    }
}
