package fr.niakimbo.dayone

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Sélecteur de jeux : parcourir les prochaines sorties, chercher, suivre / ne plus suivre. */
class MainActivity : Activity() {
    private enum class Mode { BROWSE, MINE, SETTINGS }

    private val BG = Color.BLACK
    private val CARD = 0xFF111111.toInt()
    private val LINE = 0xFF2A2A2A.toInt()
    private val RED = 0xFFD71921.toInt()
    private val DIM = 0xFF8A8A8A.toInt()

    private lateinit var store: Store
    private val items = ArrayList<Game>()
    private var mode = Mode.BROWSE
    private var filter = "all"
    private var query = ""
    private var page = 1
    private var hasNext = false
    private var loading = false
    private var token = 0

    private lateinit var grid: GridView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var chips: View
    private lateinit var tabBrowse: TextView
    private lateinit var tabMine: TextView
    private lateinit var tabSettings: TextView
    private lateinit var settings: SettingsView
    private val chipViews = LinkedHashMap<String, TextView>()
    private val adapter = Adapter()
    private val ui = Handler(Looper.getMainLooper())
    private var debounce: Runnable? = null

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radius: Float, stroke: Int = 0, strokeDp: Float = 0f) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius * resources.displayMetrics.density
        if (strokeDp > 0f) setStroke(dp(strokeDp).coerceAtLeast(1), stroke)
    }

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        window.decorView.setBackgroundColor(BG)
        setContentView(buildUi())
        seed()
        setMode(Mode.BROWSE)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }

        // En-tête
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(14f), dp(20f), dp(6f))
        }
        head.addView(View(this).apply { background = shape(RED, 10f) }, LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { rightMargin = dp(10f) })
        head.addView(tv("DAY ONE", 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(tv("WIDGET +", 11f, Color.WHITE, true).apply {
            background = shape(Color.TRANSPARENT, 20f, Color.WHITE, 1f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener { pinWidget() }
        })
        root.addView(head)

        // Onglets
        val tabs = LinearLayout(this).apply { setPadding(dp(16f), dp(8f), dp(16f), dp(4f)) }
        tabBrowse = tv("SORTIES", 12f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, dp(10f)); setOnClickListener { setMode(Mode.BROWSE) } }
        tabMine = tv("MES JEUX", 12f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, dp(10f)); setOnClickListener { setMode(Mode.MINE) } }
        tabs.addView(tabBrowse, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6f) })
        tabSettings = tv("RÉGLAGES", 12f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, dp(10f)); setOnClickListener { setMode(Mode.SETTINGS) } }
        tabs.addView(tabMine, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6f) })
        tabs.addView(tabSettings, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabs)

        // Recherche
        search = EditText(this).apply {
            hint = "RECHERCHER UN JEU"
            setHintTextColor(DIM)
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            background = shape(0xFF161616.toInt(), 14f, LINE, 1f)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, _, _ -> v.clearFocus(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    debounce?.let { ui.removeCallbacks(it) }
                    debounce = Runnable { query = s?.toString()?.trim().orEmpty(); load(true) }.also { ui.postDelayed(it, 450) }
                }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16f), dp(8f), dp(16f), dp(4f))
        })

        // Filtres
        val row = LinearLayout(this).apply { setPadding(dp(16f), dp(6f), dp(16f), dp(6f)) }
        listOf("all" to "TOUT", "ps5" to "PS5", "xbox" to "XBOX", "pc" to "PC", "nintendo" to "SWITCH").forEach { (k, label) ->
            val c = tv(label, 11f, Color.WHITE, true).apply {
                setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
                setOnClickListener { filter = k; styleChips(); load(true) }
            }
            chipViews[k] = c
            row.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8f) })
        }
        chips = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        root.addView(chips)
        styleChips()

        status = tv("", 11f, DIM).apply { setPadding(dp(20f), dp(4f), dp(20f), dp(4f)) }
        root.addView(status)

        grid = GridView(this).apply {
            numColumns = 2
            horizontalSpacing = dp(12f)
            verticalSpacing = dp(12f)
            setPadding(dp(16f), dp(6f), dp(16f), dp(24f))
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isVerticalScrollBarEnabled = false
            adapter = this@MainActivity.adapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, s: Int) {}
                override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {
                    if (mode == Mode.BROWSE && hasNext && !loading && total > 0 && first + visible >= total - 4) load(false)
                }
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        settings = SettingsView(this, store)
        root.addView(settings.build(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        settings.root.visibility = View.GONE
        root.requestApplyInsets()
        return root
    }

    private fun styleChips() {
        chipViews.forEach { (k, v) ->
            val active = k == filter
            v.background = shape(if (active) RED else Color.TRANSPARENT, 20f, if (active) RED else 0xFF3A3A3A.toInt(), 1f)
            v.setTextColor(if (active) Color.WHITE else 0xFFB5B5B5.toInt())
        }
    }

    private fun styleTabs() {
        val mine = store.games().size
        tabMine.text = "MES JEUX ($mine)"
        listOf(tabBrowse to (mode == Mode.BROWSE), tabMine to (mode == Mode.MINE), tabSettings to (mode == Mode.SETTINGS)).forEach { (t, on) ->
            t.background = shape(if (on) Color.WHITE else Color.TRANSPARENT, 20f, if (on) Color.WHITE else 0xFF3A3A3A.toInt(), 1f)
            t.setTextColor(if (on) Color.BLACK else 0xFFB5B5B5.toInt())
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        val browse = m == Mode.BROWSE
        val sets = m == Mode.SETTINGS
        search.visibility = if (browse) View.VISIBLE else View.GONE
        chips.visibility = if (browse) View.VISIBLE else View.GONE
        grid.visibility = if (sets) View.GONE else View.VISIBLE
        status.visibility = if (sets) View.GONE else View.VISIBLE
        settings.root.visibility = if (sets) View.VISIBLE else View.GONE
        styleTabs()
        if (sets) { token++; return }
        if (browse) load(true) else {
            token++
            items.clear()
            items.addAll(store.games())
            status.text = if (items.isEmpty()) "AUCUN JEU SUIVI. VA DANS « SORTIES » ET APPUIE SUR + SUIVRE." else "LE WIDGET ENCHAÎNE CES JEUX : TOUCHE SON CÔTÉ DROIT POUR CHANGER."
            adapter.notifyDataSetChanged()
        }
    }

    private fun load(reset: Boolean) {
        if (mode != Mode.BROWSE) return
        if (reset) {
            token++
            page = 1
            hasNext = false
            items.clear()
            adapter.notifyDataSetChanged()
        }
        if (BuildConfig.RAWG_KEY.isBlank()) {
            status.text = "CLÉ API MANQUANTE : AJOUTE RAWG_KEY DANS local.properties"
            return
        }
        loading = true
        status.text = "CHARGEMENT…"
        val t = token
        val q = query
        val f = filter
        val pg = page
        thread {
            val res = runCatching { Rawg.list(q, f, pg) }
            runOnUiThread {
                if (t != token) return@runOnUiThread
                loading = false
                res.onSuccess { r ->
                    items.addAll(r.games)
                    hasNext = r.hasNext
                    page = pg + 1
                    status.text = if (items.isEmpty()) "AUCUN RÉSULTAT" else ""
                    adapter.notifyDataSetChanged()
                }.onFailure {
                    status.text = "IMPOSSIBLE DE JOINDRE RAWG. VÉRIFIE TA CONNEXION."
                }
            }
        }
    }

    /** Premier lancement : GTA VI est suivi d'office. */
    private fun seed() {
        if (store.seeded) return
        thread {
            val g = Rawg.details(972995) ?: return@thread
            store.seeded = true
            store.add(g)
            Sync.ensureCover(this, g)
            runOnUiThread {
                DayOneWidget.updateAll(this)
                styleTabs()
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun pinWidget() {
        val m = AppWidgetManager.getInstance(this)
        if (m.isRequestPinAppWidgetSupported) {
            m.requestPinAppWidget(ComponentName(this, DayOneWidget::class.java), null, null)
        } else {
            Toast.makeText(this, "Appui long sur l'écran d'accueil > Widgets > Day One", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggle(g: Game) {
        if (store.has(g.id)) store.remove(g.id) else {
            store.add(g)
            thread { Sync.ensureCover(this, g); runOnUiThread { DayOneWidget.updateAll(this) } }
        }
        DayOneWidget.updateAll(this)
        if (mode == Mode.MINE) { items.clear(); items.addAll(store.games()) }
        styleTabs()
        adapter.notifyDataSetChanged()
    }

    private inner class Holder(
        val root: LinearLayout, val img: ImageView, val chip: TextView,
        val name: TextView, val meta: TextView, val btn: TextView,
    )

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = items[i].id.toLong()

        private fun build(): Holder {
            val coverH = ((resources.displayMetrics.widthPixels - dp(44f)) / 2f * 9f / 16f).toInt()
            val root = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(CARD, 14f, LINE, 1f)
                clipToOutline = true
            }
            val frame = FrameLayout(this@MainActivity)
            val img = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1A1A1A.toInt()) }
            frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, coverH))
            val chip = tv("", 11f, Color.WHITE, true).apply { setPadding(dp(8f), dp(3f), dp(8f), dp(3f)) }
            frame.addView(chip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(8f), dp(8f), 0, 0) })
            root.addView(frame)
            val name = tv("", 12f, Color.WHITE, true).apply { maxLines = 2; minLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(10f), dp(9f), dp(10f), 0) }
            val meta = tv("", 10f, DIM).apply { maxLines = 2; minLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(10f), dp(3f), dp(10f), 0) }
            val btn = tv("", 11f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(9f), 0, dp(9f)) }
            root.addView(name)
            root.addView(meta)
            root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(10f), dp(8f), dp(10f), dp(10f)) })
            return Holder(root, img, chip, name, meta, btn).also { root.tag = it }
        }

        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val h = (convert?.tag as? Holder) ?: build()
            val g = items[i]
            Images.load(Rawg.sized(g.image, 640), h.img)
            val days = g.daysLeft()
            h.chip.text = when {
                days == null -> "TBA"
                days > 0 -> "J-$days"
                days == 0L -> "AUJOURD'HUI"
                else -> "SORTI"
            }
            h.chip.background = shape(if (days != null && days >= 0) RED else 0xFF3A3A3A.toInt(), 8f)
            h.name.text = g.name.uppercase()
            h.meta.text = (g.date?.let { "%02d.%02d.%d".format(it.dayOfMonth, it.monthValue, it.year) } ?: "DATE À CONFIRMER") +
                (if (g.platforms.isNotBlank()) "\n${g.platforms}" else "")
            val followed = store.has(g.id)
            h.btn.text = if (followed) "✓ SUIVI" else "+ SUIVRE"
            h.btn.setTextColor(if (followed) Color.BLACK else Color.WHITE)
            h.btn.background = shape(if (followed) Color.WHITE else Color.TRANSPARENT, 10f, Color.WHITE, 1f)
            h.root.setOnClickListener { toggle(g) }
            return h.root
        }
    }
}
