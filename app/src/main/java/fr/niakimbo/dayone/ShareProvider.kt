package fr.niakimbo.dayone

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Sert l'image du widget aux applis de partage (WhatsApp, Snap…), sans dépendance externe. */
class ShareProvider : ContentProvider() {
    private fun file(uri: Uri): File {
        val name = File(uri.lastPathSegment ?: "x").name
        return File(File(context!!.cacheDir, "share"), name)
    }

    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri) = if (uri.lastPathSegment?.endsWith(".apk") == true) "application/vnd.android.package-archive" else "image/png"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(f.name, f.length())) }
    }
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

object Share {
    const val AUTH = "fr.niakimbo.dayone.share"
    const val PAGE = "https://github.com/Niakimbo22/day-one"

    const val APK_LINK = "https://github.com/Niakimbo22/day-one/raw/main/DayOne.apk"

    /** Envoie le fichier APK lui-même (marche sans lien ni navigateur). */
    fun apkFile(a: android.app.Activity) {
        val dir = File(a.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        File(a.applicationInfo.sourceDir).copyTo(File(dir, "DayOne.apk"), true)
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
            .putExtra(android.content.Intent.EXTRA_STREAM, Uri.parse("content://$AUTH/DayOne.apk"))
            .putExtra(android.content.Intent.EXTRA_TEXT, "Day One : widget de compte à rebours pour les sorties de jeux. Ouvre le fichier pour l'installer (autorise les sources inconnues si Android le demande).")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(android.content.Intent.createChooser(i, "Envoyer l'APK"))
    }

    fun app(a: android.app.Activity) {
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, "Day One : un widget Android de compte à rebours pour les sorties de jeux vidéo (GTA VI…), style Nothing.\nTélécharger l'APK : $APK_LINK\n(Si le téléchargement bloque, ouvre la page et prends DayOne.apk : $PAGE)")
        a.startActivity(android.content.Intent.createChooser(i, "Partager Day One"))
    }

    /** Partage une image du widget tel qu'il est réglé (jeu affiché, unités, couleurs). */
    fun widget(a: android.app.Activity, store: Store) {
        val games = store.games().ifEmpty { listOf(Game(972995, "Grand Theft Auto VI", "2026-11-19", false, null, "PS · XBOX")) }
        val d = 2.75f
        val cover = Sync.coverBitmap(a, games[0])
        val res = Renderer.render(d, store.config, games, 0, (360 * d).toInt(), (155 * d).toInt(), cover, System.currentTimeMillis(), true)
        val dir = File(a.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val name = "dayone_${System.currentTimeMillis()}.png"
        File(dir, name).outputStream().use { res.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val g = games[0]
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/png")
            .putExtra(android.content.Intent.EXTRA_STREAM, Uri.parse("content://$AUTH/$name"))
            .putExtra(android.content.Intent.EXTRA_TEXT, "${g.name} : compte à rebours avec Day One\n$PAGE")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(android.content.Intent.createChooser(i, "Partager le widget"))
    }
}
