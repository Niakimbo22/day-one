package fr.niakimbo.dayone

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlin.concurrent.thread
import kotlin.math.sqrt

class DayOneWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { push(ctx, mgr, it) }
        // Mise à jour réseau (dates + covers + nouvelle version) en arrière-plan, puis nouveau rendu
        val pending = goAsync()
        thread {
            try {
                Sync.run(ctx)
                ids.forEach { push(ctx, mgr, it) }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) {
        push(ctx, mgr, id)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_NEXT -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val store = Store(ctx)
                    val n = store.games().size
                    if (n > 0) store.setIndex(id, (store.index(id) + 1) % n)
                    push(ctx, AppWidgetManager.getInstance(ctx), id)
                }
            }
            ACTION_TICK -> updateAll(ctx)
            else -> super.onReceive(ctx, intent)
        }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        Store(ctx).clearIndex(ids)
    }

    override fun onDisabled(ctx: Context) {
        alarm(ctx)?.cancel(tickIntent(ctx))
    }

    companion object {
        const val ACTION_NEXT = "fr.niakimbo.dayone.NEXT"
        const val ACTION_TICK = "fr.niakimbo.dayone.TICK"

        private const val DAY = 86_400_000L
        private const val HOUR = 3_600_000L
        private const val MIN = 60_000L

        fun updateAll(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            m.getAppWidgetIds(ComponentName(ctx, DayOneWidget::class.java)).forEach { push(ctx, m, it) }
        }

        private fun alarm(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

        private fun tickIntent(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, DayOneWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Prochain changement visible d'un chiffre (jours, heures ou minutes) : on réveille le widget à ce moment-là. */
        private fun scheduleTick(ctx: Context, c: Config, games: List<Game>) {
            val am = alarm(ctx) ?: return
            val step = when {
                c.seconds -> if (c.days) DAY else 0L
                c.minutes -> MIN
                c.hours -> HOUR
                else -> DAY
            }
            val now = System.currentTimeMillis()
            val r = games.mapNotNull { it.targetMillis() }.filter { it > now }.minOrNull()
            if (step == 0L || r == null) { am.cancel(tickIntent(ctx)); return }
            val at = now + (r - now) % step + 1500L
            am.set(AlarmManager.RTC, at, tickIntent(ctx))
        }

        fun push(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val store = Store(ctx)
            val games = store.games()
            val cfg = store.config
            val idx = if (games.isEmpty()) 0 else store.index(id).mod(games.size)

            val d = ctx.resources.displayMetrics.density
            val opts = mgr.getAppWidgetOptions(id)
            val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val wDp = opts.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
            val hDp = opts.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            var w = (wDp * d).toInt().coerceAtLeast(220)
            var h = (hDp * d).toInt().coerceAtLeast(100)
            val scale = sqrt(1_200_000f / (w.toFloat() * h)).coerceAtMost(1f)
            w = (w * scale).toInt()
            h = (h * scale).toInt()

            val cover = games.getOrNull(idx)?.let { Sync.coverBitmap(ctx, it) }
            val res = Renderer.render(d * scale, cfg, games, idx, w, h, cover)

            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            rv.setImageViewBitmap(R.id.img, res.bitmap)

            val ch = res.chrono
            if (ch != null) {
                // Le bitmap est étiré à la taille réelle du widget : on remet les mesures à l'échelle
                rv.setViewVisibility(R.id.chronoHost, View.VISIBLE)
                rv.setViewPadding(R.id.chronoHost, 0, 0, (ch.rightPx / scale).toInt(), (ch.bottomPx / scale).toInt())
                rv.setTextViewTextSize(R.id.chrono, TypedValue.COMPLEX_UNIT_PX, ch.sizePx / scale)
                rv.setChronometerCountDown(R.id.chrono, true)
                rv.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() + res.chronoMs, null, true)
            } else {
                rv.setChronometer(R.id.chrono, SystemClock.elapsedRealtime(), null, false)
                rv.setViewVisibility(R.id.chronoHost, View.GONE)
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val next = PendingIntent.getBroadcast(
                ctx, id,
                Intent(ctx, DayOneWidget::class.java).setAction(ACTION_NEXT).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.open, open)
            rv.setOnClickPendingIntent(R.id.next, if (games.size > 1) next else open)
            mgr.updateAppWidget(id, rv)
            scheduleTick(ctx, cfg, games)
        }
    }
}
