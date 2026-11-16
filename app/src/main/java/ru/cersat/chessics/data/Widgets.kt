package ru.cersat.chessics.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.widget.RemoteViews
import ru.cersat.chessics.R
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.ui.MainActivity
import ru.cersat.chessics.ui.PieceArt
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

object Widgets {
	fun updateAll(ctx: Context) {
		val m = AppWidgetManager.getInstance(ctx)
		val s = m.getAppWidgetIds(ComponentName(ctx, StreakWidget::class.java))
		if (s.isNotEmpty()) StreakWidget.update(ctx, m, s)
		val p = m.getAppWidgetIds(ComponentName(ctx, PuzzleWidget::class.java))
		if (p.isNotEmpty()) PuzzleWidget.update(ctx, m, p)
		val l = m.getAppWidgetIds(ComponentName(ctx, LaunchWidget::class.java))
		if (l.isNotEmpty()) LaunchWidget.update(ctx, m, l)
	}

	fun openIntent(ctx: Context, action: String, code: Int): PendingIntent {
		val i = Intent(ctx, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
		return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
	}

	fun sizePx(ctx: Context, m: AppWidgetManager, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
		val o = m.getAppWidgetOptions(id)
		val d = ctx.resources.displayMetrics.density
		val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defW).takeIf { it > 0 } ?: defW
		val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, defH).takeIf { it > 0 } ?: defH
		return Pair((w * d).toInt().coerceIn(120, 1400), (h * d).toInt().coerceIn(80, 1400))
	}

	// Значки скинов, рисуются векторно
	fun drawIcon(c: Canvas, style: Int, cx: Float, cy: Float, r: Float, color: Int, accent: Int) {
		val p = Paint(Paint.ANTI_ALIAS_FLAG)
		when (style) {
			1 -> { // снежинка
				p.color = color; p.strokeWidth = r * 0.16f; p.strokeCap = Paint.Cap.ROUND
				for (i in 0 until 6) {
					val a = Math.PI / 3 * i
					val x = cx + cos(a).toFloat() * r; val y = cy + sin(a).toFloat() * r
					c.drawLine(cx, cy, x, y, p)
					for (sgn in intArrayOf(-1, 1)) {
						val bx = cx + cos(a).toFloat() * r * 0.6f; val by = cy + sin(a).toFloat() * r * 0.6f
						val b = a + sgn * Math.PI / 4
						c.drawLine(bx, by, bx + cos(b).toFloat() * r * 0.32f, by + sin(b).toFloat() * r * 0.32f, p)
					}
				}
			}
			2 -> { // молния
				p.color = accent
				val path = Path()
				path.moveTo(cx + r * 0.15f, cy - r); path.lineTo(cx - r * 0.55f, cy + r * 0.1f); path.lineTo(cx - r * 0.02f, cy + r * 0.1f)
				path.lineTo(cx - r * 0.2f, cy + r); path.lineTo(cx + r * 0.55f, cy - r * 0.15f); path.lineTo(cx + r * 0.02f, cy - r * 0.15f); path.close()
				p.setShadowLayer(r * 0.35f, 0f, 0f, accent)
				c.drawPath(path, p)
			}
			3 -> { // корона
				p.color = color
				val path = Path()
				path.moveTo(cx - r, cy + r * 0.6f); path.lineTo(cx - r, cy - r * 0.5f); path.lineTo(cx - r * 0.5f, cy)
				path.lineTo(cx, cy - r * 0.8f); path.lineTo(cx + r * 0.5f, cy); path.lineTo(cx + r, cy - r * 0.5f); path.lineTo(cx + r, cy + r * 0.6f); path.close()
				c.drawPath(path, p)
				p.color = accent
				c.drawCircle(cx, cy - r * 0.8f, r * 0.14f, p)
			}
			4 -> { // минимал — простая точка-кольцо
				p.color = color; p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.22f
				c.drawCircle(cx, cy, r * 0.7f, p)
			}
			else -> { // пламя (0) и радуга (5)
				val path = Path()
				path.moveTo(cx, cy - r)
				path.cubicTo(cx + r * 0.9f, cy - r * 0.2f, cx + r * 0.9f, cy + r * 0.6f, cx, cy + r)
				path.cubicTo(cx - r * 0.9f, cy + r * 0.6f, cx - r * 0.8f, cy - r * 0.1f, cx - r * 0.25f, cy - r * 0.45f)
				path.cubicTo(cx - r * 0.2f, cy - r * 0.1f, cx, cy - r * 0.2f, cx, cy - r)
				p.shader = LinearGradient(cx, cy - r, cx, cy + r, accent, if (style == 5) 0xFFFF2BD6.toInt() else 0xFFFF4D00.toInt(), Shader.TileMode.CLAMP)
				c.drawPath(path, p)
				p.shader = null
				p.color = 0xCCFFF3B0.toInt()
				val inner = Path()
				inner.moveTo(cx, cy + r * 0.85f)
				inner.cubicTo(cx - r * 0.45f, cy + r * 0.6f, cx - r * 0.3f, cy + r * 0.1f, cx, cy - r * 0.1f)
				inner.cubicTo(cx + r * 0.3f, cy + r * 0.2f, cx + r * 0.45f, cy + r * 0.6f, cx, cy + r * 0.85f)
				c.drawPath(inner, p)
			}
		}
	}

	fun background(c: Canvas, w: Float, h: Float, skin: Shop.Widget, radius: Float) {
		val p = Paint(Paint.ANTI_ALIAS_FLAG)
		p.shader = if (skin.style == 5)
			LinearGradient(0f, 0f, w, h, intArrayOf(0xFFFF5F6D.toInt(), 0xFFFFC371.toInt(), 0xFF47E891.toInt(), 0xFF4EA8FF.toInt(), 0xFF6A5BFF.toInt()), null, Shader.TileMode.CLAMP)
		else LinearGradient(0f, 0f, 0f, h, skin.top, skin.bottom, Shader.TileMode.CLAMP)
		c.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, p)
	}
}

