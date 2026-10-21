package fr.niakimbo.dayone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.net.Uri
import org.json.JSONObject
import java.time.LocalDate

/** Mise à jour automatique : lit update.json sur GitHub, télécharge l'APK et l'installe par-dessus. */
object Updater {
    private const val BASE = "https://raw.githubusercontent.com/Niakimbo22/day-one/main"
    const val REPO_URL = "https://github.com/Niakimbo22/day-one"

    class Info(val code: Long, val name: String, val apk: String, val notes: String)

    /** Cherche une version plus récente. Retourne null si rien de neuf ou si le réseau échoue. */
    fun check(ctx: Context): Info? {
        val store = Store(ctx)
        return try {
            val o = JSONObject(String(Rawg.bytes("$BASE/update.json?t=${System.currentTimeMillis() / 60000}"), Charsets.UTF_8))
            val info = Info(o.getLong("versionCode"), o.optString("versionName"), o.optString("apk", "$BASE/DayOne.apk"), o.optString("notes"))
            store.updateCode = info.code
            store.lastUpdateCheck = LocalDate.now().toString()
            if (info.code > installed(ctx)) info else null
        } catch (e: Exception) {
            null
        }
    }

    fun installed(ctx: Context): Long =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Ouvre l'écran Android « autoriser Day One à installer des applis ». */
    fun permissionIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Télécharge puis lance l'installation. Android peut demander un tap de confirmation. */
    fun install(ctx: Context, info: Info): Boolean {
        return try {
            val data = Rawg.bytes(info.apk)
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("DayOne.apk", 0, data.size.toLong()).use { out ->
                    out.write(data)
                    s.fsync(out)
                }
                val intent = PendingIntent.getBroadcast(
                    ctx, id, Intent(ctx, InstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                s.commit(intent.intentSender)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
