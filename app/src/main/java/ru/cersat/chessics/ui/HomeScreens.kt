package ru.cersat.chessics.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject
import ru.cersat.chessics.bots.AlgoBot
import ru.cersat.chessics.bots.Player
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.chess.V
import ru.cersat.chessics.data.Puzzles
import ru.cersat.chessics.data.Shop
import ru.cersat.chessics.data.Store
import ru.cersat.chessics.data.Widgets
import java.time.LocalDate

data class GameConfig(
	val white: Player,
	val black: Player,
	val minutes: Int = 0,
	val inc: Int = 0,
	val faceToFace: Boolean = false,
	val startFen: String = Position.START,
	val variant: Int = V.STANDARD,
	val training: String = "" // id позиции тренажёра эндшпиля
) {
	val hasClock get() = minutes > 0
	val hvh get() = white.isHuman && black.isHuman
	val remote get() = white.kind == Player.Kind.REMOTE || black.kind == Player.Kind.REMOTE
	val bot: Player? get() = when {
		remote -> null
		white.isHuman && !black.isHuman -> black
		black.isHuman && !white.isHuman -> white
		else -> null
	}
	val humanColor: Int get() = if (white.isHuman && !black.isHuman) P.WHITE else if (black.isHuman && !white.isHuman) P.BLACK else -1

	fun toJson() = JSONObject().put("w", white.encode()).put("b", black.encode()).put("m", minutes).put("i", inc).put("f", faceToFace).put("fen", startFen).put("v", variant).put("tr", training)

	companion object {
		fun fromJson(o: JSONObject) = GameConfig(Player.decode(o.getString("w")), Player.decode(o.getString("b")),
			o.optInt("m"), o.optInt("i"), o.optBoolean("f"), o.optString("fen", Position.START), o.optInt("v"), o.optString("tr", ""))

		val CLOCKS = listOf(0 to 0, 1 to 0, 3 to 2, 5 to 0, 10 to 0, 15 to 10, 30 to 0)
		fun clockLabel(m: Int, i: Int) = if (m == 0) "Без часов" else "$m+$i"
	}
}

// Иконка серии (пламя или значок скина) для интерфейса
class IconView(ctx: Context, private val style: Int, private val color: Int, private val accent: Int) : View(ctx) {
	override fun onDraw(c: Canvas) {
		val r = minOf(width, height) / 2f * 0.9f
		Widgets.drawIcon(c, style, width / 2f, height / 2f, r, color, accent)
	}
}

class HomeScreen(a: MainActivity) : Screen(a) {
	override val tab = 0
	private val st = a.store

