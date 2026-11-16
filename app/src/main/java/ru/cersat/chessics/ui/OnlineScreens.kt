package ru.cersat.chessics.ui

import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import ru.cersat.chessics.bots.Analyzer
import ru.cersat.chessics.chess.GameRecord
import ru.cersat.chessics.chess.M
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.chess.V
import ru.cersat.chessics.data.Lichess
import ru.cersat.chessics.data.Shop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LichessScreen(a: MainActivity, private val auto: JSONObject? = null) : Screen(a) {
	private var autoDone = false
	private val st = a.store
	private var events: Lichess.Stream? = null
	private var seek: Lichess.Stream? = null
	private val challenges = LinkedHashMap<String, JSONObject>()
	private var account: JSONObject? = null
	private var playing: List<JSONObject> = emptyList()
	private var loadError: String? = null
	private var loaded = false

	private val clocks = CLOCKS
	private val challengeClocks = CHALLENGE_CLOCKS

	override fun build(): View {
		val box = ui.col(16, 14)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title("Онлайн · Lichess")))
		if (Lichess.token(a).isEmpty()) { buildLogin(box); return ui.scroll(box) }
		if (!loaded) load()
		val acc = account
		val head = ui.card()
		when {
			acc != null -> {
				head.addView(ui.text(acc.optString("username"), 20f, bold = true))
				val perfs = acc.optJSONObject("perfs")
				val line = listOf("rapid" to "Рапид", "classical" to "Классика", "blitz" to "Блиц", "bullet" to "Пуля").mapNotNull { (k, n) ->
					perfs?.optJSONObject(k)?.optInt("rating")?.takeIf { it > 0 }?.let { "$n $it" }
				}.joinToString(" · ")
				head.addView(ui.sub(line.ifEmpty { "Рейтинга пока нет" }))
			}
			loadError != null -> head.addView(ui.text("Не удалось войти: $loadError", 14f, 0xFFE5383B.toInt()))
			else -> head.addView(ui.sub("Подключаюсь к lichess.org…"))
		}
		head.addView(ui.space(8))
		head.addView(ui.button("Выйти из аккаунта", false) { Lichess.setToken(a, ""); stopEvents(); a.refresh() })
		box.addView(head)

		if (challenges.isNotEmpty()) {
			box.addView(ui.text("Вызовы", 18f, bold = true))
			for ((id, ch) in challenges) {
				val c = ui.card()
				val who = ch.optJSONObject("challenger")
				c.addView(ui.text("${who?.optString("name")} (${who?.optInt("rating")})", 16f, bold = true))
				c.addView(ui.sub("${ch.optJSONObject("timeControl")?.optString("show") ?: ""} · ${if (ch.optBoolean("rated")) "рейтинговая" else "товарищеская"} · ${ch.optJSONObject("variant")?.optString("name") ?: ""}"))
				c.addView(ui.space(8))
				val r = ui.row(8)
				r.addView(ui.button("Принять") { act("POST", "/api/challenge/$id/accept") { challenges.remove(id) } }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
				r.addView(ui.button("Отклонить", false) { act("POST", "/api/challenge/$id/decline") { challenges.remove(id) } }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
				c.addView(r)
				box.addView(c)
			}
		}

		if (playing.isNotEmpty()) {
			box.addView(ui.text("Текущие партии", 18f, bold = true))
			for (g in playing) {
				val opp = g.optJSONObject("opponent")
				val r = ui.row(10)
				r.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
				r.addView(ui.col(0, 2, ui.text("против ${opp?.optString("username")} (${opp?.optInt("rating")})", 15f, bold = true),
					ui.sub(if (g.optBoolean("isMyTurn")) "Твой ход" else "Ход соперника")), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
				ui.clickable(r, ui.t.card, 16f) { val id = g.optString("gameId"); opened.add(id); a.push(OnlineGameScreen(a, id)) }
				box.addView(r)
			}
		}

		val lv = st.str("liVariant", "0").toInt().coerceIn(0, V.NAMES.size - 1)
		val vKey = V.LICHESS[lv]
		val vCard = ui.card()
		vCard.addView(ui.text("Вариант", 17f, bold = true))
		vCard.addView(ui.space(8))
		vCard.addView(ui.chips(V.NAMES.mapIndexed { i, n -> n to (i == lv) }, 4) { st.setStr("liVariant", "$it"); a.refresh() })
		vCard.addView(ui.space(4))
		vCard.addView(ui.sub("Для поиска, вызова и Stockfish на lichess."))
		box.addView(vCard)

		val tc = st.str("liClock", "0").toInt().coerceIn(0, clocks.size - 1)
		val rated = st.flag("liRated", false)
		val seekCard = ui.card()
		seekCard.addView(ui.text("Случайный соперник", 17f, bold = true))
		seekCard.addView(ui.sub("Lichess пускает внешние приложения в поиск соперника только на рапиде и классике. Блиц — через вызов друга или против Stockfish ниже; пулю внешним приложениям lichess не даёт совсем."))
		seekCard.addView(ui.space(10))
		seekCard.addView(ui.chips(clocks.mapIndexed { i, (m, inc) -> "$m+$inc" to (i == tc) }, 5) { st.setStr("liClock", "$it"); a.refresh() })
		seekCard.addView(ui.space(8))
		seekCard.addView(ui.chips(listOf("Товарищеская" to !rated, "Рейтинговая" to rated), 2) { st.setFlag("liRated", it == 1); a.refresh() })
		seekCard.addView(ui.space(10))
		seekCard.addView(ui.button("Найти соперника") { startSeek(clocks[tc], rated, vKey) })
		seekCard.addView(ui.space(8))
		seekCard.addView(ui.button("Блиц и пуля со случайным — на lichess.org", false) { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Lichess.HOST))) })
		box.addView(seekCard)

		val ftc = st.str("liFriendClock", "1").toInt().coerceIn(0, challengeClocks.size - 1)

		val friend = ui.card()
		friend.addView(ui.text("Вызвать друга", 17f, bold = true))
		friend.addView(ui.sub("Рейтинговость — как выбрано выше. Здесь можно и блиц."))
		friend.addView(ui.space(8))
		friend.addView(ui.chips(challengeClocks.mapIndexed { i, (m, inc) -> "$m+$inc" to (i == ftc) }, 4) { st.setStr("liFriendClock", "$it"); a.refresh() })
		friend.addView(ui.space(8))
		val name = EditText(a).apply {
			hint = "ник на lichess"; setTextColor(ui.t.text); setHintTextColor(ui.t.sub); setSingleLine()
			background = ui.round(ui.t.card2, 12f); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
		}
		friend.addView(name, ui.lp())
		friend.addView(ui.space(8))
		friend.addView(ui.button("Вызвать") {
			val u = name.text.toString().trim()
			if (u.isEmpty()) return@button
			val (m, inc) = challengeClocks[ftc]
			challenge("/api/challenge/$u", mapOf("rated" to "$rated", "clock.limit" to "${m * 60}", "clock.increment" to "$inc", "color" to "random", "variant" to vKey), "Ждём, пока $u примет вызов…")
		})
		box.addView(friend)

		val ai = ui.card()
		val lvl = st.str("liAi", "3").toInt()
		val aiTc = st.str("liAiTc", "1").toInt().coerceIn(0, challengeClocks.size - 1)
		ai.addView(ui.text("Stockfish на сервере lichess", 17f, bold = true))
		ai.addView(ui.space(8))
		ai.addView(ui.chips((1..8).map { "Ур. $it" to (it == lvl) }, 4) { st.setStr("liAi", "${it + 1}"); a.refresh() })
		ai.addView(ui.space(8))
		ai.addView(ui.chips(challengeClocks.mapIndexed { i, (m, inc) -> "$m+$inc" to (i == aiTc) }, 4) { st.setStr("liAiTc", "$it"); a.refresh() })
		ai.addView(ui.space(10))
		ai.addView(ui.button("Играть") {
			val (m, inc) = challengeClocks[aiTc]
			startAi(lvl, m, inc, vKey)
		})
		box.addView(ai)
		box.addView(ui.sub("Онлайн-победы тоже продлевают серию и дают алмазы.", 12f))
		startEvents()
		if (auto != null && !autoDone) BotRunner.main.post { runAuto() }
		return ui.scroll(box)
	}

	private fun buildLogin(box: LinearLayout) {
		val c = ui.card()
		c.addView(ui.text("Вход через токен", 17f, bold = true))
		c.addView(ui.sub("1. Нажми кнопку — откроется lichess.org со страницей создания токена (нужные права уже отмечены).\n2. Нажми там «Создать» и скопируй токен.\n3. Вставь его сюда."))
		c.addView(ui.space(10))
		c.addView(ui.button("Открыть lichess.org") { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Lichess.TOKEN_URL))) })
		c.addView(ui.space(10))
		val et = EditText(a).apply {
			hint = "lip_…"; setTextColor(ui.t.text); setHintTextColor(ui.t.sub); setSingleLine()
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
			background = ui.round(ui.t.card2, 12f); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
		}
		c.addView(et, ui.lp())
		c.addView(ui.space(8))
		c.addView(ui.button("Войти") {
			val t = et.text.toString().trim()
			if (t.isEmpty()) return@button
			Lichess.setToken(a, t); loaded = false; a.refresh()
		})
		box.addView(c)
	}

	private fun load() {
		loaded = true
		Lichess.exec.execute {
			try {
				val acc = Lichess.account(a)
				val pl = Lichess.call(a, "GET", "/api/account/playing").optJSONArray("nowPlaying")
				val list = if (pl == null) emptyList() else List(pl.length()) { pl.getJSONObject(it) }
				BotRunner.main.post { account = acc; playing = list; loadError = null; a.refresh() }
			} catch (e: Exception) {
				BotRunner.main.post { loadError = if (e is Lichess.Error && e.code == 401) "токен неверный или отозван" else e.message; a.refresh() }
			}
		}
	}

	private fun act(method: String, path: String, after: () -> Unit) {
		Lichess.exec.execute {
			try { Lichess.call(a, method, path); BotRunner.main.post { after(); a.refresh() } }
			catch (e: Exception) { BotRunner.main.post { a.toast("Lichess: ${e.message}") } }
		}
	}

	private fun startEvents() {
		if (events != null) return
		val h = Lichess.Stream()
		events = h
		Lichess.exec.execute {
			val err = Lichess.stream(a, "GET", "/api/stream/event", null, h) { ev ->
				BotRunner.main.post {
					if (events !== h) return@post
					when (ev.optString("type")) {
						"gameStart" -> {
							seek?.close(); seek = null
							seekDialog?.dismiss(); seekDialog = null
							val g = ev.optJSONObject("game")
							val id = g?.optString("gameId")?.ifEmpty { null } ?: g?.optString("id") ?: return@post
							if (opened.add(id)) a.push(OnlineGameScreen(a, id))
						}
						"challenge" -> {
							val ch = ev.optJSONObject("challenge") ?: return@post
							if (ch.optJSONObject("challenger")?.optString("id") != Lichess.myId(a)) { challenges[ch.optString("id")] = ch; a.refresh() }
						}
						"challengeCanceled", "challengeDeclined" -> {
							val id = ev.optJSONObject("challenge")?.optString("id")
							if (id != null && challenges.remove(id) != null) a.refresh()
							if (ev.optString("type") == "challengeDeclined" && seekDialog != null) { seekDialog?.dismiss(); seekDialog = null; a.toast("Вызов отклонён") }
						}
					}
				}
			}
			if (err != null) BotRunner.main.post { if (events === h) { events = null } }
		}
	}

	private fun stopEvents() { events?.close(); events = null }

	private var seekDialog: android.app.AlertDialog? = null

	private fun startSeek(tc: Pair<Int, Int>, rated: Boolean, variant: String) {
		val h = Lichess.Stream()
		seek = h
		seekDialog = a.dialog("Ищу соперника", ui.sub("${tc.first}+${tc.second} · ${if (rated) "рейтинговая" else "товарищеская"}. Как только найдётся — партия откроется сама."),
			"Отмена" to { h.close(); seek = null; seekDialog = null }, cancelable = false)
		Lichess.exec.execute {
			val err = Lichess.stream(a, "POST", "/api/board/seek", mapOf("rated" to "$rated", "time" to "${tc.first}", "increment" to "${tc.second}", "variant" to variant, "color" to "random"), h) {}
			if (err != null) BotRunner.main.post { seekDialog?.dismiss(); seekDialog = null; a.toast("Не удалось начать поиск: ${err.message}") }
		}
	}

	private fun challenge(path: String, f: Map<String, String>, waitText: String) {
		Lichess.exec.execute {
			try {
				val o = Lichess.call(a, "POST", path, f)
				val ch = o.optJSONObject("challenge") ?: o
				val id = ch.optString("id")
				BotRunner.main.post {
					seekDialog = a.dialog("Вызов отправлен", ui.sub(waitText), "Отменить вызов" to {
						seekDialog = null
						Lichess.exec.execute { try { Lichess.call(a, "POST", "/api/challenge/$id/cancel") } catch (_: Exception) {} }
					}, cancelable = false)
				}
			} catch (e: Exception) { BotRunner.main.post { a.toast("Lichess: ${e.message}") } }
		}
	}

	// запуск из виджета: сразу поиск соперника или игра со Stockfish lichess
	private fun runAuto() {
		val o = auto ?: return
		if (autoDone) return
		autoDone = true
		if (Lichess.token(a).isEmpty()) { a.toast("Сначала войди в lichess — виджет запустит игру со следующего раза"); return }
		val vKey = V.LICHESS[o.optInt("variant").coerceIn(0, V.LICHESS.size - 1)]
		if (o.optString("type") == "liSeek") startSeek(clocks[o.optInt("clock").coerceIn(0, clocks.size - 1)], o.optBoolean("rated"), vKey)
		else {
			val (m, inc) = challengeClocks[o.optInt("clock").coerceIn(0, challengeClocks.size - 1)]
			startAi(o.optInt("level", 3), m, inc, vKey)
		}
	}

	private fun startAi(lvl: Int, m: Int, inc: Int, vKey: String) {
		Lichess.exec.execute {
			try {
				val o = Lichess.call(a, "POST", "/api/challenge/ai", mapOf("level" to "$lvl", "clock.limit" to "${m * 60}", "clock.increment" to "$inc", "color" to "random", "variant" to vKey))
				val id = o.optString("id")
				BotRunner.main.post { if (opened.add(id)) a.push(OnlineGameScreen(a, id)) }
			} catch (e: Exception) { BotRunner.main.post { a.toast("Lichess: ${e.message}") } }
		}
	}

	override fun dispose() { stopEvents(); seek?.close() }

	companion object {
		// чтобы одна и та же партия не открылась дважды (ответ на вызов ИИ + событие gameStart)
		val opened = HashSet<String>()
		val CLOCKS = listOf(10 to 0, 10 to 5, 15 to 10, 30 to 0, 30 to 20)
		// Board API: блиц разрешён только в вызовах и против ИИ, пуля — нигде
		val CHALLENGE_CLOCKS = listOf(3 to 0, 3 to 2, 5 to 0, 5 to 3, 10 to 0, 10 to 5, 15 to 10, 30 to 0)
	}
}

