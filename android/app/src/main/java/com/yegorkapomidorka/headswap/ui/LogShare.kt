package com.yegorkapomidorka.headswap.ui

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaDensity
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.autosrv.AutoServer
import com.yegorkapomidorka.headswap.link.car.CarProfile
import com.yegorkapomidorka.headswap.link.car.CarWifi
import com.yegorkapomidorka.headswap.link.diag.NetProbe
import com.yegorkapomidorka.headswap.link.wifi.LanIface
import com.yegorkapomidorka.headswap.update.UpdatePrefs
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Раздаёт файлы логов и отчётов другим приложениям (ACTION_SEND); без AndroidX FileProvider. Только чтение. */
class LogProvider : ContentProvider() {
    override fun onCreate() = true

    private fun resolve(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val ctx = context ?: return null
        return LogShare.dirs(ctx).map { File(it, name) }.firstOrNull { it.isFile }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = resolve(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return MatrixCursor(cols).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String? =
        if (uri.lastPathSegment?.endsWith(".zip") == true) "application/zip" else "text/plain"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = resolve(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

object LogShare {
    const val AUTHORITY = "com.yegorkapomidorka.headswap.logs"
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_LOGS = 5
    private const val WL_MARK = " WL "

    fun logDir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    private fun reportDir(ctx: Context): File = File(ctx.cacheDir, "reports").also { it.mkdirs() }

    fun dirs(ctx: Context): List<File> = listOf(logDir(ctx), reportDir(ctx))

    /** Файлы headswap-*.log, новые первыми. */
    fun logFiles(ctx: Context): List<File> =
        logDir(ctx).listFiles { f -> f.name.startsWith("headswap-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun uriFor(f: File): Uri = Uri.parse("content://$AUTHORITY/${Uri.encode(f.name)}")

    private fun shareFile(ctx: Context, f: File, mime: String, subject: String) {
        val uri = uriFor(f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri(f.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            ctx.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("не открыть меню «Поделиться»", e)
            Toast.makeText(ctx, ctx.getString(R.string.share_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /** Текущий файл headswap-*.log как есть. */
    fun shareLog(ctx: Context) {
        val f = AppLog.file?.takeIf { it.isFile } ?: logFiles(ctx).firstOrNull()
        if (f == null) {
            Toast.makeText(ctx, ctx.getString(R.string.share_no_log), Toast.LENGTH_SHORT).show()
            return
        }
        shareFile(ctx, f, "text/plain", ctx.getString(R.string.share_subject_log, f.name))
    }

    /** Архив: логи + сведения об устройстве и настройках. Без контактов и геопозиции. */
    fun shareReport(ctx: Context) {
        val zip = try {
            buildZip(ctx)
        } catch (e: Exception) {
            AppLog.e("не собрать архив отчёта", e)
            Toast.makeText(ctx, ctx.getString(R.string.share_report_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        shareFile(ctx, zip, "application/zip", ctx.getString(R.string.share_subject_report))
    }

    /** Архив в «Загрузки» (MediaStore, без разрешений); при неудаче - меню «Поделиться». */
    fun saveZip(ctx: Context) {
        val zip = try {
            buildZip(ctx)
        } catch (e: Exception) {
            AppLog.e("не собрать архив", e)
            Toast.makeText(ctx, ctx.getString(R.string.share_zip_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        var dst: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore не создал файл")
            dst = uri
            val os = ctx.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("MediaStore не открыл файл на запись")
            os.use { out -> zip.inputStream().use { it.copyTo(out) } }
            dst = null      // дописан: дальше не удалять
            AppLog.i("архив сохранён в Загрузки: ${zip.name}")
            Toast.makeText(ctx, ctx.getString(R.string.share_saved, zip.name), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AppLog.e("не сохранить в Загрузки, делюсь файлом", e)
            // пустой или недописанный архив в «Загрузках» не оставляем
            dst?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            shareFile(ctx, zip, "application/zip", ctx.getString(R.string.share_subject_archive))
        }
    }

    private fun buildZip(ctx: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = reportDir(ctx)
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "headswap-$stamp.zip")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("device.txt"))
            z.write(deviceInfo(ctx).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            val files = logFiles(ctx).take(MAX_LOGS)
            for (f in files) {
                z.putNextEntry(ZipEntry("logs/${f.name}"))
                copyTail(f, z)
                z.closeEntry()
            }
            z.putNextEntry(ZipEntry("wireless.log"))
            z.write(wirelessLog(files.reversed().map { f -> f.name to f.useLines { wirelessLines(it).toList() } }).toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return out
    }

    /** wireless.log: на каждый файл (от старых к новым) строка `== имя` и его WL-строки; везде пусто - «WL-строк нет». */
    fun wirelessLog(files: List<Pair<String, List<String>>>): String {
        val sb = StringBuilder()
        var any = false
        for ((name, lines) in files) {
            sb.append("== ").append(name).append('\n')
            for (l in wirelessLines(lines)) {
                sb.append(l).append('\n')
                any = true
            }
        }
        return if (any) sb.toString() else "WL-строк нет\n"
    }

    /** Строки беспровода: `HH:mm:ss.SSS WL <фаза> ...`, ищем метку с пробелами вокруг. */
    fun wirelessLines(lines: Sequence<String>): Sequence<String> = lines.filter { WL_MARK in it }

    fun wirelessLines(lines: List<String>): List<String> = wirelessLines(lines.asSequence()).toList()

    /** Файл целиком или только последние MAX_FILE_BYTES (hexdump'ы раздувают лог). */
    private fun copyTail(f: File, to: java.io.OutputStream) {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            if (len > MAX_FILE_BYTES) {
                raf.seek(len - MAX_FILE_BYTES)
                to.write("[... начало обрезано, показаны последние ${MAX_FILE_BYTES / 1024} КиБ ...]\n".toByteArray())
            }
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                to.write(buf, 0, n)
            }
        }
    }

    fun deviceInfo(ctx: Context): String {
        val aa = AaPrefs(ctx)
        val upd = UpdatePrefs(ctx)
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        return buildString {
            appendLine("Headswap $ver")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}")
            appendLine("Uptime: ${SystemClock.elapsedRealtime() / 1000} s")
            appendLine()
            appendLine("Bridge settings")
            appendLine("  video: ${if (aa.transcode) "transcoding" else "passthrough"}")
            appendLine("  video resolution: selected ${aa.videoGeometry.label}, in session: ${CarLifeState.videoMode?.describe(CarLifeState.densityOverride) ?: "no session yet"}")
            appendLine("  AA interface scale: ${if (aa.aaDensity == AaDensity.AUTO) "auto" else "${aa.aaDensity} dpi"}")
            appendLine("  frame rate: ${aa.fpsSetting.name}")
            appendLine("  USB audio: ${aa.usbAudio}")
            appendLine("  music sample rate: ${aa.musicRate.name}")
            appendLine("  music chunk size: ${aa.mediaChunk.name}")
            appendLine("  my car: ${aa.carPreset?.let { "${it.name}${if (aa.carSettings == it.settings) "" else ", settings changed manually"}" } ?: "not selected"}")
            appendLine("  certificate: ${aa.certSet}")
            appendLine("  head unit server auto-start: ${aa.autoServer}")
            appendLine("  microphone: ${aa.micMode.label}, MODULE_STATUS VR: ${aa.micVrStatus}")
            appendLine("  update check: ${upd.enabled}, on GitHub: ${upd.release()?.version ?: "not checked"}")
            appendLine("  metadata: music ${aa.metaMusic}, navigation ${aa.metaNavi}, field 6 NAVI_NEXT_TURN_INFO: ${aa.naviField6}, progress in ${if (aa.progressSeconds) "s" else "ms"}")
            appendLine()
            appendLine("Head unit data and state")
            appendLine(CarLifeState.summary())
            appendLine(AaState.summary())
            appendLine(AutoServer.summary(ctx))
            appendLine()
            appendLine("Wireless")
            appendLine("  beta: ${BuildConfig.WIRELESS_BETA}")
            appendLine("  ACCESS_LOCAL_NETWORK: ${NetProbe.localNetworkGranted(ctx)}")
            appendLine("  networks: ${lanText()}")
            appendLine("  mode: ${CarLifeState.wireless ?: "off"}")
            appendLine("  Bluetooth carlife: ${CarLifeState.btProbe ?: "not started"}")
            val car = CarProfile(ctx)      // no MAC or name
            appendLine("  car: ${if (car.isSet) "selected (CDM id ${car.cdmAssociationId})" else "not selected"}")
            if (car.isSet) appendLine("  car connection: ${car.connMode}")
            appendLine("  Bluetooth trigger: ${if (CarProfile.triggerEnabled(ctx)) "on" else "off"}")
            appendLine("  auto: ${if (CarLifeState.carAuto) "yes" else "no"}")
            appendLine("  car Wi-Fi: ${if (CarWifi(ctx).isSet) "set" else "not set"}")      // no SSID or password
            appendLine("  car network: ${CarLifeState.carNet}")
        }
    }

    private fun lanText(): String = try {
        LanIface.scan().joinToString(", ").ifEmpty { "none" }
    } catch (e: Exception) {
        "error ${e.javaClass.simpleName}"
    }

    /** Очистить: удалить старые файлы headswap-*.log (текущий оставить). */
    fun deleteOldLogs(ctx: Context): Int {
        val cur = AppLog.file
        var n = 0
        for (f in logFiles(ctx)) if (f != cur && f.delete()) n++
        return n
    }

    fun sizeText(ctx: Context, f: File?): String {
        val b = f?.length() ?: return "—"
        return if (b >= 1024 * 1024) ctx.getString(R.string.share_size_mib, "%.1f".format(Locale.US, b / 1048576.0))
        else ctx.getString(R.string.share_size_kib, b / 1024)
    }
}
