package ru.cersat.chessics.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.cersat.chessics.bots.AlgoBot
import ru.cersat.chessics.bots.Engine
import ru.cersat.chessics.bots.RandomBot
import ru.cersat.chessics.bots.Stockfish
import ru.cersat.chessics.bots.Tactics
import ru.cersat.chessics.chess.M
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import java.io.File
import kotlin.random.Random

// Задачи: из открытой базы lichess (CC0, темы размечены игроками) + сгенерированные в приложении.
// mate > 0 — цель «мат в mate», mate == 0 — цель «выиграть материал».
object Puzzles {
	class Puzzle(val id: String, val fen: String, val moves: List<String>, val mate: Int, val themes: List<String>, val generated: Boolean = false, val rating: Int = 0) {
		val heroMoves get() = (moves.size + 1) / 2
		val goal get() = if (mate > 0) "Мат в $mate" else "Выиграй материал"
		val reward get() = if ((mate > 0 && mate >= 3) || heroMoves >= 3) 2 else 1

		fun toJson(): JSONObject = JSONObject().put("id", id).put("fen", fen).put("moves", JSONArray(moves))
			.put("mate", mate).put("themes", JSONArray(themes))
	}

	class Theme(val id: String, val title: String)

	val THEMES = listOf(
		Theme("mate1", "Мат в 1"), Theme("mate2", "Мат в 2"), Theme("mate3", "Мат в 3"), Theme("mate4", "Мат в 4"),
		Theme("fork", "Вилка · fork"), Theme("skewer", "Шампур · skewer"), Theme("pin", "Связка · pin"),
		Theme("discovered", "Вскрытое нападение · discovered attack"), Theme("sacrifice", "Жертва · sacrifice"),
		Theme("hanging", "Висящая фигура · hanging piece"), Theme("promotion", "Превращение · promotion"),
		Theme("backRank", "Мат по последней · back rank mate"), Theme("smothered", "Спёртый мат · smothered mate"),
		Theme("trapped", "Пойманная фигура · trapped piece"), Theme("deflection", "Отвлечение · deflection"),
		Theme("attraction", "Завлечение · attraction"), Theme("doubleCheck", "Двойной шах · double check"),
		Theme("xray", "Рентген · x-ray attack"), Theme("interference", "Перекрытие · interference"),
		Theme("quietMove", "Тихий ход · quiet move"), Theme("clearance", "Освобождение линии · clearance"),
		Theme("advantage", "Выигрыш материала · advantage")
	)

	// темы, которые умеет распознавать генератор в приложении
	val GEN_THEMES = setOf("mate1", "mate2", "mate3", "mate4", "fork", "skewer", "pin", "discovered", "sacrifice", "hanging", "promotion", "advantage")

	fun themeTitle(id: String) = THEMES.firstOrNull { it.id == id }?.title ?: id

	private var all: List<Puzzle>? = null

	private fun parse(o: JSONObject, generated: Boolean): Puzzle {
		val mv = o.getJSONArray("moves")
		val th = o.optJSONArray("themes") ?: JSONArray()
		return Puzzle(o.getString("id"), o.getString("fen"), List(mv.length()) { mv.getString(it) }, o.getInt("mate"),
			List(th.length()) { th.getString(it) }, generated, o.optInt("rating"))
	}

	private fun genFile(ctx: Context) = File(ctx.filesDir, "generated_puzzles.json")

	fun all(ctx: Context): List<Puzzle> {
		all?.let { return it }
		val list = ArrayList<Puzzle>()
		val arr = JSONArray(ctx.assets.open("puzzles.json").bufferedReader().readText())
		for (i in 0 until arr.length()) list.add(parse(arr.getJSONObject(i), false))
		try {
			val g = JSONArray(genFile(ctx).readText())
			for (i in 0 until g.length()) list.add(parse(g.getJSONObject(i), true))
		} catch (_: Exception) {}
		all = list
		return list
	}

	fun byTheme(ctx: Context, theme: String) = all(ctx).filter { theme in it.themes }
	fun generated(ctx: Context) = all(ctx).filter { it.generated }

	fun addGenerated(ctx: Context, p: Puzzle) {
		val arr = try { JSONArray(genFile(ctx).readText()) } catch (_: Exception) { JSONArray() }
		arr.put(p.toJson())
		genFile(ctx).writeText(arr.toString())
		all = null
	}

