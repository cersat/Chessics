package ru.cersat.chessics.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import ru.cersat.chessics.bots.Player
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.chess.V
import ru.cersat.chessics.data.LaunchWidget

// Виджет быстрого старта: при добавлении открывается этот выбор, настройки хранятся для каждого виджета отдельно
object WidgetSetup {
	fun key(id: Int) = "launch_$id"

	fun save(a: MainActivity, widgetId: Int, cfg: JSONObject, title: String, subtitle: String) {
		cfg.put("title", title).put("subtitle", subtitle)
		a.store.setStr(key(widgetId), cfg.toString())
		LaunchWidget.update(a, AppWidgetManager.getInstance(a), intArrayOf(widgetId))
		a.setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
		a.finish()
	}

	fun load(a: MainActivity, widgetId: Int): JSONObject? = try { JSONObject(a.store.str(key(widgetId), "")) } catch (_: Exception) { null }

	// запуск игры из виджета
	fun launch(a: MainActivity, widgetId: Int) {
		val o = load(a, widgetId) ?: run { a.toast("Виджет не настроен — удали его и добавь заново"); return }
		when (o.optString("type")) {
			"setup" -> a.push(GameScreen(a, SetupScreen.configFrom(o), null))
			"four" -> {
				val arr = o.optJSONArray("seats")
				a.push(FourGameScreen(a, List(4) { arr?.optString(it, "M2") ?: "M2" }))
			}
			"liSeek", "liAi" -> a.push(LichessScreen(a, o))
			"lanHost" -> a.push(LanScreen(a, o))
		}
	}
}

class WidgetConfigScreen(a: MainActivity, private val widgetId: Int) : Screen(a) {
	private val st = a.store
	private var open = ""

	override fun build(): View {
		val box = ui.col(16, 12)
		box.addView(ui.title("Что запускать виджетом?"))
		box.addView(ui.sub("Выбери игру и её настройки — нажатие на виджет сразу её начнёт."))
		box.addView(item("🤖", "Игра с ботом") { a.push(SetupScreen(a, SetupScreen.Mode.BOT, widgetId)) })
		box.addView(item("👥", "Вдвоём на одном телефоне") { a.push(SetupScreen(a, SetupScreen.Mode.HVH, widgetId)) })
		box.addView(item("⚙", "Своя партия") { a.push(SetupScreen(a, SetupScreen.Mode.CUSTOM, widgetId)) })
		box.addView(item("🎨", "Шахматы на четверых") { a.push(FourSetupScreen(a, widgetId)) })
		box.addView(item("🌐", "Lichess: случайный соперник") { open = if (open == "seek") "" else "seek"; a.refresh() })
		if (open == "seek") box.addView(seekCard())
		box.addView(item("🌐", "Lichess: Stockfish на сервере") { open = if (open == "ai") "" else "ai"; a.refresh() })
		if (open == "ai") box.addView(aiCard())
		box.addView(item("📡", "Создать партию по Wi-Fi или Bluetooth") { open = if (open == "lan") "" else "lan"; a.refresh() })
		if (open == "lan") box.addView(lanCard())
		return ui.scroll(box)
	}

