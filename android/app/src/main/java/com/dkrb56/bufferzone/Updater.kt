package com.dkrb56.bufferzone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Downloads the release APK from GitHub and hands it to the Android installer. */
object Updater {
    /** null = idle, -1 = downloading without a known size, 0..100 = percent. */
    var progress by mutableStateOf<Int?>(null)

    fun install(ctx: Context, version: String) {
        if (progress != null) return
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
                App.show("Allow Buffer Zone to install apps, then press Update again.")
            } catch (_: Exception) {
                App.show("Allow Install unknown apps for Buffer Zone in the TV settings, then press Update again.")
            }
            return
        }
        progress = 0
        App.scope.launch(Dispatchers.IO) {
            val installer = ctx.packageManager.packageInstaller
            var sessionId = -1
            try {
                val url = "https://github.com/dkrb56/bufferzone/releases/download/v$version/BufferZone-tv-$version.apk"
                App.plex.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val body = r.body ?: error("empty body")
                    val total = body.contentLength()
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    sessionId = installer.createSession(params)
                    installer.openSession(sessionId).use { session ->
                        var done = 0L
                        var last = -2
                        session.openWrite("base.apk", 0, total).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                                    if (pct != last) { last = pct; withContext(Dispatchers.Main) { progress = pct } }
                                }
                            }
                            session.fsync(out)
                        }
                        val cb = Intent(ctx, UpdateReceiver::class.java)
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                        val pi = PendingIntent.getBroadcast(ctx, sessionId, cb, flags)
                        session.commit(pi.intentSender)
                    }
                }
            } catch (_: Exception) {
                if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
                withContext(Dispatchers.Main) { progress = null; App.show("Download failed. Try again later.") }
            }
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }.onFailure { Updater.progress = null; App.show("Update failed: could not open the install screen.") }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.progress = null
            else -> {
                Updater.progress = null
                App.show("Update failed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"))
            }
        }
    }
}
