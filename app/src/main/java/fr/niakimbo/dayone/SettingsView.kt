package fr.niakimbo.dayone

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Onglet RÉGLAGES : aperçu en direct du widget + toutes les options de personnalisation. */
class SettingsView(private val a: Activity, private val store: Store) {
    private val DIM = 0xFF8A8A8A.toInt()
    private val LINE = 0xFF3A3A3A.toInt()
    private val ui = Handler(Looper.getMainLooper())
    private var cfg = store.config
    private lateinit var preview: ImageView
    private val refreshers = ArrayList<() -> Unit>()
    private var update: Updater.Info? = null
    private lateinit var updateBtn: TextView
    private lateinit var updateInfo: TextView
    private var ticking = false

    val root: ScrollView = ScrollView(a).apply { isVerticalScrollBarEnabled = false }

    private fun dp(v: Float) = (v * a.resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, r: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1f).coerceAtLeast(1), stroke)
    }

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(a).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun set(n: Config, fast: Boolean = false) {
        cfg = n
        store.config = n
        redraw()
        if (!fast) DayOneWidget.updateAll(a)
        refreshers.forEach { it() }
    }

    /** Rendu de l'aperçu, rafraîchi chaque seconde quand les secondes sont affichées. */
    private fun redraw() {
        val games = store.games().ifEmpty { listOf(Game(972995, "Grand Theft Auto VI", "2026-11-19", false, null, "PS5 · XBOX")) }
        val d = a.resources.displayMetrics.density
        val w = a.resources.displayMetrics.widthPixels - dp(32f)
        val h = dp(150f)
        val cover = Sync.coverBitmap(a, games[0])
        val c = store.config
        val res = Renderer.render(d, c, games, 0, w, h, cover, System.currentTimeMillis(), true)
        preview.setImageBitmap(res.bitmap)
        if (c.seconds && !ticking) {
            ticking = true
            ui.postDelayed(object : Runnable {
                override fun run() {
                    if (!root.isAttachedToWindow || !store.config.seconds) { ticking = false; return }
                    redraw(); ui.postDelayed(this, 1000)
                }
            }, 1000)
        }
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(tv(title, 11f, DIM, true).apply { letterSpacing = 0.15f }, lp(top = 22f, bottom = 8f))
    }

    private fun lp(top: Float = 0f, bottom: Float = 0f, w: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun toggleRow(parent: LinearLayout, label: String, hint: String?, get: () -> Boolean, put: (Config, Boolean) -> Config) {
        val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(7f), 0, dp(7f)) }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(label, 13f, Color.WHITE, true))
        if (hint != null) col.addView(tv(hint, 10f, DIM))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pill = tv("", 11f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(dp(14f), dp(7f), dp(14f), dp(7f)) }
        fun paint() {
            val on = get()
            pill.text = if (on) "ON" else "OFF"
            pill.setTextColor(if (on) Color.BLACK else 0xFFB5B5B5.toInt())
            pill.background = shape(if (on) Color.WHITE else Color.TRANSPARENT, 20f, if (on) Color.WHITE else LINE)
        }
        refreshers += ::paint
        paint()
        pill.setOnClickListener { set(put(cfg, !get())) }
        row.addView(pill)
        parent.addView(row, lp())
    }

    private fun chips(parent: LinearLayout, options: List<Pair<String, String>>, selected: () -> String, pick: (Config, String) -> Config) {
        val row = LinearLayout(a)
        val views = options.map { (k, label) ->
            tv(label, 11f, Color.WHITE, true).apply {
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                setOnClickListener { set(pick(cfg, k)) }
                row.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8f) })
            } to k
        }
        fun paint() = views.forEach { (v, k) ->
            val on = k == selected()
            v.background = shape(if (on) cfg.accent else Color.TRANSPARENT, 20f, if (on) cfg.accent else LINE)
            v.setTextColor(if (on) Color.WHITE else 0xFFB5B5B5.toInt())
        }
        refreshers += ::paint
        paint()
        parent.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) }, lp())
    }

    private fun slider(parent: LinearLayout, label: String, min: Int, max: Int, get: () -> Int, put: (Config, Int) -> Config, unit: String = "%") {
        val head = LinearLayout(a)
        val name = tv(label, 12f, Color.WHITE, true)
        val value = tv("", 12f, DIM)
        head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(value)
        val bar = SeekBar(a).apply {
            this.max = max - min
            progress = get() - min
            progressTintList = android.content.res.ColorStateList.valueOf(cfg.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    value.text = "${p + min}$unit"
                    cfg = put(cfg, p + min); store.config = cfg; redraw()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { DayOneWidget.updateAll(a) }
            })
        }
        value.text = "${get()}$unit"
        parent.addView(head, lp(top = 6f))
        parent.addView(bar, lp())
    }

    fun build(): ScrollView {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(8f), dp(16f), dp(40f)) }
        root.addView(col)

        preview = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_XY }
        col.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150f)))
        col.addView(tv("APERÇU EN DIRECT · IL SE MET À JOUR TOUT SEUL SUR TON ÉCRAN D'ACCUEIL", 9f, DIM).apply { gravity = Gravity.CENTER }, lp(top = 6f))

        section(col, "COMPTE À REBOURS")
        toggleRow(col, "Jours", null, { cfg.days }, { c, v -> if (!v && !c.hours && !c.minutes && !c.seconds) c else c.copy(days = v) })
        toggleRow(col, "Heures", null, { cfg.hours }, { c, v -> if (!v && !c.days && !c.minutes && !c.seconds) c else c.copy(hours = v) })
        toggleRow(col, "Minutes", null, { cfg.minutes }, { c, v -> if (!v && !c.days && !c.hours && !c.seconds) c else c.copy(minutes = v) })
        toggleRow(col, "Secondes en direct", "Chrono H:MM:SS qui tourne tout seul, sans batterie", { cfg.seconds }, { c, v -> if (!v && !c.days && !c.hours && !c.minutes) c else c.copy(seconds = v) })

        section(col, "STYLE DES CHIFFRES")
        chips(col, listOf("dots" to "POINTS", "text" to "TEXTE"), { if (cfg.dots) "dots" else "text" }, { c, k -> c.copy(dots = k == "dots") })

        section(col, "COULEUR D'ACCENT")
        val sw = LinearLayout(a)
        val colors = listOf(0xFFD71921, 0xFFFF6A00, 0xFFF5C400, 0xFF22C55E, 0xFF06B6D4, 0xFF3B82F6, 0xFFA855F7, 0xFFEC4899, 0xFFFFFFFF).map { it.toInt() }
        val dots = colors.map { col0 ->
            View(a).apply {
                setOnClickListener { set(cfg.copy(accent = col0)) }
                sw.addView(this, LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { rightMargin = dp(10f) })
            } to col0
        }
        fun paintSw() = dots.forEach { (v, c) -> v.background = shape(c, 17f, if (cfg.accent == c) Color.WHITE else 0xFF222222.toInt()).apply { setStroke(dp(if (cfg.accent == c) 3f else 1f), if (cfg.accent == c) 0xFF888888.toInt() else 0xFF222222.toInt()) } }
        refreshers += ::paintSw
        paintSw()
        col.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(sw) }, lp())

        section(col, "ÉLÉMENTS AFFICHÉS")
        toggleRow(col, "Nom du jeu", null, { cfg.showName }, { c, v -> c.copy(showName = v) })
        toggleRow(col, "Plateformes", null, { cfg.showPlatforms }, { c, v -> c.copy(showPlatforms = v) })
        toggleRow(col, "Date de sortie", null, { cfg.showDate }, { c, v -> c.copy(showDate = v) })
        toggleRow(col, "Pastilles de pagination", null, { cfg.showPagination }, { c, v -> c.copy(showPagination = v) })
        toggleRow(col, "Trame de points sur la cover", null, { cfg.showGrid }, { c, v -> c.copy(showGrid = v) })

        section(col, "FORME ET TAILLE")
        slider(col, "Coins arrondis", 0, 40, { cfg.corner }, { c, v -> c.copy(corner = v) }, "dp")
        slider(col, "Assombrissement de la cover", 0, 100, { cfg.dim }, { c, v -> c.copy(dim = v) })
        slider(col, "Taille des chiffres", 50, 160, { cfg.numScale }, { c, v -> c.copy(numScale = v) })
        slider(col, "Taille du nom", 50, 160, { cfg.nameScale }, { c, v -> c.copy(nameScale = v) })
        col.addView(tv("La taille du widget lui-même : appui long dessus sur l'écran d'accueil, puis tire les poignées.", 10f, DIM), lp(top = 10f))

        section(col, "PARTAGER")
        val shareRow = LinearLayout(a)
        fun shareBtn(t: String, f: () -> Unit) = tv(t, 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12f), 0, dp(12f))
            background = shape(Color.TRANSPARENT, 12f, Color.WHITE); setOnClickListener { f() }
        }
        shareRow.addView(shareBtn("PARTAGER L'APP") { Share.app(a) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8f) })
        shareRow.addView(shareBtn("PARTAGER LE WIDGET") { Share.widget(a, store) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(shareRow, lp())

        section(col, "MISE À JOUR")
        updateInfo = tv("Version ${installedName()}", 12f, Color.WHITE)
        col.addView(updateInfo, lp())
        updateBtn = tv("VÉRIFIER LES MISES À JOUR", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, dp(12f))
            background = shape(Color.TRANSPARENT, 12f, Color.WHITE)
            setOnClickListener { onUpdateClick() }
        }
        col.addView(updateBtn, lp(top = 10f))
        col.addView(tv("Day One vérifie aussi tout seul chaque jour. Android demande parfois un tap de confirmation à l'installation.", 10f, DIM), lp(top = 8f))

        refreshers.forEach { it() }
        redraw()
        if (store.config.updateAvailable) showAvailable(null)
        return root
    }

    private fun installedName() = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun showAvailable(info: Updater.Info?) {
        update = info ?: update
        val n = info?.name?.takeIf { it.isNotBlank() }
        updateInfo.text = "Nouvelle version disponible" + (n?.let { " : $it" } ?: "")
        updateBtn.text = "INSTALLER LA MISE À JOUR"
        updateBtn.background = shape(cfg.accent, 12f, cfg.accent)
    }

    private fun onUpdateClick() {
        val info = update
        if (info != null) {
            if (!Updater.canInstall(a)) {
                Toast.makeText(a, "Autorise Day One à installer des applis, puis reviens et re-touche le bouton.", Toast.LENGTH_LONG).show()
                a.startActivity(Updater.permissionIntent(a))
                return
            }
            updateBtn.text = "TÉLÉCHARGEMENT…"
            thread {
                val ok = Updater.install(a, info)
                a.runOnUiThread {
                    updateBtn.text = if (ok) "INSTALLATION…" else "ÉCHEC, RÉESSAIE"
                }
            }
        } else {
            updateBtn.text = "RECHERCHE…"
            thread {
                val found = Updater.check(a)
                a.runOnUiThread {
                    if (found != null) { showAvailable(found); DayOneWidget.updateAll(a) } else {
                        updateInfo.text = "Version ${installedName()} · à jour"
                        updateBtn.text = "VÉRIFIER LES MISES À JOUR"
                    }
                }
            }
        }
    }
}
