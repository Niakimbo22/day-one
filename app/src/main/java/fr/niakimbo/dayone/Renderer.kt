package fr.niakimbo.dayone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Dessine tout le widget dans un seul bitmap : cover, voiles, trame de points et compte à rebours
 * (points façon Nothing ou texte monospace). Les secondes en direct sont un chronomètre système
 * posé par-dessus (voir [Chrono]) : un bitmap ne peut pas tourner tout seul.
 */
object Renderer {
    private const val WHITE = Color.WHITE
    private const val DIM = 0xB3FFFFFF.toInt()
    private const val DAY = 86_400_000L
    private const val HOUR = 3_600_000L
    private const val MIN = 60_000L

    private val GLYPHS = mapOf(
        '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
        '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
        'T' to listOf("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
        'B' to listOf("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
        'A' to listOf("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
        'G' to listOf("01110", "10001", "10000", "10111", "10001", "10001", "01111"),
        'O' to listOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    )

    private val DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Où poser le chronomètre système : taille du texte et marges, en pixels du bitmap. */
    class Chrono(val sizePx: Float, val rightPx: Int, val bottomPx: Int)

    /** [chronoMs] = temps restant que le chronomètre doit décompter, à l'instant du rendu. */
    class Result(val bitmap: Bitmap, val chrono: Chrono?, val chronoMs: Long)

    private class Group(val value: String, val label: String)

    private class Model(
        val groups: List<Group>,
        val chronoMs: Long?,
        val singleLabel: String,
        val chronoLabel: String,
    )

    private class RightOut(val width: Float, val chrono: Chrono?, val chronoMs: Long)

    private fun mono(bold: Boolean) = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)

    private fun text(size: Float, color: Int, bold: Boolean = false, spacing: Float = 0.1f) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mono(bold)
            textSize = size
            this.color = color
            letterSpacing = spacing
        }

    /** Même format que le Chronometer d'Android : M:SS en dessous d'une heure, H:MM:SS au-dessus. */
    fun chronoText(ms: Long): String {
        val t = max(0L, ms) / 1000
        val s = t % 60
        val m = (t / 60) % 60
        val h = t / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun model(g: Game, c: Config, nowMs: Long): Model {
        val target = g.targetMillis() ?: return Model(listOf(Group("TBA", "")), null, "À CONFIRMER", "")
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val cal = ChronoUnit.DAYS.between(today, g.date)
        if (cal < 0) return Model(emptyList(), null, "DISPONIBLE", "")
        if (cal == 0L) return Model(listOf(Group("GO", "")), null, "C'EST AUJOURD'HUI", "")

        val r = max(0L, target - nowMs)
        var d = c.days
        if (!d && !c.hours && !c.minutes && !c.seconds) d = true
        val h = c.hours && !c.seconds
        val m = c.minutes && !c.seconds
        val multi = c.hours || c.minutes || c.seconds
        val shown = listOf(d, h, m, c.seconds).count { it }
        val groups = ArrayList<Group>()
        if (d) {
            val v = if (multi) r / DAY else cal
            groups += Group(v.toString(), if (shown > 1) "J" else if (v == 1L) "JOUR" else "JOURS")
        }
        if (h) {
            val base = if (d) r % DAY else r
            val v = base / HOUR
            groups += Group("%02d".format(v), if (shown > 1) "H" else if (v == 1L) "HEURE" else "HEURES")
        }
        if (m) {
            val base = if (h) r % HOUR else if (d) r % DAY else r
            val v = base / MIN
            groups += Group("%02d".format(v), if (shown > 1) "MIN" else "MINUTES")
        }
        val chrono = if (c.seconds) (if (d) r % DAY else r) else null
        val label = groups.firstOrNull()?.label ?: ""
        return Model(groups, chrono, label, if (d) "JOURS · H:MIN:SEC" else "H:MIN:SEC")
    }

    fun render(
        d: Float, c: Config, games: List<Game>, index: Int, w: Int, h: Int,
        cover: Bitmap?, nowMs: Long = System.currentTimeMillis(), drawChrono: Boolean = false,
    ): Result {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()

        cv.drawColor(0xFF080808.toInt())
        if (cover != null) {
            val s = max(wf / cover.width, hf / cover.height)
            val dw = cover.width * s
            val dh = cover.height * s
            cv.drawBitmap(cover, null, RectF((wf - dw) / 2, (hf - dh) / 2, (wf + dw) / 2, (hf + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val bg = Paint().apply { shader = LinearGradient(0f, 0f, wf, hf, 0xFF1C1C1C.toInt(), 0xFF050505.toInt(), Shader.TileMode.CLAMP) }
            cv.drawRect(0f, 0f, wf, hf, bg)
        }

        // Voiles sombres : lisibilité du texte, intensité réglable
        val k = c.dim.coerceIn(0, 100) / 100f
        fun a(color: Int) = Color.argb((Color.alpha(color) * k).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        if (k > 0f) {
            cv.drawRect(0f, 0f, wf, hf, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, hf, intArrayOf(a(0x66000000), a(0x22000000), a(0xEE000000.toInt())), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            })
            cv.drawRect(0f, 0f, wf, hf, Paint().apply {
                shader = LinearGradient(0f, 0f, wf * 0.75f, 0f, a(0xB3000000.toInt()), a(0x00000000), Shader.TileMode.CLAMP)
            })
            cv.drawRect(0f, 0f, wf, hf, Paint().apply {
                shader = LinearGradient(wf, 0f, wf * 0.4f, 0f, a(0xA6000000.toInt()), a(0x00000000), Shader.TileMode.CLAMP)
            })
        }

        if (c.showGrid) {
            val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
            val step = 7f * d
            var y = step / 2
            while (y < hf) {
                var x = step / 2
                while (x < wf) { cv.drawCircle(x, y, 0.7f * d, grid); x += step }
                y += step
            }
        }

        val compact = hf < 125f * d
        val pad = (if (compact) 12f else 16f) * d
        val game = games.getOrNull(index)
        var chrono: Chrono? = null
        var chronoMs = 0L
        if (game == null) empty(cv, d, c, pad, hf) else {
            val m = model(game, c, nowMs)
            val out = right(cv, d, c, pad, wf, hf, compact, m, drawChrono)
            chrono = out.chrono
            chronoMs = out.chronoMs
            top(cv, d, c, pad, wf, game, games.size, index)
            bottomLeft(cv, d, c, pad, wf, hf, game, out.width, compact)
        }
        return Result(roundCorners(bmp, c.corner * d), chrono, chronoMs)
    }

    private fun roundCorners(src: Bitmap, r: Float): Bitmap {
        if (r <= 0f) return src
        val mask = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        Canvas(src).drawBitmap(mask, 0f, 0f, p)
        mask.recycle()
        return src
    }

    private fun empty(cv: Canvas, d: Float, c: Config, pad: Float, h: Float) {
        cv.drawCircle(pad + 4f * d, h / 2 - 22f * d, 4f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.accent })
        cv.drawText("AUCUN JEU", pad, h / 2 + 4f * d, text(20f * d, WHITE, true))
        cv.drawText("TOUCHE POUR EN CHOISIR", pad, h / 2 + 24f * d, text(10f * d, DIM))
    }

    private fun top(cv: Canvas, d: Float, c: Config, pad: Float, w: Float, g: Game, total: Int, index: Int) {
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.accent }
        cv.drawCircle(pad + 3.5f * d, pad + 5f * d, 3.5f * d, accent)
        if (c.showPlatforms) cv.drawText(g.platforms.ifBlank { "DAY ONE" }, pad + 13f * d, pad + 8.5f * d, text(10f * d, DIM))

        if (c.updateAvailable) {
            val t = text(8f * d, WHITE, true, 0.15f)
            val tw = t.measureText("MAJ")
            val x = w / 2 - tw / 2
            cv.drawRoundRect(RectF(x - 6f * d, pad - 2f * d, x + tw + 6f * d, pad + 12f * d), 7f * d, 7f * d, accent)
            cv.drawText("MAJ", x, pad + 8.2f * d, t)
        }

        if (c.showPagination) {
            if (total in 2..8) {
                val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
                val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
                for (i in 0 until total) {
                    val cx = w - pad - 3f * d - (total - 1 - i) * 10f * d
                    cv.drawCircle(cx, pad + 5f * d, if (i == index) 3f * d else 2f * d, if (i == index) on else off)
                }
            } else if (total > 8) {
                val t = text(10f * d, DIM)
                val s = "${index + 1}/$total"
                cv.drawText(s, w - pad - t.measureText(s), pad + 8.5f * d, t)
            }
        }
    }

    private fun bottomLeft(cv: Canvas, d: Float, c: Config, pad: Float, w: Float, h: Float, g: Game, rightW: Float, compact: Boolean) {
        val labelsBelow = rightW > 0f && w < 230f * d && g.date != null && (c.hours || c.minutes || c.seconds)
        if (c.showDate && !labelsBelow) {
            val dateText = g.date?.format(DATE_FMT) ?: "DATE À CONFIRMER"
            cv.drawText(dateText, pad, h - pad, text((if (compact) 9f else 10f) * d, DIM))
        }
        if (!c.showName) return

        val reserve = if (rightW > 0f) rightW + 14f * d else w * 0.36f
        val maxW = (w - 2 * pad - reserve).toInt().coerceAtLeast((w * 0.28f).toInt())
        val bottom = h - pad - (if (c.showDate && !labelsBelow) (if (compact) 9f else 10f) * d + 7f * d else 0f)
        val topLimit = pad + (if (compact) 16f else 22f) * d
        val name = g.name.uppercase()
        var size = 22f * d * c.nameScale.coerceIn(50, 160) / 100f
        var layout: StaticLayout
        while (true) {
            layout = StaticLayout.Builder.obtain(name, 0, name.length, text(size, WHITE, true, 0.02f), maxW)
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            val cut = (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
            if ((!cut && layout.height <= bottom - topLimit) || size <= 9f * d) break
            size -= d * 0.5f
        }
        cv.save()
        cv.translate(pad, bottom - layout.height)
        layout.draw(cv)
        cv.restore()
    }

    /** Bloc de droite : groupes de chiffres, étiquettes, et emplacement du chronomètre des secondes. */
    private fun right(
        cv: Canvas, d: Float, c: Config, pad: Float, w: Float, h: Float,
        compact: Boolean, m: Model, drawChrono: Boolean,
    ): RightOut {
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.accent }
        val labelSize = (if (compact) 8.5f else 10f) * d
        val lp = text(labelSize, WHITE, true, 0.2f)
        val rightX = w - pad
        val baseline = h - pad
        val chronoMs = m.chronoMs
        val multi = m.groups.size >= 2 && chronoMs == null

        // Étiquette unique, alignée à droite (sauf quand chaque groupe a la sienne)
        val label = if (chronoMs != null) m.chronoLabel else if (multi) "" else m.singleLabel
        if (label.isNotEmpty()) {
            val lw = lp.measureText(label)
            cv.drawText(label, rightX - lw, baseline, lp)
            cv.drawCircle(rightX - lw - 8f * d, baseline - labelSize * 0.36f, 3f * d, accent)
        }

        var bottom = baseline - (if (compact) 12f else 19f) * d
        var chrono: Chrono? = null
        var occupied = 0f

        if (chronoMs != null) {
            val chars = chronoText(chronoMs).length
            val areaW = w * 0.52f
            val sizeMax = (if (m.groups.isEmpty()) h * 0.24f else h * 0.14f) * c.numScale.coerceIn(50, 160) / 100f
            val size = min(sizeMax, areaW / (chars * 0.62f))
            val edge = baseline - labelSize * 1.5f
            chrono = Chrono(size, pad.toInt(), (h - edge).toInt())
            occupied = chars * 0.62f * size
            if (drawChrono) {
                val p = text(size, WHITE, true, 0f)
                val t = chronoText(chronoMs)
                cv.drawText(t, rightX - p.measureText(t), edge - size * 0.2f, p)
            }
            bottom = edge - size * 1.25f - (if (compact) 2f else 5f) * d
        }

        if (m.groups.isEmpty()) return RightOut(occupied, chrono, chronoMs ?: 0L)

        val n = m.groups.size
        val narrow = w < 230f * d
        val areaW = if (narrow) w * 0.46f else min(w * 0.62f, w * (0.40f + 0.09f * (n - 1)))
        val topLimit = pad + (if (compact) 17f else 24f) * d
        val availH = bottom - topLimit
        val scale = c.numScale.coerceIn(50, 160) / 100f
        var numW = 0f
        val xs = FloatArray(n)
        val ws = FloatArray(n)

        if (c.dots) {
            val cols = m.groups.sumOf { it.value.length * 6 - 1 } + (n - 1) * 3
            val uCap = (if (n > 1) h * 0.34f else h * 0.42f) / 7f
            val u = min(min(uCap * scale, availH / 7f), areaW / cols)
            if (u >= 2f * d) {
                numW = cols * u
                var x = rightX - numW
                val topY = bottom - 7f * u
                val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
                val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
                for ((gi, g) in m.groups.withIndex()) {
                    xs[gi] = x
                    ws[gi] = (g.value.length * 6 - 1) * u
                    for ((i, ch) in g.value.withIndex()) {
                        val rows = GLYPHS[ch] ?: continue
                        for (r in 0 until 7) for (col in 0 until 5) {
                            val cx = x + (i * 6 + col) * u + u / 2
                            val cy = topY + r * u + u / 2
                            if (rows[r][col] == '1') cv.drawCircle(cx, cy, u * 0.4f, on) else cv.drawCircle(cx, cy, u * 0.12f, off)
                        }
                    }
                    x += ws[gi] + 3 * u
                }
            }
        } else {
            val totalChars = m.groups.sumOf { it.value.length } + (n - 1) * 1.4f
            val size = min(min(h * 0.36f * scale, areaW / (totalChars * 0.62f)), availH / 0.74f)
            if (size >= 6f * d) {
                val p = text(size, WHITE, true, 0f)
                val gap = size * 0.85f
                numW = m.groups.sumOf { p.measureText(it.value).toDouble() }.toFloat() + (n - 1) * gap
                var x = rightX - numW
                for ((gi, g) in m.groups.withIndex()) {
                    xs[gi] = x
                    ws[gi] = p.measureText(g.value)
                    cv.drawText(g.value, x, bottom, p)
                    x += ws[gi] + gap
                }
            }
        }

        if (multi && numW > 0f) {
            for ((gi, g) in m.groups.withIndex()) {
                val lw = lp.measureText(g.label)
                val lx = xs[gi] + (ws[gi] - lw) / 2
                cv.drawText(g.label, lx, baseline, lp)
                if (gi == 0) cv.drawCircle(lx - 8f * d, baseline - labelSize * 0.36f, 3f * d, accent)
            }
        }
        return RightOut(max(numW, occupied), chrono, chronoMs ?: 0L)
    }
}
