package fr.niakimbo.dayone

import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Game(
    val id: Int,
    val name: String,
    val released: String?,
    val tba: Boolean,
    val image: String?,
    val platforms: String,
) {
    /** Date de sortie, ou null si inconnue / à confirmer. */
    val date: LocalDate?
        get() {
            val r = released
            if (tba || r == null || r.length < 10) return null
            return runCatching { LocalDate.parse(r.substring(0, 10)) }.getOrNull()
        }

    /** Instant de sortie (minuit, heure locale), ou null si la date est inconnue. */
    fun targetMillis(): Long? =
        date?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    fun daysLeft(today: LocalDate = LocalDate.now()): Long? =
        date?.let { ChronoUnit.DAYS.between(today, it) }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("released", released ?: JSONObject.NULL)
        .put("tba", tba)
        .put("image", image ?: JSONObject.NULL)
        .put("platforms", platforms)

    companion object {
        fun fromJson(o: JSONObject): Game = Game(
            id = o.getInt("id"),
            name = o.getString("name"),
            released = if (o.isNull("released")) null else o.getString("released"),
            tba = o.optBoolean("tba"),
            image = if (o.isNull("image")) null else o.getString("image"),
            platforms = o.optString("platforms"),
        )
    }
}