class StreakWidget : AppWidgetProvider() {
	override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
		Store.get(ctx).checkStreak()
		update(ctx, m, ids)
	}

	override fun onAppWidgetOptionsChanged(ctx: Context, m: AppWidgetManager, id: Int, o: android.os.Bundle) = update(ctx, m, intArrayOf(id))

	companion object {
		fun render(ctx: Context, w: Int, h: Int): Bitmap {
			val st = Store.get(ctx)
			val skin = Shop.widget(ctx)
			val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
			val c = Canvas(bmp)
			val fw = w.toFloat(); val fh = h.toFloat()
			Widgets.background(c, fw, fh, skin, minOf(fw, fh) * 0.16f)
			val p = Paint(Paint.ANTI_ALIAS_FLAG)
			val unit = minOf(fh, fw / 2.2f)
			val icR = unit * 0.28f
			val wide = fw > fh * 1.6f
			if (wide) {
				Widgets.drawIcon(c, skin.style, unit * 0.45f, fh / 2, icR, skin.text, skin.accent)
				p.color = skin.text; p.isFakeBoldText = true; p.textSize = unit * 0.5f
				p.setShadowLayer(4f, 0f, 1f, 0x66000000)
				val num = "${st.streak}"
				c.drawText(num, unit * 0.85f, fh / 2 + unit * 0.12f, p)
				p.textSize = unit * 0.16f; p.isFakeBoldText = false
				c.drawText(Store.days(st.streak) + " подряд", unit * 0.87f, fh / 2 + unit * 0.34f, p)
				// справа: алмазы, заморозки, статус дня
				p.textSize = unit * 0.17f
				val right = fw - unit * 0.18f
				val l1 = "💎 ${st.diamonds}"; val l2 = "❄ ${st.freezes}"
				val l3 = if (st.activeToday) "✓ сегодня" else "сыграй сегодня"
				c.drawText(l1, right - p.measureText(l1), fh / 2 - unit * 0.18f, p)
				c.drawText(l2, right - p.measureText(l2), fh / 2 + unit * 0.06f, p)
				p.color = if (st.activeToday) skin.accent else skin.text
				p.isFakeBoldText = true
				c.drawText(l3, right - p.measureText(l3), fh / 2 + unit * 0.3f, p)
			} else {
				val u = minOf(fw, fh)
				Widgets.drawIcon(c, skin.style, fw / 2, fh * 0.3f, u * 0.17f, skin.text, skin.accent)
				p.color = skin.text; p.isFakeBoldText = true; p.textSize = u * 0.26f
				p.setShadowLayer(4f, 0f, 1f, 0x66000000)
				val num = "${st.streak}"
				c.drawText(num, (fw - p.measureText(num)) / 2, fh * 0.7f, p)
				p.textSize = u * 0.1f; p.isFakeBoldText = false
				val sub = if (st.activeToday) "✓ ${Store.days(st.streak)}" else "${Store.days(st.streak)} · сыграй!"
				c.drawText(sub, (fw - p.measureText(sub)) / 2, fh * 0.86f, p)
			}
			return bmp
		}

		fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
			for (id in ids) {
				val (w, h) = Widgets.sizePx(ctx, m, id, 250, 110)
				val v = RemoteViews(ctx.packageName, R.layout.widget_image)
				v.setImageViewBitmap(R.id.widget_img, render(ctx, w, h))
				v.setOnClickPendingIntent(R.id.widget_img, Widgets.openIntent(ctx, "ru.cersat.chessics.OPEN", 1))
				m.updateAppWidget(id, v)
			}
		}
	}
}