class OnlineGameScreen(a: MainActivity, private val gameId: String) : Screen(a) {
	private val st = a.store
	private var startFen = Position.START
	private var variant = V.STANDARD
	private var game = GameRecord(startFen)
	private var my = P.WHITE
	private var names = arrayOf("Белые", "Чёрные")
	private var ratings = arrayOf("", "")
	private val times = LongArray(2)
	private var stateAt = 0L
	private var status = "created"
	private var winner = ""
	private var drawBy = -1
	private var goneSec = -1
	private var stream: Lichess.Stream? = null
	private var finished = false
	private var loaded = false
	private var serverMoves = ""

	private val board = BoardView(a)
	private lateinit var topBar: LinearLayout
	private lateinit var bottomBar: LinearLayout
	private lateinit var info: TextView
	private lateinit var controls: LinearLayout
	private val clockViews = arrayOfNulls<TextView>(2)

	private val tick = object : Runnable {
		override fun run() {
			for (c in 0..1) clockViews[c]?.text = fmt(timeOf(c))
			BotRunner.main.postDelayed(this, 200)
		}
	}

	private fun live() = status == "started" || status == "created"

	private fun timeOf(c: Int): Long {
		if (live() && game.moves.size >= 2 && game.pos.side == c) return maxOf(0L, times[c] - (System.currentTimeMillis() - stateAt))
		return times[c]
	}