	fun daily(ctx: Context): Puzzle? {
		val pool = all(ctx).filter { !it.generated && (it.mate >= 2 || it.mate == 0) }
		if (pool.isEmpty()) return null
		val day = Store.get(ctx).today
		val idx = ((day * 7919L) % pool.size).toInt().let { if (it < 0) it + pool.size else it }
		return pool[idx]
	}

	fun byId(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

	// ---------- распознавание тем ----------

	fun detectThemes(start: Position, line: List<String>, mate: Int): List<String> {
		val t = LinkedHashSet<String>()
		if (mate > 0) t.add("mate$mate")
		val hero = start.side
		val b = start.copy()
		val m0 = b.parseUci(line[0])
		if (m0 == 0) return t.toList()
		if (line.filterIndexed { i, _ -> i % 2 == 0 }.any { it.length == 5 }) t.add("promotion")
		val moved = b.sq[M.from(m0)]
		val cap = b.capturedBy(m0)
		val enemy = hero xor 1
		// до хода: какие клетки били дальнобойные фигуры (для вскрытого нападения)
		val before = HashSet<Int>()
		for (s in 0 until 64) {
			val q = b.sq[s]
			if (q != 0 && P.color(q) == hero && s != M.from(m0) && P.type(q) in P.BISHOP..P.QUEEN) before.addAll(Tactics.attackSquares(b, s).toList())
		}
		val hangingBefore = cap != 0 && Tactics.attackMap(b, enemy)[M.to(m0)].isEmpty()
		b.make(m0)
		val to = M.to(m0)
		val isMate = Tactics.isMate(b)
		if (P.type(moved) != P.PAWN && Tactics.squareLoss(b, to, hero) - (if (cap != 0) P.VALUE[P.type(cap)] else 0) >= 200 && !isMate) t.add("sacrifice")
		if (cap != 0 && P.type(cap) != P.PAWN && hangingBefore && line.size <= 3 && mate == 0) t.add("hanging")
		if (mate == 0) {
			val targets = Tactics.attackSquares(b, to).count {
				val q = b.sq[it]
				q != 0 && P.color(q) == enemy && P.type(q) != P.PAWN &&
					(P.type(q) == P.KING || P.VALUE[P.type(q)] > P.VALUE[P.type(moved)] || Tactics.squareLoss(b, it, enemy) > 0)
			}
			if (targets >= 2 && Tactics.squareLoss(b, to, hero) == 0) t.add("fork")
			val tp = P.type(moved)
			if (tp in P.BISHOP..P.QUEEN) {
				for (d in Tactics.dirs(tp)) {
					var f = P.file(to) + d[0]; var r = P.rank(to) + d[1]
					var front = -1
					while (f in 0..7 && r in 0..7) {
						val q = b.sq[P.sq(f, r)]
						if (q != 0) {
							if (P.color(q) != enemy) break
							if (front < 0) front = P.sq(f, r)
							else {
								val fq = b.sq[front]
								val fv = if (P.type(fq) == P.KING) 20000 else P.VALUE[P.type(fq)]
								val bv = if (P.type(q) == P.KING) 20000 else P.VALUE[P.type(q)]
								if (fv > bv && bv >= 300) t.add("skewer")
								if (bv > fv && P.type(fq) != P.PAWN) t.add("pin")
								break
							}
						}
						f += d[0]; r += d[1]
					}
				}
			}
		}
		for (s in 0 until 64) {
			val q = b.sq[s]
			if (q == 0 || P.color(q) != hero || s == to || P.type(q) !in P.BISHOP..P.QUEEN) continue
			for (a in Tactics.attackSquares(b, s)) {
				val v = b.sq[a]
				if (v != 0 && P.color(v) == enemy && P.type(v) in P.ROOK..P.KING && a !in before) t.add("discovered")
			}
		}
		if (t.isEmpty() || t.all { it == "promotion" }) if (mate == 0) t.add("advantage")
		return t.toList()
	}

	// ---------- автогенератор ----------

	class GenCancel { @Volatile var cancelled = false }

	private fun mat(p: Position, c: Int) = Tactics.material(p, c) - Tactics.material(p, c xor 1)

	private fun analyse(ctx: Context, p: Position, go: String, multipv: Int = 1): List<Engine.Line>? =
		Stockfish.analysis.analyse(ctx, p.fen(), "", p.side == P.WHITE, go, multipv)

	// лучший ход, если он единственный (разрыв со вторым ≥ gap или единственный кратчайший мат)
	private fun unique(ctx: Context, p: Position, gap: Int, mateN: Int): Engine.Line? {
		val l = analyse(ctx, p, "depth 12", 2) ?: return null
		if (l.isEmpty()) return null
		val sign = if (p.side == P.WHITE) 1 else -1
		val a = l[0]
		if (mateN > 0) {
			if (a.mate * sign != mateN) return null
			if (l.size > 1 && l[1].mate * sign in 1..mateN) return null
			return a
		}
		if (l.size > 1 && (a.score - l[1].score) * sign < gap) return null
		return a
	}

	private fun mateLine(ctx: Context, start: Position, n: Int): List<String>? {
		val p = start.copy()
		val line = ArrayList<String>()
		for (step in n downTo 1) {
			val mv = if (step == 1) analyse(ctx, p, "depth 10")?.firstOrNull()?.move else unique(ctx, p, 0, step)?.move
			val m = if (mv == null) 0 else p.parseUci(mv)
			if (m == 0) return null
			line.add(mv!!); p.make(m)
			if (Tactics.isMate(p)) return if (step == 1) line else null
			val r = analyse(ctx, p, "depth 12")?.firstOrNull()?.move ?: return null
			val rm = p.parseUci(r)
			if (rm == 0) return null
			line.add(r); p.make(rm)
		}
		return null
	}

	private fun winLine(ctx: Context, start: Position): List<String>? {
		val hero = start.side
		val m0 = mat(start, hero)
		val p = start.copy()
		val line = ArrayList<String>()
		for (i in 0 until 3) {
			val a = unique(ctx, p, if (i == 0) 250 else 150, 0) ?: break
			val sign = if (hero == P.WHITE) 1 else -1
			if (a.mate != 0 || a.score * sign < 200) return null
			val m = p.parseUci(a.move)
			if (m == 0) return null
			line.add(a.move); p.make(m)
			if (mat(p, hero) - m0 >= 200 && i > 0) return line
			val r = analyse(ctx, p, "depth 12")?.firstOrNull()?.move ?: return if (line.isNotEmpty()) line else null
			val rm = p.parseUci(r)
			if (rm == 0) return line
			p.make(rm)
			if (mat(p, hero) - m0 >= 200) return line
			line.add(r)
		}
		return null
	}

	// Ищет задачу на тему theme (null — любая). Работает в потоке анализа. progress(i) — сколько позиций проверено.
	fun generate(ctx: Context, theme: String?, cancel: GenCancel, progress: (Int) -> Unit): Puzzle? {
		val deadline = System.currentTimeMillis() + 120_000
		var checked = 0
		val algo = AlgoBot(2)
		while (System.currentTimeMillis() < deadline && !cancel.cancelled) {
			val p = Position.start()
			repeat(Random.nextInt(2, 7)) { p.make(RandomBot.pick(p, 0) { false }) }
			val plies = Random.nextInt(14, 70)
			var ply = 0
			while (ply < plies && p.hasLegalMove()) {
				val m = if (Random.nextDouble() < 0.12) RandomBot.pick(p, ply) { false } else algo.pick(p, ply) { false }
				p.make(m); ply++
				if (ply < 12 || Random.nextDouble() > 0.5) continue
				if (cancel.cancelled) return null
				val l = analyse(ctx, p, "depth 9") ?: return null
				checked++
				progress(checked)
				val a = l.firstOrNull() ?: continue
				val sign = if (p.side == P.WHITE) 1 else -1
				val wantMate = theme?.startsWith("mate") == true || theme == "backRank" || theme == "smothered"
				val fen = Position().also { it.setFen(p.fen()) }
				if (a.mate * sign in 1..4) {
					val n = a.mate * sign
					if (theme != null && theme.startsWith("mate") && theme != "mate$n") continue
					val line = mateLine(ctx, fen, n) ?: continue
					val th = detectThemes(fen, line, n)
					if (theme != null && theme !in th) continue
					return Puzzle("gen_${System.currentTimeMillis()}", fen.fen(), line, n, th, true)
				} else if (!wantMate && a.mate == 0 && a.score * sign >= 250) {
					val line = winLine(ctx, fen) ?: continue
					val th = detectThemes(fen, line, 0)
					if (theme != null && theme !in th) continue
					return Puzzle("gen_${System.currentTimeMillis()}", fen.fen(), line, 0, th, true)
				}
			}
		}
		return null
	}
}