class PuzzleWidget : AppWidgetProvider() {
	override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = update(ctx, m, ids)
	override fun onAppWidgetOptionsChanged(ctx: Context, m: AppWidgetManager, id: Int, o: android.os.Bundle) = update(ctx, m, intArrayOf(id))

	companion object {
		fun render(ctx: Context, w: Int, h: Int): Bitmap {
			val skin = Shop.widget(ctx)
			val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
			val c = Canvas(bmp)
			Widgets.background(c, w.toFloat(), h.toFloat(), skin, minOf(w, h) * 0.08f)
			val pz = Puzzles.daily(ctx)
			val pad = minOf(w, h) * 0.05f
			val header = minOf(w, h) * 0.13f
			val size = minOf(w - 2 * pad, h - 2 * pad - header)
			val p = Paint(Paint.ANTI_ALIAS_FLAG)
			p.color = skin.text; p.isFakeBoldText = true; p.textSize = header * 0.55f
			p.setShadowLayer(3f, 0f, 1f, 0x66000000)
			val solved = Store.get(ctx).dailySolvedToday
			val title = if (pz == null) "Задача дня" else if (solved) "Задача дня ✓ решена" else "Задача дня · ${pz.goal.lowercase()}"
			c.drawText(title, pad, pad + header * 0.62f, p)
			if (pz != null) {
				val pos = Position(); pos.setFen(pz.fen)
				c.save()
				c.translate((w - size) / 2, pad + header)
				PieceArt.drawStatic(ctx, c, pos, size, pos.side == P.WHITE, Shop.board(ctx), Shop.pieces(ctx).set)
				c.restore()
			}
			return bmp
		}

		fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
			for (id in ids) {
				val (w, h) = Widgets.sizePx(ctx, m, id, 180, 210)
				val v = RemoteViews(ctx.packageName, R.layout.widget_image)
				v.setImageViewBitmap(R.id.widget_img, render(ctx, w, h))
				v.setOnClickPendingIntent(R.id.widget_img, Widgets.openIntent(ctx, "ru.cersat.chessics.DAILY", 2))
				m.updateAppWidget(id, v)
			}
		}
	}
}

// Виджет быстрого старта: при добавлении открывается выбор игры, нажатие сразу её запускает
class LaunchWidget : AppWidgetProvider() {
	override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = update(ctx, m, ids)
	override fun onAppWidgetOptionsChanged(ctx: Context, m: AppWidgetManager, id: Int, o: android.os.Bundle) = update(ctx, m, intArrayOf(id))

	override fun onDeleted(ctx: Context, ids: IntArray) {
		val st = Store.get(ctx)
		for (id in ids) st.setStr("launch_$id", "")
	}