	private fun fmt(ms: Long): String {
		val s = (ms + 999) / 1000
		return if (ms < 10_000) String.format(Locale.US, "%d.%d", ms / 1000, (ms % 1000) / 100) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
	}

	override fun build(): View {
		val root = ui.col(10, 8)
		topBar = ui.row(8); bottomBar = ui.row(8)
		info = ui.text("Подключаюсь к партии…", 14f, ui.t.sub, gravity = Gravity.CENTER)
		controls = ui.row(6)
		root.addView(ui.row(8, ui.button("‹", false) { a.pop() }, ui.text("Lichess · $gameId", 14f, ui.t.sub)))
		root.addView(topBar, ui.lp())
		board.showCoords = st.flag("coords", true)
		board.showLegal = st.flag("legal", true)
		board.canMove = { c -> c == my && live() && loaded && game.pos.side == my }
		board.onMove = { m -> onMove(m) }
		board.premoveColor = { if (st.flag("premove", true) && live() && loaded && game.pos.side != my) my else -1 }
		root.addView(board, ui.lp())
		root.addView(bottomBar, ui.lp())
		root.addView(info, ui.lp())
		root.addView(controls, ui.lp())
		redraw(false)
		if (stream == null) connect()
		BotRunner.main.removeCallbacks(tick)
		BotRunner.main.post(tick)
		return ui.scroll(root)
	}

