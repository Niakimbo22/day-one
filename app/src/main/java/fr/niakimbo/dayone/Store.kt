package fr.niakimbo.dayone

import android.content.Context
import org.json.JSONArray
import java.time.LocalDate

/** Jeux suivis + petits réglages, dans les SharedPreferences. */
class Store(context: Context) {
    private val ctx = context.applicationContext
    private val p = ctx.getSharedPreferences("dayone", Context.MODE_PRIVATE)

    private fun rank(g: Game, today: LocalDate): Int = when {
        g.date == null -> 1
        g.date!! >= today -> 0
        else -> 2
    }

    /** À venir d'abord (le plus proche en premier), puis les dates à confirmer, puis les jeux déjà sortis. */
    fun games(): List<Game> {
        val a = JSONArray(p.getString("games", "[]"))
        val today = LocalDate.now()
        return (0 until a.length()).map { Game.fromJson(a.getJSONObject(it)) }
            .sortedWith(
                compareBy<Game> { rank(it, today) }.thenBy {
                    val d = it.date?.toEpochDay() ?: 0L
                    if (rank(it, today) == 2) -d else d
                }
            )
    }

    fun save(list: List<Game>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        p.edit().putString("games", a.toString()).apply()
    }

    fun has(id: Int) = games().any { it.id == id }
    fun add(g: Game) { if (!has(g.id)) save(games() + g) }
    fun remove(id: Int) = save(games().filter { it.id != id })

    fun index(widgetId: Int) = p.getInt("idx_$widgetId", 0)
    fun setIndex(widgetId: Int, i: Int) = p.edit().putInt("idx_$widgetId", i).apply()
    fun clearIndex(ids: IntArray) = p.edit().apply { ids.forEach { remove("idx_$it") } }.apply()

    var lastSync: String
        get() = p.getString("lastSync", "") ?: ""
        set(v) = p.edit().putString("lastSync", v).apply()

    var seeded: Boolean
        get() = p.getBoolean("seeded", false)
        set(v) = p.edit().putBoolean("seeded", v).apply()

    /** Version (versionCode) la plus récente trouvée en ligne, 0 si aucune. */
    var updateCode: Long
        get() = p.getLong("updateCode", 0L)
        set(v) = p.edit().putLong("updateCode", v).apply()

    var lastUpdateCheck: String
        get() = p.getString("lastUpdateCheck", "") ?: ""
        set(v) = p.edit().putString("lastUpdateCheck", v).apply()

    private fun installedCode(): Long =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)

    var config: Config
        get() = Config(
            days = p.getBoolean("c_days", true),
            hours = p.getBoolean("c_hours", false),
            minutes = p.getBoolean("c_minutes", false),
            seconds = p.getBoolean("c_seconds", false),
            dots = p.getBoolean("c_dots", true),
            accent = p.getInt("c_accent", Config.RED),
            showName = p.getBoolean("c_name", true),
            showPlatforms = p.getBoolean("c_platforms", true),
            showDate = p.getBoolean("c_date", true),
            showPagination = p.getBoolean("c_pagination", true),
            showGrid = p.getBoolean("c_grid", true),
            dim = p.getInt("c_dim", 100),
            corner = p.getInt("c_corner", 26),
            numScale = p.getInt("c_numScale", 100),
            nameScale = p.getInt("c_nameScale", 100),
            updateAvailable = updateCode > installedCode(),
        )
        set(v) {
            p.edit()
                .putBoolean("c_days", v.days).putBoolean("c_hours", v.hours)
                .putBoolean("c_minutes", v.minutes).putBoolean("c_seconds", v.seconds)
                .putBoolean("c_dots", v.dots).putInt("c_accent", v.accent)
                .putBoolean("c_name", v.showName).putBoolean("c_platforms", v.showPlatforms)
                .putBoolean("c_date", v.showDate).putBoolean("c_pagination", v.showPagination)
                .putBoolean("c_grid", v.showGrid).putInt("c_dim", v.dim)
                .putInt("c_corner", v.corner).putInt("c_numScale", v.numScale)
                .putInt("c_nameScale", v.nameScale)
                .apply()
        }
}
