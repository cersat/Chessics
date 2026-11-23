package ru.cersat.chessics.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.data.Achievements
import ru.cersat.chessics.data.Reminder
import ru.cersat.chessics.data.Shop

class ShopScreen(a: MainActivity) : Screen(a) {
	override val tab = 3
	private val st = a.store

	override fun build(): View {
		val box = ui.col(16, 14)
		val head = ui.row(10)
		head.addView(ui.title("Магазин"), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		head.addView(ui.text("💎 ${st.diamonds}", 18f, ui.t.accent, true))
		box.addView(head)
		box.addView(ui.sub("Алмазы дают за победы над ботами (чем сильнее бот — тем больше), задачи, дни серии и достижения."))

		// заморозки
		val fz = ui.card()
		fz.addView(ui.row(12, IconView(a, 1, 0xFF8FD3FF.toInt(), 0xFFFFFFFF.toInt()).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)) },
			ui.col(0, 2, ui.text("Заморозка серии", 17f, bold = true),
				ui.sub("Если пропустишь день, заморозка сгорит вместо серии. У тебя ${st.freezes} из ${st.maxFreezes}."))))
		fz.addView(ui.space(10))
		val full = st.freezes >= st.maxFreezes
		fz.addView(ui.button(if (full) "Запас полон" else "Купить за ${Shop.FREEZE_PRICE} 💎", !full, !full && st.diamonds >= Shop.FREEZE_PRICE) {
			if (st.buyFreeze()) { a.toast("Заморозка куплена ❄"); a.refresh() }
		})
		box.addView(fz)

		// подсказки
		val hc = ui.card()
		hc.addView(ui.row(12, ui.text("💡", 32f), ui.col(0, 2, ui.text("Подсказка", 17f, bold = true),
			ui.sub("Лучший ход в партии с ботом или в задаче. Награду не уменьшает. Каждая следующая на 1 💎 дороже. У тебя: ${st.hintsLeft}."))))
		hc.addView(ui.space(10))
		hc.addView(ui.button("Купить за ${st.hintPrice} 💎", true, st.diamonds >= st.hintPrice) {
			if (st.buyHint()) { a.toast("Подсказка куплена 💡"); a.refresh() }
		})
		box.addView(hc)

		// разделы сворачиваются; открыт только один
		val open = st.str("shopOpen", Shop.Cat.THEME.name)
		for (cat in Shop.Cat.values()) {
			val items = Shop.items(cat)
			val isOpen = open == cat.name
			val owned = items.count { st.owns(it.id) }
			val sel = Shop.byId(st.selected(cat))?.name ?: ""
			val head = ui.row(10)
			head.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
			head.addView(ui.col(0, 2, ui.text(cat.title, 17f, bold = true), ui.sub("Куплено $owned из ${items.size} · выбрано: $sel", 12f)), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			head.addView(ui.text(if (isOpen) "▴" else "▾", 22f, ui.t.sub))
			ui.clickable(head, if (isOpen) ui.t.card2 else ui.t.card, 18f) { st.setStr("shopOpen", if (isOpen) "" else cat.name); a.refresh() }
			box.addView(head)
			if (isOpen) for (item in items) box.addView(itemCard(item))
		}
		return ui.scroll(box)
	}

	private fun preview(item: Shop.Item): View {
		val size = ui.dp(72)
		val bmp = Bitmap.createBitmap(if (item is Shop.Widget) size * 2 else size, size, Bitmap.Config.ARGB_8888)
		val c = Canvas(bmp)
		when (item) {
			is Shop.Theme -> {
				val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
				p.color = item.bg; c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 14f, 14f, p)
				p.color = item.card; c.drawRoundRect(size * 0.12f, size * 0.15f, size * 0.88f, size * 0.55f, 10f, 10f, p)
				p.color = item.accent; c.drawRoundRect(size * 0.12f, size * 0.65f, size * 0.88f, size * 0.85f, 10f, 10f, p)
				p.color = item.text; c.drawRect(size * 0.2f, size * 0.25f, size * 0.7f, size * 0.31f, p)
				p.color = item.sub; c.drawRect(size * 0.2f, size * 0.38f, size * 0.55f, size * 0.43f, p)
			}
			is Shop.Board -> {
				val pos = Position(); pos.setFen("8/8/8/8/3qk3/3KQ3/8/8 w - - 0 1")
				c.save(); c.scale(2f, 2f, 0f, 0f); c.translate(-size * 0.375f, -size * 0.375f)
				PieceArt.drawStatic(a, c, pos, size.toFloat(), true, item, Shop.pieces(a).set)
				c.restore()
			}
			is Shop.Pieces -> {
				val s = size / 2f
				val ps = intArrayOf(P.make(P.WHITE, P.KING), P.make(P.BLACK, P.QUEEN), P.make(P.WHITE, P.KNIGHT), P.make(P.BLACK, P.PAWN))
				val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
				ps.forEachIndexed { i, pc ->
					val b = PieceArt.get(a, item.set, pc) ?: return@forEachIndexed
					c.drawBitmap(b, null, android.graphics.RectF((i % 2) * s, (i / 2) * s, (i % 2 + 1) * s, (i / 2 + 1) * s), p)
				}
			}
			is Shop.Sound -> {
				val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
				p.color = ui.t.card2; c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 18f, 18f, p)
				p.color = ui.t.accent; p.textSize = size * 0.5f
				val sym = if (item.style == 0) "🔇" else "♪"
				c.drawText(sym, (size - p.measureText(sym)) / 2, size * 0.68f, p)
			}
			is Shop.Widget -> {
				ru.cersat.chessics.data.Widgets.background(c, size * 2f, size.toFloat(), item, size * 0.2f)
				ru.cersat.chessics.data.Widgets.drawIcon(c, item.style, size * 0.45f, size / 2f, size * 0.26f, item.text, item.accent)
				val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
				p.color = item.text; p.isFakeBoldText = true; p.textSize = size * 0.42f
				c.drawText("${st.streak}", size * 0.85f, size * 0.66f, p)
			}
		}
		val iv = ImageView(a)
		iv.setImageBitmap(bmp)
		iv.layoutParams = LinearLayout.LayoutParams(bmp.width, bmp.height)
		return iv
	}

	private fun itemCard(item: Shop.Item): View {
		val r = ui.row(14)
		r.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
		r.background = ui.round(ui.t.card, 18f)
		r.addView(preview(item))
		val owned = st.owns(item.id)
		val sel = st.selected(item.cat) == item.id && owned
		val req = item.req
		val desc0 = when (item) {
			is Shop.Pieces -> item.credit
			is Shop.Widget -> "Для виджета серии и задачи дня"
			is Shop.Theme -> if (item.light) "Светлая тема приложения" else "Тёмная тема приложения"
			is Shop.Sound -> item.desc + if (item.style > 0) " · нажми, чтобы послушать" else ""
			else -> "Цвета клеток и подсветки"
		}
		val desc = if (req == null) desc0 else desc0 + "\n" + (if (owned) "🏆 Открыто: " else "🔒 ") + req.text + if (!owned && req.target > 1) " (${req.progress(st)}/${req.target})" else ""
		r.addView(ui.col(0, 2, ui.text(item.name, 16f, bold = true), ui.sub(desc, 12f)), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		val btn = when {
			sel -> ui.button("✓", false, false) {}
			owned -> ui.button("Выбрать", false) { choose(item) }
			req != null -> ui.button("🔒", false, false) {}
			else -> ui.button("${item.price} 💎", true, st.diamonds >= item.price) {
				if (st.buy(item)) { a.toast("Куплено: ${item.name}"); choose(item) }
			}
		}
		r.addView(btn)
		if (item is Shop.Sound && item.style > 0) r.setOnClickListener {
			val ctx = a
			ru.cersat.chessics.data.Sounds.play(ctx, ru.cersat.chessics.data.Sounds.Kind.MOVE, item.style)
			BotRunner.main.postDelayed({ ru.cersat.chessics.data.Sounds.play(ctx, ru.cersat.chessics.data.Sounds.Kind.CAPTURE, item.style) }, 350)
			BotRunner.main.postDelayed({ ru.cersat.chessics.data.Sounds.play(ctx, ru.cersat.chessics.data.Sounds.Kind.CHECK, item.style) }, 750)
		}
		return r
	}

	private fun choose(item: Shop.Item) {
		st.select(item.cat, item.id)
		if (item.cat == Shop.Cat.THEME) a.restyle() else a.refresh()
	}
}