	companion object {
		fun render(ctx: Context, id: Int, w: Int, h: Int): Bitmap {
			val skin = Shop.widget(ctx)
			val o = try { org.json.JSONObject(Store.get(ctx).str("launch_$id", "")) } catch (_: Exception) { null }
			val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
			val c = Canvas(bmp)
			val fw = w.toFloat(); val fh = h.toFloat()
			Widgets.background(c, fw, fh, skin, minOf(fw, fh) * 0.16f)
			val u = minOf(fh, fw / 2.2f)
			val p = Paint(Paint.ANTI_ALIAS_FLAG)
			// кнопка «играть»
			val r = u * 0.3f; val cx = u * 0.45f; val cy = fh / 2
			p.color = skin.accent
			c.drawCircle(cx, cy, r, p)
			p.color = if (skin.style == 3) skin.text else 0xFF15171C.toInt()
			val tri = Path()
			tri.moveTo(cx - r * 0.32f, cy - r * 0.45f); tri.lineTo(cx + r * 0.5f, cy); tri.lineTo(cx - r * 0.32f, cy + r * 0.45f); tri.close()
			c.drawPath(tri, p)
			p.color = skin.text
			p.setShadowLayer(4f, 0f, 1f, 0x66000000)
			p.isFakeBoldText = true; p.textSize = u * 0.22f
			val left = u * 0.9f
			val maxW = fw - left - u * 0.15f
			fun fit(s: String): String { var t = s; while (t.length > 3 && p.measureText(t) > maxW) t = t.dropLast(2) + "…"; return t }
			c.drawText(fit(o?.optString("title") ?: "Не настроен"), left, cy - u * 0.02f, p)
			p.isFakeBoldText = false; p.textSize = u * 0.14f
			c.drawText(fit(o?.optString("subtitle") ?: "удали и добавь виджет заново"), left, cy + u * 0.2f, p)
			return bmp
		}

		fun update(ctx: Context, m: AppWidgetManager, ids: IntArray) {
			for (id in ids) {
				val (w, h) = Widgets.sizePx(ctx, m, id, 250, 110)
				val v = RemoteViews(ctx.packageName, R.layout.widget_image)
				v.setImageViewBitmap(R.id.widget_img, render(ctx, id, w, h))
				val i = Intent(ctx, MainActivity::class.java).setAction("ru.cersat.chessics.LAUNCH")
					.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
					.setData(android.net.Uri.parse("chessics://launch/$id"))
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
				v.setOnClickPendingIntent(R.id.widget_img, PendingIntent.getActivity(ctx, 1000 + id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
				m.updateAppWidget(id, v)
			}
		}
	}
}

// Ежедневное напоминание в 20:00, если сегодня ещё не играл
object Reminder {
	const val CHANNEL = "streak"

	fun schedule(ctx: Context) {
		val am = ctx.getSystemService(AlarmManager::class.java)
		val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
		am.cancel(pi)
		if (!Store.get(ctx).flag("remind", true)) return
		val cal = Calendar.getInstance().apply {
			set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
			if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
		}
		am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
	}

	fun ensureChannel(ctx: Context) {
		val nm = ctx.getSystemService(NotificationManager::class.java)
		nm.createNotificationChannel(NotificationChannel(CHANNEL, "Серия", NotificationManager.IMPORTANCE_DEFAULT).apply {
			description = "Напоминание не потерять серию"
		})
	}
}

class ReminderReceiver : BroadcastReceiver() {
	override fun onReceive(ctx: Context, intent: Intent) {
		if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
			Reminder.schedule(ctx)
			return
		}
		val st = Store.get(ctx)
		st.checkStreak()
		Widgets.updateAll(ctx)
		if (st.activeToday) return
		if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
		Reminder.ensureChannel(ctx)
		val text = if (st.streak > 0) "Серия ${st.streak} ${Store.days(st.streak)} сгорит в полночь. Одна партия или задача — и огонь цел 🔥"
		else "Начни новую серию: сыграй партию или реши задачу дня"
		val n = android.app.Notification.Builder(ctx, Reminder.CHANNEL)
			.setSmallIcon(R.drawable.ic_notify)
			.setContentTitle("Chessics")
			.setContentText(text)
			.setStyle(android.app.Notification.BigTextStyle().bigText(text))
			.setContentIntent(Widgets.openIntent(ctx, "ru.cersat.chessics.OPEN", 3))
			.setAutoCancel(true)
			.build()
		ctx.getSystemService(NotificationManager::class.java).notify(1, n)
	}
}
