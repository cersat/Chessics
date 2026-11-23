package ru.cersat.chessics.ui

import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject
import ru.cersat.chessics.bots.Player
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.chess.V
import ru.cersat.chessics.data.Lan
import ru.cersat.chessics.data.Link

// Партия между двумя телефонами: один создаёт (хост), второй подключается
class LanScreen(a: MainActivity, private val auto: JSONObject? = null) : Screen(a) {
	private var autoDone = false
	private val st = a.store
	private var waiting: Lan.Waiting? = null
	private var discovery: Lan.Waiting? = null
	private var dialog: android.app.AlertDialog? = null
	private val found = LinkedHashMap<String, Pair<String, Int>>()
	private var foundBox: LinearLayout? = null

	private val bt get() = st.str("lanBt", "0") == "1"
	private val myName get() = st.str("lanName", Build.MODEL ?: "Игрок").ifBlank { "Игрок" }

	override fun build(): View {
		val box = ui.col(16, 14)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title("По Wi-Fi или Bluetooth")))
		val nameField = EditText(a).apply {
			setText(myName); hint = "твоё имя"; setTextColor(ui.t.text); setHintTextColor(ui.t.sub); setSingleLine()
			background = ui.round(ui.t.card2, 12f); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
			setOnFocusChangeListener { _, has -> if (!has) st.setStr("lanName", text.toString().trim()) }
		}
		val nc = ui.card()
		nc.addView(ui.text("Связь", 17f, bold = true))
		nc.addView(ui.space(8))
		nc.addView(ui.chips(listOf("Wi-Fi" to !bt, "Bluetooth" to bt), 2) { st.setStr("lanBt", "$it"); stopDiscovery(); a.refresh() })
		nc.addView(ui.space(8))
		nc.addView(ui.sub(if (bt) "Сначала спарь телефоны в системных настройках Bluetooth — потом выбери соседа из списка."
			else "Оба телефона должны быть в одной Wi-Fi сети (или один раздаёт точку доступа)."))
		nc.addView(ui.space(8))
		nc.addView(nameField, ui.lp())
		box.addView(nc)

		// создать
		val color = st.str("lanColor", "2").toInt()
		val clock = st.str("lanClock", "0").toInt().coerceIn(0, GameConfig.CLOCKS.size - 1)
		val variant = st.str("lanVariant", "0").toInt().coerceIn(0, V.NAMES.size - 1)
		val host = ui.card()
		host.addView(ui.text("Создать партию", 17f, bold = true))
		host.addView(ui.space(8))
		host.addView(ui.chips(listOf("Я белыми" to (color == 0), "Я чёрными" to (color == 1), "Случайно" to (color == 2)), 3) { st.setStr("lanColor", "$it"); a.refresh() })
		host.addView(ui.space(8))
		host.addView(ui.chips(GameConfig.CLOCKS.mapIndexed { i, (m, inc) -> GameConfig.clockLabel(m, inc) to (i == clock) }, 4) { st.setStr("lanClock", "$it"); a.refresh() })
		host.addView(ui.space(8))
		val customs = VariantStore.list(st).filter { it.players == 2 && !it.setup }
		val custom = st.str("lanCustom", "-1").toInt().takeIf { it in customs.indices } ?: -1
		host.addView(ui.chips(V.NAMES.mapIndexed { i, n -> n to (custom < 0 && i == variant) }, 4) { st.setStr("lanVariant", "$it"); st.setStr("lanCustom", "-1"); a.refresh() })
		if (customs.isNotEmpty()) {
			host.addView(ui.space(8))
			host.addView(ui.sub("Свои варианты из конструктора:"))
			host.addView(ui.chips(customs.mapIndexed { i, cv -> cv.name to (i == custom) }, 2) { st.setStr("lanCustom", "$it"); a.refresh() })
			if (custom >= 0) host.addView(ui.sub("Вариант целиком перешлётся сопернику, ставить его у себя не нужно. Часов в своих вариантах пока нет — контроль времени не действует."))
		}
		host.addView(ui.space(10))
		host.addView(ui.button("Создать и ждать соперника") { st.setStr("lanName", nameField.text.toString().trim()); startHost(color, clock, variant, if (custom >= 0) customs[custom] else null) })
		box.addView(host)

		// подключиться
		val join = ui.card()
		join.addView(ui.text("Подключиться", 17f, bold = true))
		join.addView(ui.space(8))
		if (bt) {
			if (!btAllowed()) {
				join.addView(ui.sub("Нужно разрешение «Устройства поблизости», чтобы увидеть спаренные телефоны."))
				join.addView(ui.space(8))
				join.addView(ui.button("Разрешить") { askBt() })
			} else {
				val list = Lan.paired(a)
				if (list.isEmpty()) join.addView(ui.sub("Спаренных устройств нет. Спарь телефоны в настройках Bluetooth."))
				for (d in list) join.addView(ui.button("📱 ${Lan.deviceName(d)}", false) {
					st.setStr("lanName", nameField.text.toString().trim())
					dialog = a.dialog("Подключаюсь…", ui.sub(Lan.deviceName(d)), "Отмена" to {}, cancelable = false)
					Lan.joinBt(a, d, { l -> onGuestLink(l) }, { e -> dialog?.dismiss(); a.toast("Не удалось: $e. Соперник нажал «Создать»?") })
				}.apply { layoutParams = ui.lp().apply { topMargin = ui.dp(6) } })
			}
		} else {
			join.addView(ui.sub("Партии в этой сети:"))
			val fb = ui.col(0, 6)
			foundBox = fb
			join.addView(fb, ui.lp())
			fillFound()
			join.addView(ui.space(10))
			val ip = EditText(a).apply {
				hint = "или адрес вручную: 192.168.1.5:40123"; setTextColor(ui.t.text); setHintTextColor(ui.t.sub); setSingleLine()
				background = ui.round(ui.t.card2, 12f); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
			}
			join.addView(ip, ui.lp())
			join.addView(ui.space(8))
			join.addView(ui.button("Подключиться по адресу", false) {
				val t = ip.text.toString().trim().split(":")
				val port = t.getOrNull(1)?.toIntOrNull()
				if (t.size != 2 || port == null) { a.toast("Формат: адрес:порт"); return@button }
				st.setStr("lanName", nameField.text.toString().trim())
				joinWifi(t[0], port)
			})
			startDiscovery()
		}
		box.addView(join)
		// запуск из виджета: сразу создать партию с сохранёнными настройками
		if (auto != null && !autoDone) {
			autoDone = true
			st.setStr("lanBt", if (auto.optBoolean("bt")) "1" else "0")
			BotRunner.main.post { stopDiscovery(); a.refresh(); startHost(auto.optInt("color", 2), auto.optInt("clock"), auto.optInt("variant")) }
		}
		return ui.scroll(box)
	}

	private fun btAllowed() = Build.VERSION.SDK_INT < 31 || a.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

	private fun askBt() {
		if (Build.VERSION.SDK_INT >= 31) a.requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 2)
		BotRunner.main.postDelayed({ a.refresh() }, 1500)
	}

	private fun fillFound() {
		val fb = foundBox ?: return
		fb.removeAllViews()
		if (found.isEmpty()) fb.addView(ui.sub("Ищу… (соперник должен нажать «Создать»)", 12f))
		for ((name, hp) in found) fb.addView(ui.button("📡 ${name.removePrefix("Chessics · ")}", false) { joinWifi(hp.first, hp.second) })
	}

	private fun startDiscovery() {
		if (discovery != null) return
		discovery = Lan.discover(a) { name, h, port -> found[name] = h to port; fillFound() }
	}

	private fun stopDiscovery() { discovery?.stop?.invoke(); discovery = null; found.clear() }

	private fun joinWifi(h: String, port: Int) {
		dialog = a.dialog("Подключаюсь…", ui.sub("$h:$port"), "Отмена" to {}, cancelable = false)
		Lan.joinWifi(h, port, { l -> onGuestLink(l) }, { e -> dialog?.dismiss(); a.toast("Не удалось подключиться: $e") })
	}

	private fun startHost(color: Int, clock: Int, variant: Int, custom: ru.cersat.chessics.chess.Variant? = null) {
		if (bt && !btAllowed()) { askBt(); return }
		val hostWhite = when (color) { 0 -> true; 1 -> false; else -> Math.random() < 0.5 }
		val (m, inc) = GameConfig.CLOCKS[clock]
		val fen = if (variant == V.CHESS960) V.random960() else Position.START
		val dealt = custom?.dealt() // перемешанную расстановку выбирает хозяин и шлёт её как есть
		val info = ui.sub("Жду соперника…", 14f)
		dialog = a.dialog("Партия создана", info, "Отмена" to { waiting?.stop?.invoke(); waiting = null }, cancelable = false)
		val onLink: (Link) -> Unit = { l ->
			waiting = null
			l.start()
			l.attach { o ->
				if (o.optString("t") == "hello" && dealt != null) {
					l.send(JSONObject().put("t", "start").put("name", myName).put("custom", dealt.json()).put("hostWhite", hostWhite))
					dialog?.dismiss()
					a.push(VariantGameScreen(a, dealt, if (hostWhite) "H" else "L", if (hostWhite) "L" else "H", l, o.optString("name", "Соперник"), dealt = true))
				} else if (o.optString("t") == "hello") {
					l.send(JSONObject().put("t", "start").put("name", myName).put("fen", fen).put("variant", variant)
						.put("m", m).put("inc", inc).put("hostWhite", hostWhite))
					dialog?.dismiss()
					val cfg = GameConfig(if (hostWhite) Player.HUMAN else Player(Player.Kind.REMOTE),
						if (hostWhite) Player(Player.Kind.REMOTE) else Player.HUMAN, m, inc, false, fen, variant)
					a.push(GameScreen(a, cfg, null, l, o.optString("name", "Соперник")))
				}
			}
		}
		val onErr: (String) -> Unit = { e -> dialog?.dismiss(); a.toast("Ошибка: $e") }
		waiting = if (bt) Lan.hostBt(a, onLink, onErr)
		else Lan.hostWifi(a, myName, { ip, port -> info.text = "Жду соперника…\nАдрес для ручного подключения: ${ip ?: "?"}:$port" }, onLink, onErr)
		if (bt) info.text = "Жду подключения по Bluetooth от «${myName}»…"
	}

	private fun onGuestLink(l: Link) {
		l.start()
		l.attach { o ->
			if (o.optString("t") == "start" && o.has("custom")) {
				dialog?.dismiss()
				val hostWhite = o.optBoolean("hostWhite")
				val v = try { ru.cersat.chessics.chess.Variant.from(o.getJSONObject("custom")) } catch (e: Exception) { a.toast("Соперник прислал вариант, который не читается"); return@attach }
				a.push(VariantGameScreen(a, v, if (hostWhite) "L" else "H", if (hostWhite) "H" else "L", l, o.optString("name", "Соперник"), dealt = true))
			} else if (o.optString("t") == "start") {
				dialog?.dismiss()
				val hostWhite = o.optBoolean("hostWhite")
				val cfg = GameConfig(if (hostWhite) Player(Player.Kind.REMOTE) else Player.HUMAN,
					if (hostWhite) Player.HUMAN else Player(Player.Kind.REMOTE),
					o.optInt("m"), o.optInt("inc"), false, o.optString("fen", Position.START), o.optInt("variant"))
				a.push(GameScreen(a, cfg, null, l, o.optString("name", "Соперник")))
			}
		}
		l.send(JSONObject().put("t", "hello").put("name", myName))
	}

	override fun dispose() { waiting?.stop?.invoke(); stopDiscovery() }
	override fun onPause() { stopDiscovery() }
}