	private fun connect() {
		val h = Lichess.Stream()
		stream = h
		Lichess.exec.execute {
			val err = Lichess.stream(a, "GET", "/api/board/game/stream/$gameId", null, h) { ev -> BotRunner.main.post { if (stream === h) onEvent(ev) } }
			BotRunner.main.post {
				if (stream !== h) return@post
				stream = null
				if (err != null) info.text = "Связь с lichess потеряна: ${err.message}. Вернись в «Онлайн», чтобы переподключиться."
			}
		}
	}

	private fun onEvent(ev: JSONObject) {
		when (ev.optString("type")) {
			"gameFull" -> {
				val fen = ev.optString("initialFen", "startpos")
				startFen = if (fen == "startpos" || fen.isEmpty()) Position.START else fen
				variant = V.fromLichess(ev.optJSONObject("variant")?.optString("key") ?: "standard")
				game = GameRecord(startFen, variant)
				val w = ev.optJSONObject("white"); val b = ev.optJSONObject("black")
				names = arrayOf(pname(w), pname(b))
				ratings = arrayOf(w?.optInt("rating")?.takeIf { it > 0 }?.toString() ?: "", b?.optInt("rating")?.takeIf { it > 0 }?.toString() ?: "")
				val me = Lichess.myId(a)
				my = if (b?.optString("id") == me && me.isNotEmpty()) P.BLACK else P.WHITE
				board.whiteBottom = my == P.WHITE
				loaded = true
				ev.optJSONObject("state")?.let { onState(it) }
			}
			"gameState" -> onState(ev)
			"opponentGone" -> {
				goneSec = if (ev.optBoolean("gone")) ev.optInt("claimWinInSeconds", 0) else -1
				redraw(false)
			}
		}
	}

