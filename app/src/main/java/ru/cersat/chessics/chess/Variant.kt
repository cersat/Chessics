package ru.cersat.chessics.chess

import org.json.JSONArray
import org.json.JSONObject

// ===== Конструктор вариантов: модель =====

// Одно правило хода в координатах владельца: dy — «вперёд», dx — «вправо».
// Для чёрных доска повёрнута на 180°, как в нотации Бетца и у Fairy-Stockfish.
// slide — луч в направлении (dx, dy) до препятствия; иначе прыжок ровно на (dx, dy).
class MoveRule(val dx: Int, val dy: Int, val slide: Boolean, val mode: Int) {
	fun json(): JSONObject = JSONObject().put("dx", dx).put("dy", dy).put("slide", slide).put("mode", mode)

	companion object {
		const val BOTH = 0
		const val MOVE = 1 // только тихий ход
		const val CAPTURE = 2 // только взятие
		fun from(o: JSONObject) = MoveRule(o.optInt("dx"), o.optInt("dy"), o.optBoolean("slide"), o.optInt("mode"))
	}
}

class PieceDef(
	var name: String,
	var symbol: String,
	var art: Int, // 0 — буква в кружке, 1..6 — рисунок пешки/коня/слона/ладьи/ферзя/короля
	var value: Int,
	var royal: Boolean, // король: его мат или взятие решает партию
	var pawn: Boolean, // пешка: двойной ход с места, взятие на проходе, превращение
	var castler: Boolean, // с ним королевская фигура может рокироваться
	val moves: MutableList<MoveRule>,
) {
	fun json(): JSONObject = JSONObject().put("name", name).put("symbol", symbol).put("art", art).put("value", value)
		.put("royal", royal).put("pawn", pawn).put("castler", castler).put("moves", JSONArray().apply { moves.forEach { put(it.json()) } })

	fun copy() = PieceDef(name, symbol, art, value, royal, pawn, castler, moves.toMutableList())

	companion object {
		fun from(o: JSONObject): PieceDef {
			val arr = o.optJSONArray("moves") ?: JSONArray()
			return PieceDef(o.optString("name", "Фигура"), o.optString("symbol", "?"), o.optInt("art"), o.optInt("value", 300),
				o.optBoolean("royal"), o.optBoolean("pawn"), o.optBoolean("castler"),
				MutableList(arr.length()) { MoveRule.from(arr.getJSONObject(it)) })
		}

		private fun rays(vararg d: IntArray) = d.map { MoveRule(it[0], it[1], true, MoveRule.BOTH) }
		private fun leaps(vararg d: IntArray) = d.map { MoveRule(it[0], it[1], false, MoveRule.BOTH) }
		private val ORTHO = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
		private val DIAG = arrayOf(intArrayOf(1, 1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(-1, -1))
		private val KNIGHT = arrayOf(intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(-1, 2), intArrayOf(-2, 1), intArrayOf(1, -2), intArrayOf(2, -1), intArrayOf(-1, -2), intArrayOf(-2, -1))

		fun pawn() = PieceDef("Пешка", "п", P.PAWN, 100, false, true, false,
			mutableListOf(MoveRule(0, 1, false, MoveRule.MOVE), MoveRule(1, 1, false, MoveRule.CAPTURE), MoveRule(-1, 1, false, MoveRule.CAPTURE)))
		fun knight() = PieceDef("Конь", "К", P.KNIGHT, 320, false, false, false, leaps(*KNIGHT).toMutableList())
		fun bishop() = PieceDef("Слон", "С", P.BISHOP, 330, false, false, false, rays(*DIAG).toMutableList())
		fun rook() = PieceDef("Ладья", "Л", P.ROOK, 500, false, false, true, rays(*ORTHO).toMutableList())
		fun queen() = PieceDef("Ферзь", "Ф", P.QUEEN, 900, false, false, false, (rays(*ORTHO) + rays(*DIAG)).toMutableList())
		fun king() = PieceDef("Король", "Кр", P.KING, 0, true, false, false, (leaps(*ORTHO) + leaps(*DIAG)).toMutableList())
		fun standard() = mutableListOf(pawn(), knight(), bishop(), rook(), queen(), king())
	}
}

class Variant(
	var name: String,
	var w: Int,
	var h: Int,
	val pieces: MutableList<PieceDef>,
	var cells: IntArray, // стартовая расстановка: 0 — пусто, иначе (индекс фигуры + 1) | (цвет << 8)
	var locked: BooleanArray, // заблокированные клетки — на них нельзя встать
) {
	var win = WIN_MATE
	var nChecks = 3
	var stalemate = 0 // 0 — ничья, 1 — запатованный проигрывает, 2 — запатованный выигрывает
	var castling = true
	var enPassant = true
	var pawnDouble = true
	var promo = mutableListOf<String>() // в кого превращаются пешки (имена фигур); пусто — не превращаются

	fun idx(x: Int, y: Int) = y * w + x
	fun piece(code: Int) = pieces[(code and 0xFF) - 1]
	fun has(code: Int) = code != 0 && (code and 0xFF) in 1..pieces.size

	fun json(): JSONObject = JSONObject().put("name", name).put("w", w).put("h", h)
		.put("pieces", JSONArray().apply { pieces.forEach { put(it.json()) } })
		.put("cells", JSONArray().apply { cells.forEach { put(it) } })
		.put("locked", JSONArray().apply { locked.forEach { put(it) } })
		.put("win", win).put("nChecks", nChecks).put("stalemate", stalemate).put("castling", castling)
		.put("enPassant", enPassant).put("pawnDouble", pawnDouble).put("promo", JSONArray(promo))

	fun copy(): Variant = from(json())

	// изменить размер, сохранив то, что помещается
	fun resize(nw: Int, nh: Int) {
		val c = IntArray(nw * nh); val l = BooleanArray(nw * nh)
		for (y in 0 until minOf(h, nh)) for (x in 0 until minOf(w, nw)) { c[y * nw + x] = cells[idx(x, y)]; l[y * nw + x] = locked[idx(x, y)] }
		w = nw; h = nh; cells = c; locked = l
	}

	// удалить фигуру: её клетки освобождаются, номера остальных сдвигаются
	fun removePiece(i: Int) {
		val name = pieces[i].name
		pieces.removeAt(i)
		cells = IntArray(cells.size) { k ->
			val c = cells[k]
			when { c == 0 -> 0; (c and 0xFF) - 1 == i -> 0; (c and 0xFF) - 1 > i -> c - 1; else -> c }
		}
		promo.remove(name)
	}

	companion object {
		const val WIN_MATE = 0
		const val WIN_CAPTURE_ROYAL = 1
		const val WIN_ALL = 2
		const val WIN_HILL = 3
		const val WIN_CHECKS = 4
		val WIN_NAMES = listOf("Мат", "Взятие короля", "Взять все фигуры", "Король на холме", "N шахов")
		val STALEMATE_NAMES = listOf("Пат — ничья", "Пат — проигрыш", "Пат — победа")
		const val MIN = 4
		const val MAX = 12

		fun from(o: JSONObject): Variant {
			val pa = o.optJSONArray("pieces") ?: JSONArray()
			val w = o.optInt("w", 8).coerceIn(MIN, MAX); val h = o.optInt("h", 8).coerceIn(MIN, MAX)
			val ca = o.optJSONArray("cells"); val la = o.optJSONArray("locked")
			val v = Variant(o.optString("name", "Вариант"), w, h, MutableList(pa.length()) { PieceDef.from(pa.getJSONObject(it)) },
				IntArray(w * h) { ca?.optInt(it) ?: 0 }, BooleanArray(w * h) { la?.optBoolean(it) ?: false })
			v.win = o.optInt("win", WIN_MATE).coerceIn(0, 4)
			v.nChecks = o.optInt("nChecks", 3).coerceIn(1, 20)
			v.stalemate = o.optInt("stalemate").coerceIn(0, 2)
			v.castling = o.optBoolean("castling", true)
			v.enPassant = o.optBoolean("enPassant", true)
			v.pawnDouble = o.optBoolean("pawnDouble", true)
			o.optJSONArray("promo")?.let { a -> for (i in 0 until a.length()) v.promo.add(a.getString(i)) }
			// чистим ссылки на несуществующие фигуры
			for (i in v.cells.indices) if (v.cells[i] != 0 && !v.has(v.cells[i])) v.cells[i] = 0
			v.promo.retainAll(v.pieces.map { it.name }.toSet())
			return v
		}

		fun code(type: Int, color: Int) = (type + 1) or (color shl 8)

		// классические шахматы как отправная точка
		fun classic(): Variant {
			val v = Variant("Классические шахматы", 8, 8, PieceDef.standard(), IntArray(64), BooleanArray(64))
			val back = intArrayOf(3, 1, 2, 4, 5, 2, 1, 3)
			for (x in 0..7) {
				v.cells[v.idx(x, 0)] = code(back[x], 0); v.cells[v.idx(x, 1)] = code(0, 0)
				v.cells[v.idx(x, 7)] = code(back[x], 1); v.cells[v.idx(x, 6)] = code(0, 1)
			}
			v.promo.addAll(listOf("Ферзь", "Ладья", "Слон", "Конь"))
			return v
		}

		// маленькая доска 6×6 без слонов (как шахматы Лос-Аламоса)
		fun mini(): Variant {
			val v = Variant("Мини 6×6", 6, 6, PieceDef.standard(), IntArray(36), BooleanArray(36))
			val back = intArrayOf(3, 1, 4, 5, 1, 3)
			for (x in 0..5) {
				v.cells[v.idx(x, 0)] = code(back[x], 0); v.cells[v.idx(x, 1)] = code(0, 0)
				v.cells[v.idx(x, 5)] = code(back[x], 1); v.cells[v.idx(x, 4)] = code(0, 1)
			}
			v.castling = false; v.pawnDouble = false; v.enPassant = false
			v.promo.addAll(listOf("Ферзь", "Ладья", "Конь"))
			return v
		}
	}
}

// ===== Позиция и правила =====

// Ход: from | to << 8 | promo << 16 (индекс фигуры + 1) | флаги << 24
object VM {
	const val EP = 1
	const val CASTLE = 2
	const val DOUBLE = 4
	fun make(from: Int, to: Int, promo: Int = 0, flags: Int = 0) = from or (to shl 8) or (promo shl 16) or (flags shl 24)
	fun from(m: Int) = m and 0xFF
	fun to(m: Int) = (m shr 8) and 0xFF
	fun promo(m: Int) = (m shr 16) and 0xFF
	fun flags(m: Int) = (m ushr 24) and 0xFF
}

class VPos(val v: Variant) {
	val n = v.w * v.h
	var sq = v.cells.copyOf()
	var moved = BooleanArray(n)
	var turn = 0
	var ep = -1 // клетка, через которую перепрыгнула пешка
	var epVictim = -1
	var checks = IntArray(2)
	var quiet = 0
	val history = HashMap<Long, Int>()
	private val royalTypes = v.pieces.indices.filter { v.pieces[it].royal }.toSet()
	private val promoTypes = v.promo.mapNotNull { name -> v.pieces.indexOfFirst { it.name == name }.takeIf { it >= 0 } }
	val hasRoyalStart = IntArray(2).also { r -> for (c in v.cells) if (c != 0 && (c and 0xFF) - 1 in royalTypes) r[c shr 8]++ }
	private val usesCheck get() = (v.win == Variant.WIN_MATE || v.win == Variant.WIN_HILL || v.win == Variant.WIN_CHECKS) && royalTypes.isNotEmpty()

	fun copy(): VPos {
		val p = VPos(v)
		p.sq = sq.copyOf(); p.moved = moved.copyOf(); p.turn = turn; p.ep = ep; p.epVictim = epVictim
		p.checks = checks.copyOf(); p.quiet = quiet
		p.history.putAll(history)
		return p
	}

	fun color(code: Int) = code shr 8
	fun type(code: Int) = (code and 0xFF) - 1
	fun def(code: Int) = v.pieces[type(code)]
	fun x(s: Int) = s % v.w
	fun y(s: Int) = s / v.w
	private fun ok(x: Int, y: Int) = x in 0 until v.w && y in 0 until v.h && !v.locked[y * v.w + x]
	fun lastRank(color: Int, s: Int) = if (color == 0) y(s) == v.h - 1 else y(s) == 0

	// все клетки, куда фигура может пойти (без учёта шаха).
	// attacks — только клетки, которые фигура бьёт (в том числе пустые), для проверки шаха
	private fun gen(s: Int, out: MutableList<Int>, attacks: Boolean = false) {
		val code = sq[s]; val c = color(code); val d = def(code)
		val fy = if (c == 0) 1 else -1
		val sx = x(s); val sy = y(s)
		for (r in d.moves) {
			var tx = sx; var ty = sy
			while (true) {
				tx += r.dx * fy; ty += r.dy * fy
				if (!ok(tx, ty)) break
				val t = ty * v.w + tx
				val occ = sq[t]
				if (attacks) {
					if (r.mode != MoveRule.MOVE) out.add(VM.make(s, t))
					if (occ != 0 || !r.slide) break
					continue
				}
				if (occ == 0) {
					if (r.mode != MoveRule.CAPTURE) add(s, t, d, c, out, 0)
					// двойной ход пешки с места
					if (d.pawn && v.pawnDouble && !moved[s] && !r.slide && r.mode == MoveRule.MOVE) {
						val x2 = tx + r.dx * fy; val y2 = ty + r.dy * fy
						if (ok(x2, y2) && sq[y2 * v.w + x2] == 0) add(s, y2 * v.w + x2, d, c, out, VM.DOUBLE)
					}
					// взятие на проходе
					if (d.pawn && v.enPassant && t == ep && r.mode != MoveRule.MOVE && epVictim >= 0 && sq[epVictim] != 0 && color(sq[epVictim]) != c)
						add(s, t, d, c, out, VM.EP)
				} else {
					if (color(occ) != c && r.mode != MoveRule.MOVE) add(s, t, d, c, out, 0)
					break
				}
				if (!r.slide) break
			}
		}
	}

	private fun add(s: Int, t: Int, d: PieceDef, c: Int, out: MutableList<Int>, flags: Int) {
		if (d.pawn && lastRank(c, t) && promoTypes.isNotEmpty()) for (p in promoTypes) out.add(VM.make(s, t, p + 1, flags))
		else out.add(VM.make(s, t, 0, flags))
	}

	fun attacked(s: Int, by: Int): Boolean {
		val tmp = ArrayList<Int>(32)
		for (i in 0 until n) {
			val code = sq[i]
			if (code == 0 || color(code) != by) continue
			tmp.clear()
			gen(i, tmp, true)
			if (tmp.any { VM.to(it) == s }) return true
		}
		return false
	}

	fun royals(c: Int) = (0 until n).filter { sq[it] != 0 && color(sq[it]) == c && type(sq[it]) in royalTypes }

	fun inCheck(c: Int = turn): Boolean = royalTypes.isNotEmpty() && royals(c).any { attacked(it, 1 - c) }

	private fun castles(out: MutableList<Int>) {
		if (!v.castling) return
		for (k in royals(turn)) {
			if (moved[k]) continue
			if (usesCheck && attacked(k, 1 - turn)) continue
			val ky = y(k); val kx = x(k)
			for (dir in intArrayOf(-1, 1)) {
				var x = kx + dir
				while (x in 0 until v.w && sq[v.idx(x, ky)] == 0 && !v.locked[v.idx(x, ky)]) x += dir
				if (x !in 0 until v.w) continue
				val r = v.idx(x, ky)
				val rc = sq[r]
				if (rc == 0 || color(rc) != turn || !def(rc).castler || moved[r] || kotlin.math.abs(x - kx) < 3) continue
				val t = v.idx(kx + 2 * dir, ky)
				if (usesCheck && (attacked(v.idx(kx + dir, ky), 1 - turn) || attacked(t, 1 - turn))) continue
				out.add(VM.make(k, t, 0, VM.CASTLE))
			}
		}
	}

	fun pseudo(): MutableList<Int> {
		val out = ArrayList<Int>(64)
		for (i in 0 until n) if (sq[i] != 0 && color(sq[i]) == turn) gen(i, out)
		castles(out)
		return out
	}

	fun legal(): List<Int> {
		val ps = pseudo()
		if (!usesCheck) return ps
		val me = turn
		return ps.filter { m -> val p = copy(); p.make(m); !p.inCheck(me) }
	}

	fun make(m: Int) {
		val f = VM.from(m); val t = VM.to(m); val fl = VM.flags(m)
		val code = sq[f]
		val d = def(code)
		var capture = sq[t] != 0
		if (fl and VM.EP != 0 && epVictim >= 0) { sq[epVictim] = 0; capture = true }
		if (fl and VM.CASTLE != 0) {
			val dir = if (x(t) > x(f)) 1 else -1
			var rx = x(t) + dir
			while (sq[v.idx(rx, y(f))] == 0) rx += dir
			val r = v.idx(rx, y(f))
			val rook = sq[r]
			sq[r] = 0
			sq[v.idx(x(f) + dir, y(f))] = rook
			moved[v.idx(x(f) + dir, y(f))] = true
		}
		sq[t] = if (VM.promo(m) != 0) Variant.code(VM.promo(m) - 1, color(code)) else code
		sq[f] = 0
		moved[t] = true; moved[f] = true
		ep = -1; epVictim = -1
		if (fl and VM.DOUBLE != 0) { ep = v.idx((x(f) + x(t)) / 2, (y(f) + y(t)) / 2); epVictim = t }
		quiet = if (capture || d.pawn) 0 else quiet + 1
		turn = 1 - turn
		if (royalTypes.isNotEmpty() && inCheck(turn)) checks[1 - turn]++
	}

	fun key(): Long {
		var hsh = 1125899906842597L
		for (c in sq) hsh = 31 * hsh + c
		return 31 * (31 * hsh + turn) + ep
	}

	fun record() { val k = key(); history[k] = (history[k] ?: 0) + 1 }

	// -1 — идёт; 0 — победа белых; 1 — победа чёрных; 2 — ничья
	fun result(): Int {
		val mover = 1 - turn
		when (v.win) {
			Variant.WIN_CAPTURE_ROYAL -> for (c in 0..1) if (hasRoyalStart[c] > 0 && royals(c).isEmpty()) return 1 - c
			Variant.WIN_ALL -> for (c in 0..1) if ((0 until n).none { sq[it] != 0 && color(sq[it]) == c }) return 1 - c
			Variant.WIN_HILL -> if (royals(mover).any { onHill(it) }) return mover
			Variant.WIN_CHECKS -> if (checks[mover] >= v.nChecks) return mover
		}
		// у стороны не осталось фигур вовсе — проигрыш в любом режиме
		for (c in 0..1) if ((0 until n).none { sq[it] != 0 && color(sq[it]) == c }) return 1 - c
		if (legal().isEmpty()) {
			if (usesCheck && inCheck(turn)) return mover
			return when (v.stalemate) { 1 -> mover; 2 -> turn; else -> 2 }
		}
		if (quiet >= 100) return 2
		if ((history[key()] ?: 0) >= 3) return 2
		return -1
	}

	// «холм» — центральные 2×2 (или 1×1/1×2 на нечётной доске)
	fun onHill(s: Int): Boolean {
		val xs = if (v.w % 2 == 0) setOf(v.w / 2 - 1, v.w / 2) else setOf(v.w / 2)
		val ys = if (v.h % 2 == 0) setOf(v.h / 2 - 1, v.h / 2) else setOf(v.h / 2)
		return x(s) in xs && y(s) in ys
	}

	// материал в пользу белых
	fun material(): Int {
		var s = 0
		for (c in sq) if (c != 0) { val d = def(c); val vv = if (d.royal) 0 else d.value; s += if (color(c) == 0) vv else -vv }
		return s
	}

	// SAN-подобная запись: символ фигуры, откуда-куда
	fun moveName(m: Int): String {
		val f = VM.from(m); val t = VM.to(m)
		val d = def(sq[f])
		if (VM.flags(m) and VM.CASTLE != 0) return if (x(t) > x(f)) "0-0" else "0-0-0"
		val cap = sq[t] != 0 || VM.flags(m) and VM.EP != 0
		val p = if (VM.promo(m) != 0) "=" + v.pieces[VM.promo(m) - 1].symbol else ""
		return (if (d.pawn) "" else d.symbol) + sqName(f) + (if (cap) "x" else "-") + sqName(t) + p
	}

	fun sqName(s: Int) = "${'a' + x(s)}${y(s) + 1}"
}

// ===== Боты для любых фигур =====

interface VBot { fun pick(p: VPos, cancel: () -> Boolean): Int }

object VRandom : VBot {
	override fun pick(p: VPos, cancel: () -> Boolean): Int = p.legal().randomOrNull() ?: 0
}

// Материал: альфа-бета по чистому материалу с форсированными взятиями в конце
class VMaterial(private val depth: Int) : VBot {
	private var deadline = 0L
	private var stop = false
	private var cancel: () -> Boolean = { false }
	private val WIN = 1_000_000

	override fun pick(p: VPos, cancel: () -> Boolean): Int {
		this.cancel = cancel
		val moves = p.legal()
		if (moves.isEmpty()) return 0
		if (moves.size == 1) return moves[0]
		deadline = System.currentTimeMillis() + 2500
		stop = false
		var best = moves.shuffled()
		var bestMove = best[0]
		for (d in 1..depth) {
			var alpha = -WIN * 2
			var cur = bestMove
			val scored = ArrayList<Pair<Int, Int>>()
			for (m in best) {
				val q = p.copy(); q.make(m); q.record()
				val sc = -search(q, d - 1, -WIN * 2, -alpha, 1)
				if (stop) break
				scored.add(m to sc)
				if (sc > alpha) { alpha = sc; cur = m }
			}
			if (stop) break
			bestMove = cur
			best = scored.sortedByDescending { it.second }.map { it.first } + best.filter { m -> scored.none { it.first == m } }
			if (alpha >= WIN / 2) break
		}
		return bestMove
	}

	private fun timeUp(): Boolean {
		if (stop) return true
		if (System.currentTimeMillis() > deadline || cancel()) stop = true
		return stop
	}

	private fun evalFor(p: VPos) = if (p.turn == 0) p.material() else -p.material()

	private fun search(p: VPos, d: Int, a0: Int, b: Int, ply: Int): Int {
		if (timeUp()) return 0
		val r = p.result()
		if (r >= 0) return if (r == 2) 0 else if (r == p.turn) WIN - ply else -WIN + ply
		if (d <= 0) return quiesce(p, a0, b, 0)
		var a = a0
		val ms = order(p, p.legal())
		for (m in ms) {
			val q = p.copy(); q.make(m); q.record()
			val sc = -search(q, d - 1, -b, -a, ply + 1)
			if (stop) return 0
			if (sc > a) a = sc
			if (a >= b) break
		}
		return a
	}

	private fun quiesce(p: VPos, a0: Int, b: Int, qd: Int): Int {
		val stand = evalFor(p)
		if (stand >= b || qd >= 4) return stand
		var a = maxOf(a0, stand)
		val caps = p.pseudo().filter { p.sq[VM.to(it)] != 0 || VM.promo(it) != 0 }
		for (m in order(p, caps)) {
			if (timeUp()) return 0
			val q = p.copy(); q.make(m)
			val r = q.result()
			val sc = if (r >= 0) (if (r == 2) 0 else if (r == p.turn) WIN else -WIN) else -quiesce(q, -b, -a, qd + 1)
			if (sc > a) a = sc
			if (a >= b) break
		}
		return a
	}

	private fun order(p: VPos, ms: List<Int>) = ms.sortedByDescending { m ->
		val t = p.sq[VM.to(m)]
		(if (t != 0) p.def(t).value * 10 + (if (p.def(t).royal) 100000 else 0) - p.def(p.sq[VM.from(m)]).value else 0) + (if (VM.promo(m) != 0) 5000 else 0)
	}
}

// Алгоритм: правила вместо перебора. Уровни 1–5: на низких чаще зевает.
class VAlgorithm(private val level: Int) : VBot {
	override fun pick(p: VPos, cancel: () -> Boolean): Int {
		val moves = p.legal()
		if (moves.isEmpty()) return 0
		val blunder = when (level) { 1 -> 0.5; 2 -> 0.3; 3 -> 0.15; 4 -> 0.05; else -> 0.0 }
		if (Math.random() < blunder) return moves.random()
		val me = p.turn
		var best = moves[0]; var bestSc = Int.MIN_VALUE
		for (m in moves) {
			val q = p.copy(); q.make(m)
			val r = q.result()
			var sc: Int
			if (r == me) sc = 10_000_000
			else if (r == 1 - me) sc = -10_000_000
			else if (r == 2) sc = -50
			else {
				val target = p.sq[VM.to(m)]
				val mover = p.def(p.sq[VM.from(m)])
				val moverVal = if (VM.promo(m) != 0) p.v.pieces[VM.promo(m) - 1].value else mover.value
				sc = if (target != 0) p.def(target).value * 10 else 0
				if (VM.flags(m) and VM.EP != 0) sc += 1000
				if (VM.promo(m) != 0) sc += (moverVal - mover.value) * 10
				// не ставить фигуру под удар
				if (!mover.royal && q.attacked(VM.to(m), 1 - me)) {
					val defended = q.copy().let { z -> z.sq[VM.to(m)] = 0; z.attacked(VM.to(m), me) }
					sc -= if (defended) moverVal * 4 else moverVal * 10
				}
				// уводить свои висящие фигуры: штраф за то, что остаётся под боем
				if (level >= 2) for (s in 0 until q.n) {
					val c = q.sq[s]
					if (c == 0 || q.color(c) != me || q.def(c).royal || s == VM.to(m)) continue
					if (q.attacked(s, 1 - me) && !q.attacked(s, me)) sc -= q.def(c).value * 6
				}
				// шах полезен
				if (level >= 3 && q.inCheck(q.turn)) sc += 150
				// вилки: сколько ценных фигур соперника бьёт фигура с нового места
				if (level >= 4) {
					q.turn = me
					val hits = q.pseudo().filter { VM.from(it) == VM.to(m) && q.sq[VM.to(it)] != 0 }.map { q.def(q.sq[VM.to(it)]) }
					q.turn = 1 - me
					val forks = hits.count { it.royal || it.value > moverVal }
					if (forks >= 2) sc += 400
				}
				sc += (Math.random() * 20).toInt()
			}
			if (sc > bestSc) { bestSc = sc; best = m }
			if (cancel()) break
		}
		return best
	}
}

object VBots {
	// "H" человек, "R" случайный, "M3" материал 3, "A2" алгоритм 2
	val OPTIONS = listOf("H", "R", "M1", "M2", "M3", "M4", "A1", "A2", "A3", "A4", "A5")
	// Fairy-Stockfish: Эло, 0 — полная сила
	val FAIRY = listOf("F800", "F1200", "F1600", "F2000", "F2400", "F0")

	fun title(s: String) = when {
		s == "H" -> "Человек"
		s == "R" -> "Случайный"
		s.startsWith("M") -> "Материал ${s.drop(1)}"
		s.startsWith("A") -> "Алгоритм ${s.drop(1)}"
		s == "F0" -> "Fairy · полная сила"
		s.startsWith("F") -> "Fairy ${s.drop(1)}"
		else -> s
	}

	fun bot(s: String): VBot? = when {
		s == "R" -> VRandom
		s.startsWith("M") -> VMaterial(s.drop(1).toIntOrNull() ?: 2)
		s.startsWith("A") -> VAlgorithm(s.drop(1).toIntOrNull() ?: 3)
		else -> null
	}
}