	override fun build(): View {
		val box = ui.col(16, 14)
		val head = ui.row(10)
		val title = ui.title("Chessics")
		// пасхалка: семь раз постучать по названию
		var knocks = 0
		title.setOnClickListener {
			knocks++
			if (knocks == 7) ru.cersat.chessics.data.Eggs.find(st, "knock")?.let { a.toast(it); a.refresh() } ?: a.toast("Кто там?")
		}
		head.addView(title, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		ru.cersat.chessics.data.Eggs.onHome(st).takeIf { it.isNotEmpty() }?.let { msgs -> BotRunner.main.post { a.toast(msgs.joinToString("\n")) } }
		head.addView(pill("💎 ${st.diamonds}"))
		head.addView(pill("❄ ${st.freezes}"))
		box.addView(head)
		box.addView(streakCard())
		box.addView(goalsCard())

		val saved = st.str("current", "")
		if (saved.isNotEmpty()) {
			val c = ui.card()
			try {
				val cfg = GameConfig.fromJson(JSONObject(saved).getJSONObject("cfg"))
				c.addView(ui.text("Незаконченная партия", 17f, bold = true))
				c.addView(ui.sub("${cfg.white.shortName} — ${cfg.black.shortName}"))
				c.addView(ui.space(10))
				val r = ui.row(8)
				r.addView(ui.button("Продолжить") { a.push(GameScreen(a, cfg, JSONObject(saved))) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
				r.addView(ui.button("Сбросить", false) { st.setStr("current", ""); a.refresh() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
				c.addView(r)
				box.addView(c)
			} catch (e: Exception) { st.setStr("current", "") }
		}

		box.addView(modeCard("🤖", "Играть с ботом", "Stockfish 1350–3000 и полная сила, Материал, Алгоритм, Случайный") {
			a.push(SetupScreen(a, SetupScreen.Mode.BOT))
		})
		box.addView(modeCard("👥", "Вдвоём на одном телефоне", "Телефон — это доска: лицом к лицу или передавая телефон, с шахматными часами") {
			a.push(SetupScreen(a, SetupScreen.Mode.HVH))
		})
		box.addView(modeCard("🌐", "Онлайн · Lichess", "Живые соперники, вызов друга, Stockfish на сервере lichess. Нужен свой аккаунт") {
			a.push(LichessScreen(a))
		})
		box.addView(modeCard("🎨", "Шахматы на четверых", "Две команды на доске 14×14: люди и боты в любом составе") {
			a.push(FourSetupScreen(a))
		})
		box.addView(modeCard("🧩", "Конструктор вариантов", "Своя доска, свои фигуры и правила — и партия против ботов или вдвоём") {
			a.push(VariantListScreen(a))
		})
		box.addView(modeCard("📡", "По Wi-Fi или Bluetooth", "Партия между двумя телефонами рядом, без интернета") {
			a.push(LanScreen(a))
		})
		box.addView(modeCard("📚", "Тренировка", "Дебюты ход за ходом и эндшпиль против Stockfish") {
			a.push(TrainingScreen(a))
		})
		box.addView(modeCard("⚙", "Своя партия", "Любой игрок за каждую сторону: бот против бота, человек за обе стороны…") {
			a.push(SetupScreen(a, SetupScreen.Mode.CUSTOM))
		})
		box.addView(dailyCard())
		return ui.scroll(box)
	}

	private fun pill(s: String) = ui.text(s, 14f, ui.t.text, true).apply {
		setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
		background = ui.round(ui.t.card, 20f)
	}

	private fun streakCard(): View {
		val c = ui.card(18)
		val skin = Shop.widget(a)
		val r = ui.row(14)
		r.addView(IconView(a, 0, ui.t.text, if (st.activeToday) 0xFFFFD166.toInt() else 0xFF777777.toInt()), LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)))
		val txt = ui.col()
		txt.addView(ui.text("${st.streak}", 38f, ui.t.text, true))
		txt.addView(ui.sub(Store.days(st.streak) + " подряд · рекорд ${st.bestStreak}"))
		r.addView(txt, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		c.addView(r)
		c.addView(ui.space(10))
		c.addView(ui.text(if (st.activeToday) "✓ Сегодня день засчитан" else "Сыграй партию с ботом или реши задачу, чтобы продлить серию",
			13f, if (st.activeToday) ui.t.accent else ui.t.sub))
		c.addView(ui.space(12))
		// неделя
		val week = ui.row(6)
		val active = st.daySet("activeDays")
		val frozen = st.daySet("frozenDays")
		val names = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
		for (i in 6 downTo 0) {
			val d = st.today - i
			val dow = LocalDate.ofEpochDay(d).dayOfWeek.value - 1
			val isActive = d in active; val isFrozen = d in frozen; val isToday = i == 0
			val circle = ui.text(if (isActive) "🔥" else if (isFrozen) "❄" else "", 15f, ui.t.text, gravity = Gravity.CENTER)
			circle.background = when {
				isActive -> ui.round(ui.t.accent, 20f)
				isFrozen -> ui.round(0xFF2E6FA8.toInt(), 20f)
				isToday -> ui.round(ui.t.card2, 20f, 2, ui.t.accent)
				else -> ui.round(ui.t.card2, 20f)
			}
			val cell = ui.col(0, 4, circle, ui.text(names[dow], 11f, if (isToday) ui.t.accent else ui.t.sub, isToday, Gravity.CENTER))
			circle.layoutParams = LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply { gravity = Gravity.CENTER_HORIZONTAL }
			week.addView(cell, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		}
		c.addView(week)
		c.addView(ui.space(10))
		c.addView(ui.sub("Заморозки: ${st.freezes} из ${st.maxFreezes} — сами спасают серию, если пропустишь день. Скин виджета: ${skin.name}", 12f))
		return c
	}

	private fun goalsCard(): View {
		val c = ui.card()
		c.addView(ui.text("🎯 Цели", 17f, bold = true))
		for (daily in listOf(true, false)) {
			c.addView(ui.space(8))
			val list = if (daily) ru.cersat.chessics.data.Goals.daily(st.today) else ru.cersat.chessics.data.Goals.weekly(st.week)
			c.addView(ui.sub(if (daily) "На сегодня · все три — ещё +3 💎" else "На неделю", 12f))
			list.forEachIndexed { i, g ->
				val prog = minOf(st.goalProgress(daily, i), g.target)
				val claimed = st.goalClaimed(daily, i)
				val r = ui.row(10)
				r.setPadding(0, ui.dp(6), 0, ui.dp(2))
				val left = ui.col(0, 4, ui.text(g.title, 14f, if (claimed) ui.t.sub else ui.t.text), ui.progress(prog, g.target))
				r.addView(left, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
				when {
					claimed -> r.addView(ui.text("✓", 18f, ui.t.accent, true))
					prog >= g.target -> r.addView(ui.button("+${g.reward} 💎") { val got = st.claimGoal(daily, i); if (got > 0) a.toast("+$got 💎"); a.refresh() })
					else -> r.addView(ui.text("${if (g.max) "лучшее " else ""}$prog/${g.target}", 12f, ui.t.sub))
				}
				c.addView(r)
			}
		}
		return c
	}

	private fun modeCard(icon: String, title: String, desc: String, onClick: () -> Unit): View {
		val r = ui.row(14)
		r.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
		r.addView(ui.text(icon, 30f))
		r.addView(ui.col(0, 2, ui.text(title, 17f, bold = true), ui.sub(desc)), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		r.addView(ui.text("›", 26f, ui.t.sub))
		ui.clickable(r, ui.t.card, 18f, onClick)
		return r
	}

	private fun dailyCard(): View {
		val pz = Puzzles.daily(a) ?: return View(a)
		val r = ui.row(14)
		r.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
		val img = ImageView(a)
		val size = ui.dp(96)
		val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
		val pos = Position(); pos.setFen(pz.fen)
		PieceArt.drawStatic(a, Canvas(bmp), pos, size.toFloat(), pos.side == P.WHITE, Shop.board(a), Shop.pieces(a).set)
		img.setImageBitmap(bmp)
		r.addView(img, LinearLayout.LayoutParams(size, size))
		val solved = st.dailySolvedToday
		r.addView(ui.col(0, 4,
			ui.text("🗓 Задача дня", 17f, bold = true),
			ui.sub("${pz.goal} · ход ${if (pos.side == P.WHITE) "белых" else "чёрных"}"),
			ui.text(if (solved) "✓ Решена сегодня" else "+2 💎 бонус за решение", 13f, if (solved) ui.t.accent else ui.t.text)
		), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		ui.clickable(r, ui.t.card, 18f) { a.push(PuzzleScreen(a, pz, true)) }
		return r
	}
}

class SetupScreen(a: MainActivity, private val mode: Mode, private val widgetId: Int = -1) : Screen(a) {
	enum class Mode { BOT, HVH, CUSTOM }

	private val st = a.store
	private var bot = Player.decode(st.str("setupBot", "STOCKFISH:1350"))
	private var color = st.str("setupColor", "0").toInt() // 0 белые, 1 чёрные, 2 случайно
	private var clock = st.str("setupClock_$mode", "0").toInt()
	private var f2f = st.flag("setupF2F", true)
	private var white = Player.decode(st.str("setupW", "HUMAN:0"))
	private var black = Player.decode(st.str("setupB", "MATERIAL:3"))
	private var variant = st.str("setupVariant", "0").toInt()

	override fun build(): View {
		val box = ui.col(16, 14)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title(when (mode) {
			Mode.BOT -> "Игра с ботом"
			Mode.HVH -> "Вдвоём на одном телефоне"
			Mode.CUSTOM -> "Своя партия"
		})))
		val vc = ui.col(0, 8)
		vc.addView(ui.chips(V.NAMES.mapIndexed { i, n -> n to (i == variant) }, 4) { variant = it; a.refresh() })
		vc.addView(ui.sub(when (variant) {
			V.CHESS960 -> "Фигуры на первой линии расставлены случайно (одна из 960 позиций), рокировка — как в Chess960: нажми королём на свою ладью."
			V.THREE_CHECK -> "Побеждает тот, кто первым объявит три шаха (или поставит мат). Stockfish здесь — это Fairy-Stockfish (до 2850 Эло)."
			V.KOTH -> "Побеждает тот, чей король первым дойдёт до центра (d4, e4, d5, e5), или мат. Stockfish здесь — это Fairy-Stockfish (до 2850 Эло)."
			else -> "Обычные шахматы."
		}))
		box.addView(section("Вариант", vc))
		when (mode) {
			Mode.BOT -> {
				box.addView(section("Соперник", playerPicker(bot, false) { bot = it; a.refresh() }))
				box.addView(section("Мой цвет", ui.chips(listOf("Белые" to (color == 0), "Чёрные" to (color == 1), "Случайно" to (color == 2)), 3) { color = it; a.refresh() }))
			}
			Mode.HVH -> {
				val c = ui.col(0, 8)
				c.addView(ui.chips(listOf("Лицом к лицу" to f2f, "Передавать телефон" to !f2f), 2) { f2f = it == 0; a.refresh() })
				c.addView(ui.sub(if (f2f) "Телефон лежит между игроками. Фигуры и часы верхнего игрока развёрнуты к нему."
				else "Доска поворачивается к тому, чей ход."))
				box.addView(section("Как сидим", c))
			}
			Mode.CUSTOM -> {
				box.addView(section("Белые", playerPicker(white, true) { white = it; a.refresh() }))
				box.addView(section("Чёрные", playerPicker(black, true) { black = it; a.refresh() }))
				box.addView(ui.sub("Награды и серия начисляются только в партиях «человек против бота» и «человек против человека»."))
			}
		}
		val clocks = GameConfig.CLOCKS.mapIndexed { i, (m, inc) -> GameConfig.clockLabel(m, inc) to (i == clock) }
		box.addView(section("Контроль времени", ui.chips(clocks, 4) { clock = it; a.refresh() }))
		box.addView(ui.button(if (widgetId >= 0) "📌 Поставить в виджет" else "Начать партию") { start() })
		return ui.scroll(box)
	}

	private fun section(title: String, v: View): View {
		val c = ui.card()
		c.addView(ui.text(title, 16f, bold = true))
		c.addView(ui.space(10))
		c.addView(v)
		return c
	}

	private fun playerPicker(cur: Player, allowHuman: Boolean, onChange: (Player) -> Unit): View {
		val box = ui.col(0, 10)
		val fams = ArrayList<Pair<String, Player.Kind>>()
		if (allowHuman) fams.add("Человек" to Player.Kind.HUMAN)
		fams.add("Stockfish" to Player.Kind.STOCKFISH)
		fams.add("Материал" to Player.Kind.MATERIAL)
		fams.add("Алгоритм" to Player.Kind.ALGO)
		fams.add("Случайный" to Player.Kind.RANDOM)
		box.addView(ui.chips(fams.map { it.first to (it.second == cur.kind) }, 3) { i ->
			onChange(when (fams[i].second) {
				Player.Kind.HUMAN -> Player.HUMAN
				Player.Kind.STOCKFISH -> Player(Player.Kind.STOCKFISH, 1350)
				Player.Kind.MATERIAL -> Player(Player.Kind.MATERIAL, 3)
				Player.Kind.ALGO -> Player(Player.Kind.ALGO, 3)
				else -> Player(Player.Kind.RANDOM, 0)
			})
		})
		when (cur.kind) {
			Player.Kind.STOCKFISH -> {
				val lv = Player.SF_LEVELS
				box.addView(ui.chips(lv.map { (if (it == 0) "MAX" else "$it") to (it == cur.level) }, 3) { onChange(Player(Player.Kind.STOCKFISH, lv[it])) })
				box.addView(ui.sub("Движки работают прямо на телефоне. От 1350 — Stockfish 17.1, MAX — без ограничений (≈3500). 500–1150, а также «Три шаха» и «Царь горы» — Fairy-Stockfish 14. Уровни — встроенная настройка силы UCI_Elo."))
			}
			Player.Kind.MATERIAL -> {
				box.addView(ui.chips((1..8).map { "Глубина $it" to (it == cur.level) }, 4) { onChange(Player(Player.Kind.MATERIAL, it + 1)) })
				box.addView(ui.sub("Минимакс с альфа-бетой, считает только материал, но досчитывает все размены до конца и продлевает шахи. Думает до 2,5 с. ≈${cur.elo} Эло (оценка на глаз)."))
			}
			Player.Kind.ALGO -> {
				box.addView(ui.chips((1..5).map { AlgoBot.LABELS[it - 1] to (it == cur.level) }, 3) { onChange(Player(Player.Kind.ALGO, it + 1)) })
				box.addView(ui.sub("Не перебор, а правила: видит мат в 1, не вешает фигуры (SEE), ищет вилки; с «Сильного» смотрит ответ соперника и не пропускает мат в 1; в эндшпиле двигает проходные. ≈${cur.elo} Эло (оценка на глаз)."))
			}
			Player.Kind.RANDOM -> box.addView(ui.sub("Ходит случайно. Для разминки и проверки матов."))
			Player.Kind.HUMAN -> box.addView(ui.sub("Ходит человек с этого телефона."))
			Player.Kind.REMOTE -> {}
		}
		return box
	}

	private fun start() {
		st.setStr("setupClock_$mode", "$clock")
		st.setStr("setupVariant", "$variant")
		when (mode) {
			Mode.BOT -> { st.setStr("setupBot", bot.encode()); st.setStr("setupColor", "$color") }
			Mode.HVH -> st.setFlag("setupF2F", f2f)
			Mode.CUSTOM -> { st.setStr("setupW", white.encode()); st.setStr("setupB", black.encode()) }
		}
		val o = JSONObject().put("type", "setup").put("mode", mode.name).put("bot", bot.encode()).put("color", color).put("clock", clock)
			.put("f2f", f2f).put("w", white.encode()).put("b", black.encode()).put("variant", variant)
		if (widgetId >= 0) {
			val (m, inc) = GameConfig.CLOCKS[clock]
			val title = when (mode) { Mode.BOT -> bot.shortName; Mode.HVH -> "Вдвоём"; Mode.CUSTOM -> "${white.shortName} — ${black.shortName}" }
			val parts = ArrayList<String>()
			if (mode == Mode.BOT) parts.add(arrayOf("белыми", "чёрными", "случайный цвет")[color])
			parts.add(GameConfig.clockLabel(m, inc))
			if (variant != V.STANDARD) parts.add(V.NAMES[variant])
			WidgetSetup.save(a, widgetId, o, title, parts.joinToString(" · "))
			return
		}
		a.replace(GameScreen(a, configFrom(o), null))
	}

	companion object {
		// Настройки → партия. Цвет «случайно» и расстановка Chess960 выбираются заново при каждом запуске.
		fun configFrom(o: JSONObject): GameConfig {
			val (m, inc) = GameConfig.CLOCKS[o.optInt("clock").coerceIn(0, GameConfig.CLOCKS.size - 1)]
			val variant = o.optInt("variant")
			val fen = if (variant == V.CHESS960) V.random960() else Position.START
			return when (o.optString("mode")) {
				"HVH" -> GameConfig(Player.HUMAN, Player.HUMAN, m, inc, o.optBoolean("f2f", true), fen, variant)
				"CUSTOM" -> GameConfig(Player.decode(o.optString("w")), Player.decode(o.optString("b")), m, inc, false, fen, variant)
				else -> {
					val bot = Player.decode(o.optString("bot"))
					val humanWhite = when (o.optInt("color")) { 0 -> true; 1 -> false; else -> Math.random() < 0.5 }
					if (humanWhite) GameConfig(Player.HUMAN, bot, m, inc, false, fen, variant) else GameConfig(bot, Player.HUMAN, m, inc, false, fen, variant)
				}
			}
		}
	}
}