	private fun pname(o: JSONObject?): String {
		if (o == null) return "?"
		if (o.has("aiLevel")) return "Stockfish ур. ${o.optInt("aiLevel")}"
		return (o.optString("title").let { if (it.isNotEmpty() && it != "null") "$it " else "" }) + o.optString("name", o.optString("id", "?"))
	}

	private fun onState(s: JSONObject) {
		times[0] = s.optLong("wtime", times[0]); times[1] = s.optLong("btime", times[1])
		stateAt = System.currentTimeMillis()
		status = s.optString("status", status)
		winner = s.optString("winner", "")
		drawBy = when { s.optBoolean("wdraw") -> P.WHITE; s.optBoolean("bdraw") -> P.BLACK; else -> -1 }
		val moves = s.optString("moves", "").trim()
		val animate = moves.length > serverMoves.length
		val before = game.moves.size
		serverMoves = moves
		syncMoves(moves)
		redraw(animate)
		if (game.moves.size > before && game.moves.isNotEmpty()) {
			val n = game.moves.size
			val last = game.moves[n - 1]
			val prev = game.positionAt(n - 1)
			// свой ход уже озвучен при отправке
			if (prev.side != my) ru.cersat.chessics.data.Sounds.play(a, ru.cersat.chessics.data.Sounds.forMove(prev.capturedBy(last) != 0, M.isCastle(last), game.pos.inCheck()))
		}
		if (!live() && !finished) { board.clearPremove(); ru.cersat.chessics.data.Sounds.play(a, ru.cersat.chessics.data.Sounds.Kind.END); finish() }
		else if (game.pos.side == my && board.premoveFrom >= 0) {
			val f = board.premoveFrom; val t = board.premoveTo
			board.clearPremove()
			game.pos.legalMoves().firstOrNull { M.from(it) == f && M.to(it) == t && (M.promo(it) == 0 || M.promo(it) == P.QUEEN) }?.let { send(it) }
		}
	}

