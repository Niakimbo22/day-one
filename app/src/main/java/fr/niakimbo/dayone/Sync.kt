package fr.niakimbo.dayone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.time.LocalDate

/** Rafraîchit les dates (reports, annonces) et garde les covers en cache sur le téléphone. */
object Sync {
    private fun dir(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }

    private fun coverFile(ctx: Context, g: Game): File? {
        val u = g.image ?: return null
        return File(dir(ctx), "${g.id}_${u.hashCode().toUInt().toString(16)}.jpg")
    }

    fun coverBitmap(ctx: Context, g: Game): Bitmap? =
        coverFile(ctx, g)?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Télécharge la cover si elle n'est pas déjà en cache. */
    fun ensureCover(ctx: Context, g: Game): Boolean {
        val f = coverFile(ctx, g) ?: return false
        if (f.exists()) return true
        val url = Rawg.sized(g.image, 1280) ?: return false
        return try {
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(Rawg.bytes(url))
            tmp.renameTo(f)
            dir(ctx).listFiles()?.filter { it.name.startsWith("${g.id}_") && it != f }?.forEach { it.delete() }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Une fois par jour (ou à la demande) : dates à jour + covers présentes. */
    fun run(ctx: Context, force: Boolean = false) {
        val store = Store(ctx)
        val today = LocalDate.now().toString()
        if (force || store.lastSync != today) {
            var allOk = true
            val fresh = store.games().map { g ->
                Rawg.details(g.id) ?: g.also { allOk = false }
            }
            if (fresh.isNotEmpty()) store.save(fresh)
            if (allOk) store.lastSync = today
        }
        if (force || store.lastUpdateCheck != today) Updater.check(ctx)
        val games = store.games()
        games.forEach { ensureCover(ctx, it) }
        val keep = games.map { "${it.id}_" }
        dir(ctx).listFiles()?.filter { f -> keep.none { f.name.startsWith(it) } }?.forEach { it.delete() }
    }
}