	private fun item(icon: String, title: String, f: () -> Unit): View {
		val r = ui.row(12)
		r.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
		r.addView(ui.text(icon, 24f))
		r.addView(ui.text(title, 16f, bold = true), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		r.addView(ui.text("›", 22f, ui.t.sub))
		ui.clickable(r, ui.t.card, 16f, f)
		return r
	}

	private fun variantChips(key: String) = ui.chips(V.NAMES.mapIndexed { i, n -> n to (i == st.str(key, "0").toInt()) }, 4) { st.setStr(key, "$it"); a.refresh() }

	private fun seekCard(): View {
		val c = ui.card()
		val tc = st.str("liClock", "0").toInt().coerceIn(0, LichessScreen.CLOCKS.size - 1)
		val rated = st.flag("liRated", false)
		c.addView(ui.chips(LichessScreen.CLOCKS.mapIndexed { i, (m, inc) -> "$m+$inc" to (i == tc) }, 5) { st.setStr("liClock", "$it"); a.refresh() })
		c.addView(ui.space(8))
		c.addView(ui.chips(listOf("Товарищеская" to !rated, "Рейтинговая" to rated), 2) { st.setFlag("liRated", it == 1); a.refresh() })
		c.addView(ui.space(8))
		c.addView(variantChips("liVariant"))
		c.addView(ui.space(10))
		c.addView(ui.button("📌 Поставить в виджет") {
			val (m, inc) = LichessScreen.CLOCKS[tc]
			val v = st.str("liVariant", "0").toInt()
			WidgetSetup.save(a, widgetId, JSONObject().put("type", "liSeek").put("clock", tc).put("rated", rated).put("variant", v),
				"Lichess", "$m+$inc · ${if (rated) "рейтинг" else "товарищеская"} · ${V.NAMES[v]}")
		})
		return c
	}

	private fun aiCard(): View {
		val c = ui.card()
		val lvl = st.str("liAi", "3").toInt()
		val tc = st.str("liAiTc", "1").toInt().coerceIn(0, LichessScreen.CHALLENGE_CLOCKS.size - 1)
		c.addView(ui.chips((1..8).map { "Ур. $it" to (it == lvl) }, 4) { st.setStr("liAi", "${it + 1}"); a.refresh() })
		c.addView(ui.space(8))
		c.addView(ui.chips(LichessScreen.CHALLENGE_CLOCKS.mapIndexed { i, (m, inc) -> "$m+$inc" to (i == tc) }, 4) { st.setStr("liAiTc", "$it"); a.refresh() })
		c.addView(ui.space(8))
		c.addView(variantChips("liVariant"))
		c.addView(ui.space(10))
		c.addView(ui.button("📌 Поставить в виджет") {
			val (m, inc) = LichessScreen.CHALLENGE_CLOCKS[tc]
			val v = st.str("liVariant", "0").toInt()
			WidgetSetup.save(a, widgetId, JSONObject().put("type", "liAi").put("level", lvl).put("clock", tc).put("variant", v),
				"Lichess Stockfish $lvl", "$m+$inc · ${V.NAMES[v]}")
		})
		return c
	}

	private fun lanCard(): View {
		val c = ui.card()
		val bt = st.str("lanBt", "0") == "1"
		val color = st.str("lanColor", "2").toInt()
		val clock = st.str("lanClock", "0").toInt().coerceIn(0, GameConfig.CLOCKS.size - 1)
		c.addView(ui.chips(listOf("Wi-Fi" to !bt, "Bluetooth" to bt), 2) { st.setStr("lanBt", "$it"); a.refresh() })
		c.addView(ui.space(8))
		c.addView(ui.chips(listOf("Я белыми" to (color == 0), "Я чёрными" to (color == 1), "Случайно" to (color == 2)), 3) { st.setStr("lanColor", "$it"); a.refresh() })
		c.addView(ui.space(8))
		c.addView(ui.chips(GameConfig.CLOCKS.mapIndexed { i, (m, inc) -> GameConfig.clockLabel(m, inc) to (i == clock) }, 4) { st.setStr("lanClock", "$it"); a.refresh() })
		c.addView(ui.space(8))
		c.addView(variantChips("lanVariant"))
		c.addView(ui.space(10))
		c.addView(ui.button("📌 Поставить в виджет") {
			val v = st.str("lanVariant", "0").toInt()
			val (m, inc) = GameConfig.CLOCKS[clock]
			WidgetSetup.save(a, widgetId, JSONObject().put("type", "lanHost").put("bt", bt).put("color", color).put("clock", clock).put("variant", v),
				if (bt) "Хост по Bluetooth" else "Хост по Wi-Fi", "${arrayOf("белыми", "чёрными", "случайный цвет")[color]} · ${GameConfig.clockLabel(m, inc)} · ${V.NAMES[v]}")
		})
		return c
	}
}

// Отдельная запись в манифесте: Android открывает её при добавлении виджета быстрого старта
class WidgetConfigActivity : MainActivity()