	private fun syncMoves(moves: String) {
		val list = if (moves.isEmpty()) emptyList() else moves.split(' ')
		val mine = game.moves.map { M.uci(it) }
		if (list.size >= mine.size && list.subList(0, mine.size) == mine) {
			for (u in list.drop(mine.size)) { val m = game.pos.parseUci(u); if (m == 0) break; game.play(m) }
		} else {
			game = GameRecord.fromUci(startFen, moves, variant)
		}
	}

	private fun redraw(animate: Boolean) {
		board.setPosition(game.pos.copy(), game.lastMove, animate && st.flag("anim", true))
		bar(topBar, my xor 1)
		bar(bottomBar, my)
		info.text = when {
			!loaded -> "Подключаюсь к партии…"
			!live() -> resultText()
			goneSec == 0 -> "Соперник ушёл — можно забрать победу"
			goneSec > 0 -> "Соперник ушёл. Победу можно забрать через $goneSec с"
			game.pos.side == my -> if (game.pos.inCheck()) "Шах! Твой ход" else "Твой ход"
			else -> "Ход соперника"
		}
		controls.removeAllViews()
		fun add(label: String, primary: Boolean = false, f: () -> Unit) = controls.addView(ui.button(label, primary, true, f), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		if (!loaded) return
		if (live()) {
			if (drawBy == (my xor 1)) {
				add("Принять ½", true) { post("/api/board/game/$gameId/draw/yes") }
				add("Отклонить") { post("/api/board/game/$gameId/draw/no") }
			} else if (drawBy != my) add("½") { post("/api/board/game/$gameId/draw/yes"); a.toast("Ничья предложена") }
			if (goneSec == 0) add("Забрать победу", true) { post("/api/board/game/$gameId/claim-victory") }
			if (game.moves.size < 2) add("Отменить партию") { post("/api/board/game/$gameId/abort") }
			else add("⚑ Сдаться") {
				a.dialog("Сдаться?", ui.sub("Партия на lichess будет проиграна."), "Сдаться" to { post("/api/board/game/$gameId/resign") }, "Играть дальше" to {})
			}
		} else {
			add("Анализ", true) { openReview() }
			add("Онлайн") { a.pop() }
		}
	}

	private fun bar(v: LinearLayout, color: Int) {
		v.removeAllViews()
		v.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
		val toMove = live() && loaded && game.pos.side == color
		v.background = ui.round(if (toMove) ui.t.card2 else ui.t.card, 14f, if (toMove) 2 else 0, ui.t.accent)
		val icon = ImageView(a)
		icon.setImageBitmap(PieceArt.get(a, Shop.pieces(a).set, P.make(color, P.KING)))
		v.addView(icon, LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)))
		val nm = names[color] + if (ratings[color].isNotEmpty()) " · ${ratings[color]}" else ""
		v.addView(ui.text(nm + if (color == my) " (ты)" else "", 15f, bold = true), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		val c = ui.text(fmt(timeOf(color)), 20f, if (toMove) Ui.contrast(ui.t.accent) else ui.t.text, true, Gravity.CENTER)
		c.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
		c.background = ui.round(if (toMove) ui.t.accent else ui.t.bg, 10f)
		c.minWidth = ui.dp(78)
		clockViews[color] = c
		v.addView(c)
	}

	private fun resultText(): String {
		val reason = when (status) {
			"mate" -> "мат"; "resign" -> "сдача"; "stalemate" -> "пат"; "timeout" -> "соперник покинул партию"
			"outoftime" -> "время вышло"; "draw" -> "ничья"; "aborted" -> "партия отменена"; "noStart" -> "партия не началась"
			"cheat" -> "нарушение правил"; else -> status
		}
		return when (winner) {
			"white" -> "Победа белых · $reason"
			"black" -> "Победа чёрных · $reason"
			else -> if (status == "aborted" || status == "noStart") reason.replaceFirstChar { it.uppercase() } else "Ничья · $reason"
		}
	}