class ProfileScreen(a: MainActivity) : Screen(a) {
	override val tab = 4
	private val st = a.store

	override fun build(): View {
		val box = ui.col(16, 14)
		val head = ui.row(10)
		head.addView(ui.title("Профиль"), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		head.addView(ui.button("⚙ Настройки", false) { a.push(SettingsScreen(a)) })
		box.addView(head)

		val c = ui.card()
		c.addView(ui.text("Рейтинг ${st.rating}", 26f, ui.t.accent, true))
		c.addView(ui.sub("Считается по Эло после каждой партии с ботом (рейтинг бота — его ≈Эло)"))
		c.addView(ui.space(12))
		val stats = listOf(
			"Партий" to st.stat("games"), "Побед" to st.stat("wins"), "Ничьих" to st.stat("draws"), "Поражений" to st.stat("losses"),
			"Задач" to st.stat("puzzles"), "Лучшая серия" to st.bestStreak, "Вдвоём" to st.stat("hvh"), "Подсказок" to st.stat("hints"),
			"Бриллиантовых ходов" to st.stat("brilliant"), "Своих вариантов" to VariantStore.list(st).size
		)
		c.addView(grid(stats))
		val best = st.stat("bestWinElo")
		if (best > 0) { c.addView(ui.space(8)); c.addView(ui.sub("Самый сильный побеждённый бот: ≈$best Эло")) }
		box.addView(c)

		box.addView(ui.button("📜 История партий (${st.loadGames().length()})", false) { a.push(HistoryScreen(a)) })

		val done = Achievements.ALL.count { st.achieved(it.id) }
		box.addView(ui.text("Достижения · $done из ${Achievements.ALL.size}", 19f, bold = true))
		for (ach in Achievements.ALL) {
			val got = st.achieved(ach.id)
			val card = ui.card(14, if (got) ui.t.card2 else ui.t.card)
			val r = ui.row(10)
			r.addView(ui.text(if (got) "🏆" else "🔒", 22f))
			r.addView(ui.col(0, 2, ui.text(ach.title, 15f, bold = true), ui.sub(ach.desc, 12f)), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			r.addView(ui.text("+${ach.reward} 💎", 13f, if (got) ui.t.accent else ui.t.sub, true))
			card.addView(r)
			if (!got && ach.target > 1) {
				card.addView(ui.space(8))
				val pr = st.progressOf(ach)
				val big = ach.target >= 1000
				card.addView(ui.progress(if (big) (if (pr >= ach.target) 1 else 0) else pr, if (big) 1 else ach.target))
				if (!big) card.addView(ui.sub("$pr / ${ach.target}", 11f))
			}
			box.addView(card)
		}

		val eggs = ru.cersat.chessics.data.Eggs
		box.addView(ui.text("Пасхалки · ${eggs.ALL.count { eggs.found(st, it.id) }} из ${eggs.ALL.size}", 19f, bold = true))
		box.addView(ui.sub("Находятся сами по ходу игры. Каждая — +${eggs.REWARD} 💎."))
		for (e in eggs.ALL) {
			val got = eggs.found(st, e.id)
			val card = ui.card(14, if (got) ui.t.card2 else ui.t.card)
			val r = ui.row(10)
			r.addView(ui.text(if (got) "🥚" else "❓", 22f))
			r.addView(if (got) ui.col(0, 2, ui.text(e.title, 15f, bold = true), ui.sub(e.text, 12f)) else ui.text("???", 15f, ui.t.sub, true), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			card.addView(r)
			box.addView(card)
		}
		return ui.scroll(box)
	}

	private fun grid(items: List<Pair<String, Int>>): View {
		val g = ui.col(0, 8)
		items.chunked(4).forEach { ch ->
			val r = ui.row(8)
			ch.forEach { (k, v) ->
				val cell = ui.col(0, 0, ui.text("$v", 20f, bold = true, gravity = Gravity.CENTER), ui.text(k, 11f, ui.t.sub, gravity = Gravity.CENTER))
				cell.setPadding(0, ui.dp(8), 0, ui.dp(8))
				cell.background = ui.round(ui.t.bg, 12f)
				r.addView(cell, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			}
			g.addView(r, ui.lp())
		}
		return g
	}
}

class HistoryScreen(a: MainActivity) : Screen(a) {
	override fun build(): View {
		val box = ui.col(16, 10)
		box.addView(ui.row(8, ui.button("‹", false) { a.pop() }, ui.title("История")))
		val arr = a.store.loadGames()
		if (arr.length() == 0) box.addView(ui.sub("Пока пусто. Сыгранные партии появятся здесь."))
		for (i in arr.length() - 1 downTo 0) {
			val o = arr.getJSONObject(i)
			val r = ui.row(10)
			r.setPadding(ui.dp(14), ui.dp(12), ui.dp(10), ui.dp(12))
			val custom = o.optJSONObject("custom")
			val plies = if (custom != null) (o.optJSONArray("vmoves")?.length() ?: 0) else o.optString("uci").split(' ').count { it.isNotBlank() }
			val vname = if (custom != null) " · ${custom.optString("name", "свой вариант")}" else o.optInt("variant").takeIf { it > 0 }?.let { " · ${ru.cersat.chessics.chess.V.NAMES[it]}" } ?: ""
			val info = ui.col(0, 2,
				ui.text("${o.optString("w")} — ${o.optString("b")}$vname", 15f, bold = true),
				ui.sub("${o.optString("res")} · ${o.optString("reason")} · ${(plies + 1) / 2} ход.", 12f),
				ui.sub(o.optString("date") + if (o.optString("tc").isNotEmpty()) " · ${o.optString("tc")}" else "", 11f))
			r.addView(info, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			r.addView(ui.button("🗑", false) { a.store.deleteGame(i); a.refresh() })
			ui.clickable(r, ui.t.card, 16f) {
				val four = custom?.optInt("players", 2) == 4
				val res = when (o.optString("res")) { "1-0" -> if (four) "Победа красных и жёлтых" else "Победа белых"; "0-1" -> if (four) "Победа синих и зелёных" else "Победа чёрных"; "1/2-1/2" -> "Ничья"; else -> "" }
				if (custom != null) {
					val arr = o.optJSONArray("vmoves") ?: org.json.JSONArray()
					val mv = List(arr.length()) { k -> arr.getJSONArray(k).let { intArrayOf(it.getInt(0), it.getInt(1), it.getInt(2)) } }
					a.push(VariantReviewScreen(a, ru.cersat.chessics.chess.Variant.from(custom), mv, "${o.optString("w")} — ${o.optString("b")}$vname",
						if (res.isEmpty()) "" else "$res · ${o.optString("reason")}"))
					return@clickable
				}
				a.push(ReviewScreen(a, o.optString("fen", Position.START), o.optString("uci"), o.optString("w"), o.optString("b"),
					if (res.isEmpty()) "" else "$res · ${o.optString("reason")}", o.optInt("variant")))
			}
			box.addView(r)
		}
		return ui.scroll(box)
	}
}

class SettingsScreen(a: MainActivity) : Screen(a) {
	private val st = a.store

	override fun build(): View {
		val box = ui.col(16, 10)
		box.addView(ui.row(8, ui.button("‹", false) { a.pop() }, ui.title("Настройки")))
		box.addView(toggle("Координаты на доске", "coords", true))
		box.addView(toggle("Подсветка возможных ходов", "legal", true))
		box.addView(toggle("Полоса преимущества в партии", "evalbar", true))
		box.addView(toggle("Анимация ходов", "anim", true))
		box.addView(toggle("Значок типа своего хода во время партии (★, ?!, ??…)", "liveJudge", true))
		box.addView(toggle("Сразу превращать пешку в ферзя", "autoqueen", false))
		box.addView(toggle("Показывать аббревиатуры фигур на английском (N, B, R, Q, K)", "sanEn", false))
		box.addView(toggle("Предходы (ход заранее, пока думает соперник)", "premove", true))
		box.addView(toggle("Вибрация при ходе", "vibro", true))
		box.addView(toggle("Напоминание о серии в 20:00", "remind", true) {
			Reminder.schedule(a)
			if (st.flag("remind", true) && Build.VERSION.SDK_INT >= 33 &&
				a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
				a.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
		})
		val about = ui.card()
		about.addView(ui.text("О приложении", 16f, bold = true))
		val info = try { a.packageManager.getPackageInfo(a.packageName, 0) } catch (_: Exception) { null }
		about.addView(ui.text("Версия ${info?.versionName ?: "неизвестна"}" + (info?.let { " (сборка ${if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()})" } ?: ""), 14f, ui.t.text, true))
		about.addView(ui.sub("Chessics. Движок Stockfish 17.1 (GPLv3, stockfishchess.org) работает прямо на телефоне. " +
			"Боты «Материал» и «Алгоритм» перенесены из chess-bots.js. Фигуры — из lichess: " +
			Shop.items(Shop.Cat.PIECES).joinToString("; ") { (it as Shop.Pieces).credit } + ". Задачи — из открытой базы lichess (CC0), генератор в приложении проверяет свои задачи Stockfish. Ходы оценивает Stockfish: !! бриллиантовый (жертва, после которой позиция не хуже), ! отличный (единственный сильный), ★ лучший, 👍 очень хороший, ✓ хороший, □ единственный, ?! неточность, ? ошибка, ?? зевок.", 12f))
		about.addView(ui.space(6))
		about.addView(ui.sub("Виджеты: долгое нажатие на рабочем столе → Виджеты → Chessics («Серия» и «Задача дня»).", 12f))
		box.addView(about)
		return ui.scroll(box)
	}

	private fun toggle(label: String, key: String, def: Boolean, after: () -> Unit = {}): View {
		val on = st.flag(key, def)
		val r = ui.row(10)
		r.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
		r.addView(ui.text(label, 15f), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		r.addView(ui.text(if (on) "Вкл" else "Выкл", 14f, if (on) Ui.contrast(ui.t.accent) else ui.t.sub, true, Gravity.CENTER).apply {
			setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
			background = ui.round(if (on) ui.t.accent else ui.t.card2, 14f)
		})
		ui.clickable(r, ui.t.card, 16f) { st.setFlag(key, !on); after(); a.refresh() }
		return r
	}
}
