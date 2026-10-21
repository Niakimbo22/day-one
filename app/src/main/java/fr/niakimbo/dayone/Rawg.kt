package fr.niakimbo.dayone

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/** Client minimal de l'API RAWG (https://rawg.io/apidocs). Toujours à appeler hors du thread principal. */
object Rawg {
    private const val BASE = "https://api.rawg.io/api"

    class Page(val games: List<Game>, val hasNext: Boolean)

    private val PLATFORMS = mapOf(
        "PC" to "PC",
        "PlayStation" to "PS",
        "Xbox" to "XBOX",
        "Nintendo" to "SWITCH",
        "Apple Macintosh" to "MAC",
        "Linux" to "LINUX",
        "Android" to "ANDROID",
        "iOS" to "IOS",
    )

    fun bytes(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "DayOne/1.0")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun text(url: String) = String(bytes(url), Charsets.UTF_8)

    /** Version redimensionnée d'une image RAWG (largeur fixe, beaucoup plus légère que l'originale : 4 Mo -> 50-170 Ko). */
    fun sized(url: String?, width: Int): String? = when {
        url.isNullOrBlank() -> null
        url.contains("/media/games/") -> url.replace("/media/games/", "/media/resize/$width/-/games/")
        url.contains("/media/screenshots/") -> url.replace("/media/screenshots/", "/media/resize/$width/-/screenshots/")
        else -> url
    }

    private fun parse(o: JSONObject): Game {
        val plats = o.optJSONArray("parent_platforms")?.let { a ->
            (0 until a.length()).mapNotNull { PLATFORMS[a.getJSONObject(it).getJSONObject("platform").optString("name")] }
        } ?: emptyList()
        val img = o.optString("background_image", "")
        return Game(
            id = o.getInt("id"),
            name = o.getString("name"),
            released = if (o.isNull("released")) null else o.optString("released").ifBlank { null },
            tba = o.optBoolean("tba"),
            image = img.takeIf { it.startsWith("http") },
            platforms = plats.joinToString(" · "),
        )
    }

    /** filter : all | ps5 | xbox | pc | nintendo. Sans recherche, renvoie les prochaines sorties (2 ans). */
    fun list(query: String, filter: String, page: Int): Page {
        val q = mutableListOf("key=${BuildConfig.RAWG_KEY}", "page=$page", "page_size=20")
        if (query.isNotBlank()) {
            q += "search=" + URLEncoder.encode(query.trim(), "UTF-8")
            q += "search_precise=true"
        } else {
            val today = LocalDate.now()
            q += "dates=$today,${today.plusYears(2)}"
            q += "ordering=-added"
        }
        when (filter) {
            "ps5" -> q += "platforms=187"
            "xbox" -> q += "platforms=186"
            "pc" -> q += "platforms=4"
            "nintendo" -> q += "parent_platforms=7"
        }
        val o = JSONObject(text("$BASE/games?" + q.joinToString("&")))
        val a = o.getJSONArray("results")
        return Page((0 until a.length()).map { parse(a.getJSONObject(it)) }, !o.isNull("next"))
    }

    fun details(id: Int): Game? = runCatching {
        parse(JSONObject(text("$BASE/games/$id?key=${BuildConfig.RAWG_KEY}")))
    }.getOrNull()
}
