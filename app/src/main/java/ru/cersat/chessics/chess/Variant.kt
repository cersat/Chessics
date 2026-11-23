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
	// рокировки королевской фигуры: короткая — к вертикали h (вправо), длинная — к вертикали a; на сколько клеток идёт король
	var castleShort = true
	var castleLong = true
	var stepsShort = 2
	var stepsLong = 2
	var castleAxis = 0 // 0 — рокировка вдоль горизонтали (влево-вправо), 1 — вдоль вертикали (вверх-вниз)

	fun json(): JSONObject = JSONObject().put("name", name).put("symbol", symbol).put("art", art).put("value", value)
		.put("royal", royal).put("pawn", pawn).put("castler", castler).put("moves", JSONArray().apply { moves.forEach { put(it.json()) } })
		.put("castleShort", castleShort).put("castleLong", castleLong).put("stepsShort", stepsShort).put("stepsLong", stepsLong).put("castleAxis", castleAxis)

	fun copy() = PieceDef(name, symbol, art, value, royal, pawn, castler, moves.toMutableList()).also {
		it.castleShort = castleShort; it.castleLong = castleLong; it.stepsShort = stepsShort; it.stepsLong = stepsLong; it.castleAxis = castleAxis
	}

	companion object {
		fun from(o: JSONObject): PieceDef {
			val arr = o.optJSONArray("moves") ?: JSONArray()
			return PieceDef(o.optString("name", "Фигура"), o.optString("symbol", "?"), o.optInt("art"), o.optInt("value", 300),
				o.optBoolean("royal"), o.optBoolean("pawn"), o.optBoolean("castler"),
				MutableList(arr.length()) { MoveRule.from(arr.getJSONObject(it)) }).also {
				it.castleShort = o.optBoolean("castleShort", true); it.castleLong = o.optBoolean("castleLong", true)
				it.stepsShort = o.optInt("stepsShort", 2).coerceIn(2, 6); it.stepsLong = o.optInt("stepsLong", 2).coerceIn(2, 6); it.castleAxis = o.optInt("castleAxis", 0).coerceIn(0, 1)
			}
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
	var shuffle = false // перемешивать первую линию перед каждой партией, как в Chess960
	var hill: BooleanArray = BooleanArray(w * h) // клетки холма; ни одной — центр доски
	// клетки превращения пешек каждого цвета; ни одной — последняя горизонталь (на четверых — средняя линия)
	var promoZ: Array<BooleanArray> = Array(4) { BooleanArray(w * h) }
	val promoWhite get() = promoZ[0]
	val promoBlack get() = promoZ[1]
	var players = 2 // 2 или 4 (команды 0+2 против 1+3, доска квадратная)
	var setup = false // расстановка: перед партией игроки по очереди ставят фигуры, лежащие на доске, в свои зоны
	var zone: Array<BooleanArray> = Array(4) { BooleanArray(w * h) } // зоны расстановки каждого цвета
	var shuf: Array<MutableList<Int>> = Array(4) { mutableListOf<Int>() } // клетки перемешивания каждого цвета в порядке выбора
	var dealtWarning: String? = null
	// набор для расстановки: номера фигур (с повторами), которые каждый цвет ставит в свои зоны; лежащие на доске фигуры стоят сразу
	var kit: Array<MutableList<Int>> = Array(4) { mutableListOf<Int>() }

	fun rot(c: Int) = if (players == 2) c * 2 else c
	// локальные координаты игрока (как у белых снизу) → клетка доски, и обратно; для четверых доска квадратная
	fun toG(c: Int, lx: Int, ly: Int): Int = when (rot(c)) { 0 -> idx(lx, ly); 1 -> idx(ly, w - 1 - lx); 2 -> idx(w - 1 - lx, h - 1 - ly); else -> idx(w - 1 - ly, lx) }
	fun toLx(c: Int, s: Int): Int { val x = s % w; val y = s / w; return when (rot(c)) { 0 -> x; 1 -> w - 1 - y; 2 -> w - 1 - x; else -> y } }
	fun toLy(c: Int, s: Int): Int { val x = s % w; val y = s / w; return when (rot(c)) { 0 -> y; 1 -> x; 2 -> h - 1 - y; else -> w - 1 - x } }
	fun team(c: Int) = c and 1

	fun promoCells(color: Int): List<Int> {
		val chosen = promoZ[color].let { z -> z.indices.filter { z[it] && !locked[it] } }
		if (chosen.isNotEmpty()) return chosen
		if (players == 4) return (0 until w * h).filter { !locked[it] && toLy(color, it) == w / 2 }
		val y = if (color == 0) h - 1 else 0
		return (0 until w).map { idx(it, y) }.filter { !locked[it] }
	}

	// холм: выбранные клетки, а если не выбрано ни одной — центральные 2×2 (на нечётной стороне — 1 клетка)
	fun hillCells(): List<Int> {
		val chosen = hill.indices.filter { hill[it] && !locked[it] }
		if (chosen.isNotEmpty()) return chosen
		val xs = if (w % 2 == 0) listOf(w / 2 - 1, w / 2) else listOf(w / 2)
		val ys = if (h % 2 == 0) listOf(h / 2 - 1, h / 2) else listOf(h / 2)
		return xs.flatMap { x -> ys.map { y -> idx(x, y) } }
	}

	fun idx(x: Int, y: Int) = y * w + x
	fun piece(code: Int) = pieces[(code and 0xFF) - 1]
	fun has(code: Int) = code != 0 && (code and 0xFF) in 1..pieces.size

	fun json(): JSONObject = JSONObject().put("name", name).put("w", w).put("h", h)
		.put("pieces", JSONArray().apply { pieces.forEach { put(it.json()) } })
		.put("cells", JSONArray().apply { cells.forEach { put(it) } })
		.put("locked", JSONArray().apply { locked.forEach { put(it) } })
		.put("win", win).put("nChecks", nChecks).put("stalemate", stalemate).put("castling", castling)
		.put("enPassant", enPassant).put("pawnDouble", pawnDouble).put("promo", JSONArray(promo)).put("shuffle", shuffle)
		.put("hill", JSONArray().apply { hill.forEach { put(it) } })
		.put("promoWhite", JSONArray().apply { promoZ[0].forEach { put(it) } })
		.put("promoBlack", JSONArray().apply { promoZ[1].forEach { put(it) } })
		.put("promoC2", JSONArray().apply { promoZ[2].forEach { put(it) } })
		.put("promoC3", JSONArray().apply { promoZ[3].forEach { put(it) } })
		.put("players", players).put("setup", setup)
		.put("kit", JSONArray().apply { for (c in 0..3) put(JSONArray(kit[c])) })
		.put("zones", JSONArray().apply { for (c in 0..3) put(JSONArray().apply { zone[c].forEach { put(it) } }) })
		.put("shuf", JSONArray().apply { for (c in 0..3) put(JSONArray(shuf[c])) })

	fun copy(): Variant = from(json())

	// изменить размер, сохранив то, что помещается
	fun resize(nw: Int, nh: Int) {
		val c = IntArray(nw * nh); val l = BooleanArray(nw * nh); val hl = BooleanArray(nw * nh)
		val pz = Array(4) { BooleanArray(nw * nh) }; val zz = Array(4) { BooleanArray(nw * nh) }
		for (y in 0 until minOf(h, nh)) for (x in 0 until minOf(w, nw)) {
			val o = idx(x, y); val n = y * nw + x
			c[n] = cells[o]; l[n] = locked[o]; hl[n] = hill[o]
			for (k in 0..3) { pz[k][n] = promoZ[k][o]; zz[k][n] = zone[k][o] }
		}
		val ow = w
		for (k in 0..3) shuf[k] = shuf[k].filter { it % ow < nw && it / ow < nh }.map { (it / ow) * nw + it % ow }.toMutableList()
		w = nw; h = nh; cells = c; locked = l; hill = hl; promoZ = pz; zone = zz
	}

	// расстановка для очередной партии: при перемешивании — случайная первая линия.
	// Одинаковые «одноцветные» фигуры (как слоны) встают на клетки разного цвета, король — между двумя рокирующимися фигурами.
	fun dealt(rnd: java.util.Random = java.util.Random()): Variant {
		val picked = shuffle && (0 until players).any { shuf[it].size >= 2 }
		val legacy = shuffle && players == 2 && !picked
		if (!legacy && !picked) return this
		val d = copy()
		d.shuffle = false
		if (picked) {
			// своё перемешивание: фигуры на выбранных клетках каждого цвета меняются местами; рокировка должна остаться возможной
			val want = (0 until players).map { castleFeasible(cells, it) }
			var tries = 0
			while (true) {
				for (c in 0 until players) {
					val sel = shuf[c]
					if (sel.size < 2) continue
					val items = sel.map { cells[it] }.shuffled(rnd)
					sel.forEachIndexed { i, s -> d.cells[s] = items[i] }
				}
				val ok = (0 until players).all { !want[it] || castleFeasible(d.cells, it) }
				if (ok || ++tries >= 300) {
					if (!ok) d.dealtWarning = "после перемешивания рокировка невозможна: " + (0 until players).filter { want[it] && !castleFeasible(d.cells, it) }.joinToString(", ") { colorName(it) }
					break
				}
			}
			return d
		}
		val white = (0 until w).map { idx(it, 0) }.filter { cells[it] != 0 && (cells[it] shr 8) == 0 }
		val black = (0 until w).map { idx(it, h - 1) }.filter { cells[it] != 0 && (cells[it] shr 8) == 1 }
		val mirrored = white.map { it % w } == black.map { it % w } && white.all { s -> (cells[idx(s % w, h - 1)] and 0xFF) == (cells[s] and 0xFF) }
		val wx = deal(white.map { it % w }, white.map { cells[it] and 0xFF }, 0, rnd)
		for ((x, t) in wx) d.cells[idx(x, 0)] = t
		if (mirrored) for ((x, t) in wx) d.cells[idx(x, h - 1)] = t or (1 shl 8)
		else for ((x, t) in deal(black.map { it % w }, black.map { cells[it] and 0xFF }, h - 1, rnd)) d.cells[idx(x, h - 1)] = t or (1 shl 8)
		return d
	}

	fun colorName(c: Int) = if (players == 2) (if (c == 0) "белые" else "чёрные") else listOf("красные", "синие", "жёлтые", "зелёные")[c]

	// можно ли в принципе рокироваться цвету c в расстановке cs: есть королевская фигура и своя «рокирующаяся» фигура
	// в нужную сторону дальше, чем шаг короля (фигуры между ними со временем уйдут)
	fun castleFeasible(cs: IntArray, c: Int): Boolean {
		if (!castling || players == 4) return false
		for (k in cs.indices) {
			val code = cs[k]
			if (code == 0 || (code shr 8) != c || !has(code)) continue
			val kd = piece(code)
			if (!kd.royal) continue
			val vertical = kd.castleAxis == 1
			val ky = k / w; val kx = k % w
			val kp = if (vertical) ky else kx
			val lim = if (vertical) h else w
			for (dir in intArrayOf(-1, 1)) {
				if (if (dir > 0) !kd.castleShort else !kd.castleLong) continue
				val steps = if (dir > 0) kd.stepsShort else kd.stepsLong
				var p = kp + dir * (steps + 1)
				while (p in 0 until lim) {
					val rc = cs[if (vertical) idx(kx, p) else idx(p, ky)]
					if (rc != 0 && has(rc) && (rc shr 8) == c && piece(rc).castler) return true
					p += dir
				}
			}
		}
		return false
	}

	private fun deal(xs: List<Int>, types: List<Int>, y: Int, rnd: java.util.Random): List<Pair<Int, Int>> {
		if (xs.size < 2) return xs.zip(types)
		fun bound(t: Int) = pieces[t - 1].moves.isNotEmpty() && pieces[t - 1].moves.all { (it.dx + it.dy) % 2 == 0 }
		val pairs = types.groupBy { it }.filter { (t, l) -> l.size == 2 && bound(t) }.keys
		val royal = types.filter { pieces[it - 1].royal }
		val castlers = types.filter { pieces[it - 1].castler }
		var best = types
		for (attempt in 0 until 2000) {
			val p = types.shuffled(rnd)
			val okColors = pairs.all { t -> val at = xs.indices.filter { p[it] == t }; (xs[at[0]] + y) % 2 != (xs[at[1]] + y) % 2 }
			val okKing = royal.size != 1 || castlers.size != 2 || run {
				val k = xs[p.indexOf(royal[0])]; val cs = xs.indices.filter { pieces[p[it] - 1].castler }.map { xs[it] }
				k > cs.min() && k < cs.max()
			}
			best = p
			if (okColors && okKing) break
		}
		return xs.zip(best)
	}

	// сменить число игроков: на четверых доска квадратная, лишние цвета убираются
	fun switchPlayers(n: Int) {
		if (n == players) return
		if (n == 4) {
			val m = maxOf(w, h)
			if (w != h) resize(m, m)
			players = 4; castling = false; enPassant = false
		} else {
			players = 2
			for (i in cells.indices) if (cells[i] != 0 && (cells[i] shr 8) >= 2) cells[i] = 0
		}
	}

	// зона расстановки цвета: выбранные клетки, а если не выбрано ни одной — две первые горизонтали игрока
	fun zoneCells(c: Int): List<Int> {
		val chosen = zone[c].indices.filter { zone[c][it] && !locked[it] }
		if (chosen.isNotEmpty()) return chosen
		return (0 until w * h).filter { !locked[it] && toLy(c, it) <= 1 }
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
		for (k in 0..3) kit[k] = kit[k].filter { it != i }.map { if (it > i) it - 1 else it }.toMutableList()
	}

	companion object {
		const val WIN_MATE = 0
		const val WIN_CAPTURE_ROYAL = 1
		const val WIN_ALL = 2
		const val WIN_HILL = 3
		const val WIN_CHECKS = 4
		const val WIN_PROMO = 5
		val WIN_NAMES = listOf("Мат", "Взятие короля", "Взять все фигуры", "Король на холме", "N шахов", "Пешка дошла до конца")
		val STALEMATE_NAMES = listOf("Пат — ничья", "Пат — проигрыш", "Пат — победа")
		const val MIN = 4
		const val MAX = 16

		fun from(o: JSONObject): Variant {
			val pa = o.optJSONArray("pieces") ?: JSONArray()
			val w = o.optInt("w", 8).coerceIn(MIN, MAX); val h = o.optInt("h", 8).coerceIn(MIN, MAX)
			val ca = o.optJSONArray("cells"); val la = o.optJSONArray("locked")
			val v = Variant(o.optString("name", "Вариант"), w, h, MutableList(pa.length()) { PieceDef.from(pa.getJSONObject(it)) },
				IntArray(w * h) { ca?.optInt(it) ?: 0 }, BooleanArray(w * h) { la?.optBoolean(it) ?: false })
			v.win = o.optInt("win", WIN_MATE).coerceIn(0, 5)
			v.nChecks = o.optInt("nChecks", 3).coerceIn(1, 20)
			v.stalemate = o.optInt("stalemate").coerceIn(0, 2)
			v.castling = o.optBoolean("castling", true)
			v.enPassant = o.optBoolean("enPassant", true)
			v.pawnDouble = o.optBoolean("pawnDouble", true)
			v.shuffle = o.optBoolean("shuffle", false)
			o.optJSONArray("hill")?.let { a -> if (a.length() == w * h) v.hill = BooleanArray(w * h) { a.optBoolean(it) } }
			for ((k, key) in listOf("promoWhite", "promoBlack", "promoC2", "promoC3").withIndex())
				o.optJSONArray(key)?.let { a -> if (a.length() == w * h) v.promoZ[k] = BooleanArray(w * h) { a.optBoolean(it) } }
			v.players = if (o.optInt("players", 2) == 4 && w == h) 4 else 2
			v.setup = o.optBoolean("setup", false)
			o.optJSONArray("kit")?.let { ka -> for (k in 0 until minOf(4, ka.length())) ka.optJSONArray(k)?.let { a -> v.kit[k] = MutableList(a.length()) { a.optInt(it) }.toMutableList() } }
			o.optJSONArray("zones")?.let { za -> for (k in 0 until minOf(4, za.length())) za.optJSONArray(k)?.let { a -> if (a.length() == w * h) v.zone[k] = BooleanArray(w * h) { a.optBoolean(it) } } }
			o.optJSONArray("shuf")?.let { sa -> for (k in 0 until minOf(4, sa.length())) sa.optJSONArray(k)?.let { a -> v.shuf[k] = MutableList(a.length()) { a.optInt(it) }.filter { it in 0 until w * h }.toMutableList() } }
			o.optJSONArray("promo")?.let { a -> for (i in 0 until a.length()) v.promo.add(a.getString(i)) }
			// чистим ссылки на несуществующие фигуры
			for (i in v.cells.indices) if (v.cells[i] != 0 && (!v.has(v.cells[i]) || (v.cells[i] shr 8) >= v.players)) v.cells[i] = 0
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

		// шахматы на четверых: доска 14×14 без углов 3×3, команды красные+жёлтые против синих+зелёных
		fun four(): Variant {
			val n = 14
			val v = Variant("На четверых", n, n, PieceDef.standard(), IntArray(n * n), BooleanArray(n * n))
			v.players = 4; v.castling = false; v.enPassant = false
			for (y in 0 until n) for (x in 0 until n) if ((x < 3 || x > 10) && (y < 3 || y > 10)) v.locked[v.idx(x, y)] = true
			val back = intArrayOf(3, 1, 2, 4, 5, 2, 1, 3)
			for (c in 0..3) for (i in 0..7) {
				v.cells[v.toG(c, 3 + i, 0)] = code(back[i], c)
				v.cells[v.toG(c, 3 + i, 1)] = code(0, c)
			}
			v.promo.addAll(listOf("Ферзь", "Ладья", "Слон", "Конь"))
			return v
		}

		// расстановка: фигуры лежат на доске как набор, игроки по очереди ставят их на первые две горизонтали
		fun setupChess(): Variant {
			val v = classic()
			v.name = "Расстановка"; v.setup = true
			v.cells.fill(0)
			for (c in 0..1) { v.kit[c].addAll(List(8) { 0 }); v.kit[c].addAll(listOf(1, 1, 2, 2, 3, 3, 4, 5)) }
			return v
		}

		fun chess960() = classic().also { it.name = "Chess960"; it.shuffle = true }
		fun threeCheck() = classic().also { it.name = "Три шаха"; it.win = WIN_CHECKS; it.nChecks = 3 }
		fun koth() = classic().also { it.name = "Царь горы"; it.win = WIN_HILL }

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
	var checks = IntArray(2) // шахи по командам
	var quiet = 0
	var promoted = -1 // команда, чья пешка дошла до клетки превращения (для победы превращением)
	val history = HashMap<Long, Int>()
	private val royalTypes = v.pieces.indices.filter { v.pieces[it].royal }.toSet()
	private val promoTypes = v.promo.mapNotNull { name -> v.pieces.indexOfFirst { it.name == name }.takeIf { it >= 0 } }
	val hasRoyalStart = IntArray(4).also { r -> for (c in v.cells) if (c != 0 && (c and 0xFF) - 1 in royalTypes) r[c shr 8]++ }
	private val usesCheck get() = (v.win == Variant.WIN_MATE || v.win == Variant.WIN_HILL || v.win == Variant.WIN_CHECKS || v.win == Variant.WIN_PROMO) && royalTypes.isNotEmpty()
	private val hillSet by lazy { v.hillCells().toHashSet() }
	private val promoSets by lazy { Array(4) { c -> v.promoCells(c).toHashSet() } }

	fun copy(): VPos {
		val p = VPos(v)
		p.sq = sq.copyOf(); p.moved = moved.copyOf(); p.turn = turn; p.ep = ep; p.epVictim = epVictim
		p.checks = checks.copyOf(); p.quiet = quiet; p.promoted = promoted
		p.history.putAll(history)
		return p
	}

	fun color(code: Int) = code shr 8
	fun team(c: Int) = c and 1
	fun foe(c: Int) = 1 - (c and 1) // команда соперников цвета c
	val players get() = v.players
	fun prev(c: Int = turn) = (c + players - 1) % players
	fun type(code: Int) = (code and 0xFF) - 1
	fun def(code: Int) = v.pieces[type(code)]
	fun x(s: Int) = s % v.w
	fun y(s: Int) = s / v.w
	private fun ok(x: Int, y: Int) = x in 0 until v.w && y in 0 until v.h && !v.locked[y * v.w + x]
	// клетка превращения для пешки этого цвета
	fun lastRank(color: Int, s: Int) = s in promoSets[color]

	// все клетки, куда фигура может пойти (без учёта шаха).
	// attacks — только клетки, которые фигура бьёт (в том числе пустые), для проверки шаха
	private fun gen(s: Int, out: MutableList<Int>, attacks: Boolean = false) {
		val code = sq[s]; val c = color(code); val d = def(code)
		val rt = v.rot(c)
		val sx = x(s); val sy = y(s)
		for (r in d.moves) {
			val wx = when (rt) { 0 -> r.dx; 1 -> r.dy; 2 -> -r.dx; else -> -r.dy }
			val wy = when (rt) { 0 -> r.dy; 1 -> -r.dx; 2 -> -r.dy; else -> r.dx }
			var tx = sx; var ty = sy
			while (true) {
				tx += wx; ty += wy
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
						val x2 = tx + wx; val y2 = ty + wy
						if (ok(x2, y2) && sq[y2 * v.w + x2] == 0) add(s, y2 * v.w + x2, d, c, out, VM.DOUBLE)
					}
					// взятие на проходе
					if (d.pawn && v.enPassant && t == ep && r.mode != MoveRule.MOVE && epVictim >= 0 && sq[epVictim] != 0 && team(color(sq[epVictim])) != team(c))
						add(s, t, d, c, out, VM.EP)
				} else {
					if (team(color(occ)) != team(c) && r.mode != MoveRule.MOVE) add(s, t, d, c, out, 0)
					break
				}
				if (!r.slide) break
			}
		}
	}

	private fun add(s: Int, t: Int, d: PieceDef, c: Int, out: MutableList<Int>, flags: Int) {
		// при победе превращением дошедшая пешка сразу выигрывает — превращать её не во что
		if (d.pawn && lastRank(c, t) && promoTypes.isNotEmpty() && v.win != Variant.WIN_PROMO) for (p in promoTypes) out.add(VM.make(s, t, p + 1, flags))
		else out.add(VM.make(s, t, 0, flags))
	}

	// клетку s бьёт команда by (на двоих — цвет by)
	fun attacked(s: Int, by: Int): Boolean {
		val tmp = ArrayList<Int>(32)
		for (i in 0 until n) {
			val code = sq[i]
			if (code == 0 || team(color(code)) != by) continue
			tmp.clear()
			gen(i, tmp, true)
			if (tmp.any { VM.to(it) == s }) return true
		}
		return false
	}

	fun royals(c: Int) = (0 until n).filter { sq[it] != 0 && color(sq[it]) == c && type(sq[it]) in royalTypes }

	fun inCheck(c: Int = turn): Boolean = royalTypes.isNotEmpty() && royals(c).any { attacked(it, foe(c)) }

	private fun castles(out: MutableList<Int>) {
		if (!v.castling || players == 4) return
		for (k in royals(turn)) {
			if (moved[k]) continue
			if (usesCheck && attacked(k, foe(turn))) continue
			val ky = y(k); val kx = x(k)
			val kd = def(sq[k])
			val vertical = kd.castleAxis == 1
			val lim = if (vertical) v.h else v.w
			fun at(q: Int) = if (vertical) v.idx(kx, q) else v.idx(q, ky)
			val kp = if (vertical) ky else kx
			for (dir in intArrayOf(-1, 1)) {
				// вправо (к вертикали h) или вверх — короткая, влево или вниз — длинная; у каждой свой шаг короля
				if (if (dir > 0) !kd.castleShort else !kd.castleLong) continue
				val steps = if (dir > 0) kd.stepsShort else kd.stepsLong
				var x = kp + dir
				while (x in 0 until lim && sq[at(x)] == 0 && !v.locked[at(x)]) x += dir
				if (x !in 0 until lim) continue
				val r = at(x)
				val rc = sq[r]
				if (rc == 0 || color(rc) != turn || !def(rc).castler || moved[r] || kotlin.math.abs(x - kp) <= steps) continue
				val t = at(kp + steps * dir)
				if (usesCheck && (1..steps).any { attacked(at(kp + it * dir), foe(turn)) }) continue
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
			val vertical = d.castleAxis == 1
			val dir = if (if (vertical) y(t) > y(f) else x(t) > x(f)) 1 else -1
			fun at(q: Int) = if (vertical) v.idx(x(f), q) else v.idx(q, y(f))
			val tp = if (vertical) y(t) else x(t)
			var rx = tp + dir
			while (sq[at(rx)] == 0) rx += dir
			val r = at(rx)
			val rook = sq[r]
			sq[r] = 0
			// ладья встаёт рядом с королём с внутренней стороны
			sq[at(tp - dir)] = rook
			moved[at(tp - dir)] = true
		}
		if (d.pawn && lastRank(color(code), t)) promoted = team(color(code))
		sq[t] = if (VM.promo(m) != 0) Variant.code(VM.promo(m) - 1, color(code)) else code
		sq[f] = 0
		moved[t] = true; moved[f] = true
		ep = -1; epVictim = -1
		if (fl and VM.DOUBLE != 0) { ep = v.idx((x(f) + x(t)) / 2, (y(f) + y(t)) / 2); epVictim = t }
		quiet = if (capture || d.pawn) 0 else quiet + 1
		val mover = turn
		turn = (turn + 1) % players
		if (royalTypes.isNotEmpty() && inCheck(turn)) checks[team(mover)]++
	}

	fun key(): Long {
		var hsh = 1125899906842597L
		for (c in sq) hsh = 31 * hsh + c
		return 31 * (31 * hsh + turn) + ep
	}

	fun record() { val k = key(); history[k] = (history[k] ?: 0) + 1 }

	// -1 — идёт; 0/1 — победила команда (на двоих — белые/чёрные); 2 — ничья
	fun result(): Int {
		val prevColor = prev()
		val mover = team(prevColor)
		when (v.win) {
			Variant.WIN_CAPTURE_ROYAL -> for (c in 0 until players) if (hasRoyalStart[c] > 0 && royals(c).isEmpty()) return 1 - team(c)
			Variant.WIN_ALL -> for (t in 0..1) if ((0 until n).none { sq[it] != 0 && team(color(sq[it])) == t }) return 1 - t
			Variant.WIN_HILL -> if (royals(prevColor).any { onHill(it) }) return mover
			Variant.WIN_CHECKS -> if (checks[mover] >= v.nChecks) return mover
			Variant.WIN_PROMO -> if (promoted == mover) return mover
		}
		// у команды не осталось фигур вовсе — проигрыш в любом режиме
		for (t in 0..1) if ((0 until n).none { sq[it] != 0 && team(color(sq[it])) == t }) return 1 - t
		if (legal().isEmpty()) {
			if (usesCheck && inCheck(turn)) return mover
			return when (v.stalemate) { 1 -> mover; 2 -> team(turn); else -> 2 }
		}
		if (quiet >= 50 * players) return 2
		if ((history[key()] ?: 0) >= 3) return 2
		return -1
	}

	// «холм» — центральные 2×2 (или 1×1/1×2 на нечётной доске)
	fun onHill(s: Int): Boolean = s in hillSet

	// материал в пользу белых
	fun material(): Int {
		var s = 0
		for (c in sq) if (c != 0) { val d = def(c); val vv = if (d.royal) 0 else d.value; s += if (team(color(c)) == 0) vv else -vv }
		return s
	}

	// SAN-подобная запись: символ фигуры, откуда-куда
	fun moveName(m: Int): String {
		val f = VM.from(m); val t = VM.to(m)
		val d = def(sq[f])
		if (VM.flags(m) and VM.CASTLE != 0) return if (if (d.castleAxis == 1) y(t) > y(f) else x(t) > x(f)) "0-0" else "0-0-0"
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

	// Разбор позиции без ограничения «лучший ход»: оценка каждого хода (за ходящего) на глубину depth, не дольше ms.
	// Возвращает ходы по убыванию оценки; пусто — если позиция закончена или время вышло до первого хода.
	fun lines(p: VPos, depth: Int, ms: Long, cancel: () -> Boolean = { false }): List<Pair<Int, Int>> {
		this.cancel = cancel
		val moves = p.legal()
		if (moves.isEmpty()) return emptyList()
		deadline = System.currentTimeMillis() + ms
		stop = false
		var done: List<Pair<Int, Int>> = emptyList()
		var order = moves
		for (d in 1..depth) {
			val scored = ArrayList<Pair<Int, Int>>()
			for (m in order) {
				val q = p.copy(); q.make(m); q.record()
				val sc = -search(q, d - 1, -WIN * 2, WIN * 2, 1)
				if (stop) break
				scored.add(m to sc)
			}
			if (stop) break
			done = scored.sortedByDescending { it.second }
			order = done.map { it.first }
			if (done[0].second >= WIN / 2) break
		}
		return done
	}

	private fun timeUp(): Boolean {
		if (stop) return true
		if (System.currentTimeMillis() > deadline || cancel()) stop = true
		return stop
	}

	private fun evalFor(p: VPos) = if (p.team(p.turn) == 0) p.material() else -p.material()

	private fun search(p: VPos, d: Int, a0: Int, b: Int, ply: Int): Int {
		if (timeUp()) return 0
		val r = p.result()
		if (r >= 0) return if (r == 2) 0 else if (r == p.team(p.turn)) WIN - ply else -WIN + ply
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
			val sc = if (r >= 0) (if (r == 2) 0 else if (r == p.team(p.turn)) WIN else -WIN) else -quiesce(q, -b, -a, qd + 1)
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

// Алгоритм: правила вместо перебора. Уровни 1–5: на низких чаще зевает. Оценку позиции (полосу) не использует — только материал и свои правила.
class VAlgorithm(private val level: Int) : VBot {
	private fun center(v: Variant, s: Int): Double {
		// чем ближе к центру доски, тем лучше: 0..1
		val cx = (v.w - 1) / 2.0; val cy = (v.h - 1) / 2.0
		val d = Math.abs(s % v.w - cx) / (cx + 0.5) + Math.abs(s / v.w - cy) / (cy + 0.5)
		return 1.0 - d / 2
	}

	override fun pick(p: VPos, cancel: () -> Boolean): Int {
		val moves = p.legal()
		if (moves.isEmpty()) return 0
		val blunder = when (level) { 1 -> 0.5; 2 -> 0.3; 3 -> 0.15; 4 -> 0.05; else -> 0.0 }
		if (Math.random() < blunder) return moves.random()
		val me = p.turn
		val myTeam = p.team(me); val foe = p.foe(me)
		val small = p.n <= 144
		var best = moves[0]; var bestSc = Double.NEGATIVE_INFINITY
		// сколько своих фигур висит сейчас — чтобы поощрять ходы, которые это исправляют
		fun hangingNow(q: VPos): Int {
			var sum = 0
			for (s in 0 until q.n) {
				val c = q.sq[s]
				if (c == 0 || q.color(c) != me || q.def(c).royal) continue
				if (q.attacked(s, foe) && !q.attacked(s, myTeam)) sum += q.def(c).value
			}
			return sum
		}
		val hangBefore = if (level >= 2) hangingNow(p) else 0
		for (m in moves) {
			val q = p.copy(); q.make(m)
			val r = q.result()
			var sc: Double
			if (r == myTeam) sc = 10_000_000.0
			else if (r == 1 - myTeam) sc = -10_000_000.0
			else if (r == 2) sc = -50.0
			else {
				val target = p.sq[VM.to(m)]
				val mover = p.def(p.sq[VM.from(m)])
				val moverVal = if (VM.promo(m) != 0) p.v.pieces[VM.promo(m) - 1].value else mover.value
				sc = if (target != 0) p.def(target).value * 10.0 else 0.0
				if (VM.flags(m) and VM.EP != 0) sc += 1000
				if (VM.promo(m) != 0) sc += (moverVal - mover.value) * 10
				// не ставить фигуру под удар; защищённая стоит дешевле
				if (!mover.royal && q.attacked(VM.to(m), foe)) {
					val defended = q.copy().let { z -> z.sq[VM.to(m)] = 0; z.attacked(VM.to(m), myTeam) }
					// размен выгоден, если взяли не меньше, чем отдаём
					val cap = if (target != 0) p.def(target).value else 0
					sc -= if (defended) maxOf(0, moverVal - cap) * 4.0 else moverVal * 10.0
				}
				if (level >= 2) {
					// уводить свои висящие фигуры: штраф за то, что остаётся под боем (считаем только после хода)
					val hang = hangingNow(q)
					sc -= hang * 6.0
					if (hang < hangBefore) sc += (hangBefore - hang) * 2.0
				}
				// шах полезен
				if (level >= 3 && q.inCheck(q.turn)) sc += 150
				if (level >= 3) {
					// развитие и центр: ранние ходы фигур к центру, пешек к центру — чуть лучше
					val gain = center(p.v, VM.to(m)) - center(p.v, VM.from(m))
					sc += gain * (if (mover.pawn) 25 else 40)
					// не бросать короля вперёд без нужды
					if (mover.royal && VM.flags(m) and VM.CASTLE == 0 && p.v.castling) sc -= 30
					if (VM.flags(m) and VM.CASTLE != 0) sc += 120
					// пешка, идущая к клетке превращения, — ценна
					if (mover.pawn) {
						val c = p.color(p.sq[VM.from(m)])
						val lx = p.v.toLy(c, VM.to(m)); val far = if (p.players == 4) p.v.w / 2 else p.v.h - 1
						if (lx * 2 >= far) sc += (lx * 2 - far + 1) * 6.0
					}
				}
				// вилки: сколько ценных фигур соперника бьёт фигура с нового места
				if (level >= 4 && small) {
					val savedTurn = q.turn
					q.turn = me
					val hits = q.pseudo().filter { VM.from(it) == VM.to(m) && q.sq[VM.to(it)] != 0 && q.team(q.color(q.sq[VM.to(it)])) != myTeam }.map { q.def(q.sq[VM.to(it)]) }
					q.turn = savedTurn
					val forks = hits.count { it.royal || it.value > moverVal }
					if (forks >= 2) sc += 400
					// не пропустить мат в один ход: смотрим ответы соперника
					if (!cancel() && q.players == 2) {
						for (reply in q.legal()) {
							val z = q.copy(); z.make(reply)
							if (z.result() == 1 - myTeam) { sc -= 5000.0; break }
						}
					}
				}
				sc += Math.random() * 20
			}
			if (sc > bestSc) { bestSc = sc; best = m }
			if (cancel()) break
		}
		return best
	}
}

object VBots {
	private fun icon(d: PieceDef) = if (d.art in 1..6) "a${d.art}" else "s${d.symbol}"

	// Бот видит доску так же, как человек: вражеские фигуры с одинаковой иконкой он не различает, поэтому
	// перемешивает их между собой (число фигур каждого вида сохраняется) и считает на случайном допущении. Свои фигуры не трогает.
	fun blur(p: VPos, me: Int) {
		val groups = HashMap<String, MutableList<Int>>()
		for (s in 0 until p.n) {
			val c = p.sq[s]
			if (c == 0 || !p.v.has(c) || p.team(p.color(c)) == p.team(me)) continue
			groups.getOrPut(icon(p.v.piece(c)) + "/" + p.color(c)) { mutableListOf() }.add(s)
		}
		for (sqs in groups.values) {
			if (sqs.size < 2) continue
			val codes = sqs.map { p.sq[it] }
			if (codes.distinct().size < 2) continue
			val mixed = codes.shuffled()
			sqs.forEachIndexed { i, s -> p.sq[s] = mixed[i] }
		}
	}

	// "H" человек, "R" случайный, "M3" материал 3, "A2" алгоритм 2
	val OPTIONS = listOf("H", "N", "R", "M1", "M2", "M3", "M4", "A1", "A2", "A3", "A4", "A5")
	// Fairy-Stockfish: Эло, 0 — полная сила
	val FAIRY = listOf("F800", "F1200", "F1600", "F2000", "F2400", "F0")

	fun title(s: String) = when {
		s == "H" -> "Человек"
		s == "R" -> "Случайный"
		s.startsWith("M") -> "Материал ${s.drop(1)}"
		s.startsWith("A") -> "Алгоритм ${s.drop(1)}"
		s == "N" -> "Нейросеть"
		s == "L" -> "Соперник по сети"
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