	private fun post(path: String) {
		Lichess.exec.execute {
			try { Lichess.call(a, "POST", path) } catch (e: Exception) { BotRunner.main.post { a.toast("Lichess: ${e.message}") } }
		}
	}

	private fun onMove(m0: Int) {
		if (game.pos.side != my || !live()) return
		if (m0 and BoardView.PROMO_ASK != 0) {
			val base = m0 and BoardView.PROMO_ASK.inv()
			fun promo(t: Int) = game.pos.legalMoves().firstOrNull { M.from(it) == M.from(base) && M.to(it) == M.to(base) && M.promo(it) == t } ?: 0
			if (st.flag("autoqueen", false)) { send(promo(P.QUEEN)); return }
			val r = ui.row(8)
			var dlg: android.app.AlertDialog? = null
			for (t in intArrayOf(P.QUEEN, P.ROOK, P.BISHOP, P.KNIGHT)) {
				val iv = ImageView(a)
				iv.setImageBitmap(PieceArt.get(a, Shop.pieces(a).set, P.make(my, t)))
				iv.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
				ui.clickable(iv, ui.t.card2, 12f) { dlg?.dismiss(); send(promo(t)) }
				r.addView(iv, LinearLayout.LayoutParams(0, ui.dp(70), 1f))
			}
			dlg = a.dialog("Превращение пешки", r, "Отмена" to { redraw(false) })
			return
		}
		send(m0)
	}

	private fun send(m: Int) {
		if (m == 0) return
		val u = M.uci(m)
		val captured = game.pos.capturedBy(m) != 0
		game.play(m) // сразу показываем, сервер подтвердит
		ru.cersat.chessics.data.Sounds.play(a, ru.cersat.chessics.data.Sounds.forMove(captured, M.isCastle(m), game.pos.inCheck()))
		if (game.moves.size >= 2) { times[my] = timeOf(my); stateAt = System.currentTimeMillis() }
		redraw(true)
		Lichess.exec.execute {
			try { Lichess.call(a, "POST", "/api/board/game/$gameId/move/$u") }
			catch (e: Exception) {
				BotRunner.main.post {
					a.toast("Ход не принят: ${e.message}")
					game = GameRecord.fromUci(startFen, serverMoves, variant)
					redraw(false)
				}
			}
		}
	}

	private fun finish() {
		finished = true
		val plies = game.moves.size
		if (status == "aborted" || status == "noStart") return
		val score = when (winner) { "white" -> if (my == P.WHITE) 1.0 else 0.0; "black" -> if (my == P.BLACK) 1.0 else 0.0; else -> 0.5 }
		val r = st.onlineFinished(score, plies)
		val o = JSONObject()
			.put("date", SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date()))
			.put("w", names[0]).put("b", names[1])
			.put("res", when (winner) { "white" -> "1-0"; "black" -> "0-1"; else -> "1/2-1/2" })
			.put("reason", resultText().substringAfter("· ", resultText()))
			.put("fen", startFen).put("uci", game.uciList()).put("tc", "lichess").put("variant", variant)
		st.saveGame(o)
		if (plies > 0) Analyzer.start(a, startFen, game.uciList(), variant)
		val body = ui.col(0, 6)
		r.lines.forEach { body.addView(ui.text(it, 14f)) }
		if (r.diamonds > 0) body.addView(ui.text("Всего: +${r.diamonds} 💎", 16f, ui.t.accent, true))
		val head = when { score >= 1.0 -> "Победа! 🎉"; score > 0 -> "Ничья"; else -> "Поражение" }
		a.dialog(head, ui.col(0, 6, ui.sub(resultText(), 15f), body), "Посмотреть ходы" to {}, "Анализ партии" to { openReview() })
	}

	private fun openReview() {
		val res = resultText()
		a.push(ReviewScreen(a, startFen, game.uciList(), names[0], names[1], res, variant))
	}

	override fun dispose() {
		LichessScreen.opened.remove(gameId)
		stream?.close(); stream = null
		BotRunner.main.removeCallbacks(tick)
	}
}
