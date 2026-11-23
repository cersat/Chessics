package ru.cersat.chessics.chess

import kotlin.math.abs

// Перевод варианта из конструктора в описание для Fairy-Stockfish (variants.ini) и обратно.
// Судья партии — наш движок; Fairy только предлагает ход.
object FairyVariant {
	const val MAX_W = 12
	const val MAX_H = 10
	private const val LETTERS = "abcdefghijlmnopqrstuvwxyz" // без k — это король

	class Plan(val section: String, val ini: String, val letters: CharArray, private val castle: Castle?) {
		fun castleRights(p: VPos): String {
			val c = castle ?: return "-"
			val sb = StringBuilder()
			for (color in 0..1) {
				val y = if (color == 0) c.rank else p.v.h - 1 - c.rank
				val k = p.v.idx(c.king, y)
				val kc = p.sq[k]
				if (kc == 0 || p.color(kc) != color || !p.def(kc).royal || p.moved[k]) continue
				fun rook(file: Int?): Boolean {
					if (file == null) return false
					val s = p.v.idx(file, y); val rc = p.sq[s]
					return rc != 0 && p.color(rc) == color && p.def(rc).castler && !p.moved[s]
				}
				if (rook(c.rookK)) sb.append(if (color == 0) 'K' else 'k')
				if (rook(c.rookQ)) sb.append(if (color == 0) 'Q' else 'q')
			}
			return sb.toString().ifEmpty { "-" }
		}
	}

	class Castle(val rank: Int, val king: Int, val rookK: Int?, val rookQ: Int?)

	class Result(val plan: Plan?, val reason: String?)

	private fun file(x: Int) = 'a' + x
	fun sqName(v: Variant, s: Int) = "${file(s % v.w)}${s / v.w + 1}"

	// Бетца для одного правила хода: модальность + направление + атом (удвоенный — луч)
	private fun betza(r: MoveRule, ep: Boolean = false, prefix: String = ""): String? {
		val ax = abs(r.dx); val ay = abs(r.dy)
		val a = maxOf(ax, ay); val b = minOf(ax, ay)
		if (a == 0 || a > 3) return null
		val atom = when (a to b) {
			1 to 0 -> "W"; 1 to 1 -> "F"; 2 to 0 -> "D"; 2 to 1 -> "N"; 2 to 2 -> "A"
			3 to 0 -> "H"; 3 to 1 -> "C"; 3 to 2 -> "Z"; else -> "G"
		}
		val dir = when {
			b == 0 -> when { r.dy > 0 -> "ff"; r.dy < 0 -> "bb"; r.dx > 0 -> "rr"; else -> "ll" }
			ay == a -> when { r.dy > 0 && r.dx > 0 -> "rf"; r.dy > 0 -> "lf"; r.dx > 0 -> "rb"; else -> "lb" } // узкий: «вперёд» больше
			else -> when { r.dy > 0 && r.dx > 0 -> "fr"; r.dy > 0 -> "fl"; r.dx > 0 -> "br"; else -> "bl" } // широкий
		}
		val mod = when (r.mode) { MoveRule.MOVE -> "m"; MoveRule.CAPTURE -> if (ep) "ce" else "c"; else -> if (ep) "mce" else "" }
		return prefix + mod + dir + (if (r.slide) atom + atom else atom)
	}

	fun plan(v: Variant): Result {
		if (v.players == 4) return Result(null, "четверо игроков — Fairy-Stockfish такое не умеет")
		if (v.setup) return Result(null, "расстановка фигур перед партией")
		if (v.pieces.any { it.royal && it.castleAxis == 1 && v.castling }) return Result(null, "рокировка по вертикали")
		if (v.w > MAX_W || v.h > MAX_H) return Result(null, "доска больше ${MAX_W}×${MAX_H}")
		val allPieces = v.win == Variant.WIN_ALL
		val royals = if (allPieces) emptyList() else v.pieces.indices.filter { v.pieces[it].royal }
		if (royals.size > 1) return Result(null, "несколько разных королевских фигур")
		if (v.pieces.size - royals.size > LETTERS.length) return Result(null, "больше ${LETTERS.length} видов фигур")
		// сколько королевских фигур у каждой стороны в начале
		val royalCount = IntArray(2) { c -> v.cells.count { it != 0 && (it shr 8) == c && (it and 0xFF) - 1 in royals } }
		// Несколько королей на сторону: настоящий король Fairy один, поэтому короли — «псевдокоролевские» фигуры:
		// под бой их ставить нельзя, атака любого — шах, взятие любого — проигрыш (как у нас)
		val pseudo = royals.isNotEmpty() && v.win != Variant.WIN_CAPTURE_ROYAL && (royalCount[0] > 1 || royalCount[1] > 1)
		if (pseudo && royalCount[0] != royalCount[1]) return Result(null, "у сторон разное число королевских фигур (${royalCount[0]} и ${royalCount[1]})")
		// при взятии короля он у Fairy — обычная фигура: настоящий король Fairy не даёт ходить под бой
		val asKing = if (v.win == Variant.WIN_CAPTURE_ROYAL || pseudo) emptyList() else royals
		val letters = CharArray(v.pieces.size)
		var li = 0
		for (i in v.pieces.indices) letters[i] = if (i in asKing) 'k' else LETTERS[li++]
		val lines = ArrayList<String>()
		for (n in listOf("pawn", "knight", "bishop", "rook", "queen", "king")) lines.add("$n = -")
		var custom = 1
		for ((i, d) in v.pieces.withIndex()) {
			if (d.moves.isEmpty()) return Result(null, "у фигуры «${d.name}» нет ходов")
			val far = "фигура «${d.name}» ходит дальше чем на 3 клетки"
			val pawnEp = d.pawn && v.enPassant
			val parts = ArrayList<String>()
			for (r in d.moves) {
				// лучи Fairy умеет только с шагом ладьи, слона и коня
				val step = maxOf(abs(r.dx), abs(r.dy)) to minOf(abs(r.dx), abs(r.dy))
				if (r.slide && step != (1 to 0) && step != (1 to 1) && step != (2 to 1))
					return Result(null, "у фигуры «${d.name}» луч с шагом (${abs(r.dx)}, ${abs(r.dy)}) — Fairy умеет лучи только как у ладьи, слона и коня")
				parts.add(betza(r, pawnEp && r.mode != MoveRule.MOVE && !r.slide) ?: return Result(null, far))
				// двойной ход пешки с места — первый ход фигуры, не перепрыгивая (как у нас)
				if (d.pawn && v.pawnDouble && !r.slide && r.mode == MoveRule.MOVE)
					parts.add(betza(MoveRule(r.dx * 2, r.dy * 2, false, MoveRule.MOVE), false, "in") ?: return Result(null, "двойной ход пешки «${d.name}» длиннее 3 клеток"))
			}
			if (i in asKing) lines.add("king = k:${parts.joinToString("")}")
			else lines.add("customPiece${custom++} = ${letters[i]}:${parts.joinToString("")}")
		}
		lines.add("maxFile = ${v.w}")
		lines.add("maxRank = ${v.h}")
		// пешки
		val pawns = v.pieces.indices.filter { v.pieces[it].pawn && it !in asKing }.map { letters[it] }.joinToString("")
		if (pawns.isNotEmpty()) {
			lines.add("promotionPawnTypes = $pawns")
			lines.add("nMoveRuleTypes = $pawns")
			val promo = v.promo.mapNotNull { n -> v.pieces.indexOfFirst { it.name == n }.takeIf { it >= 0 && it !in asKing } }.map { letters[it] }.joinToString("")
			val promo2 = if (v.win == Variant.WIN_PROMO) "" else promo
			lines.add("promotionPieceTypes = ${promo2.ifEmpty { "-" }}")
			lines.add("promotionRegionWhite = ${v.promoCells(0).joinToString(" ") { sqName(v, it) }}")
			lines.add("promotionRegionBlack = ${v.promoCells(1).joinToString(" ") { sqName(v, it) }}")
			lines.add("mandatoryPawnPromotion = ${promo2.isNotEmpty()}")
			// двойной ход задан в Бетце как «начальный» ход; Fairy разрешает его на клетках региона — это стартовые клетки пешек
			lines.add("doubleStep = ${v.pawnDouble}")
			for (c in 0..1) {
				val sqs = v.cells.indices.filter { v.cells[it] != 0 && (v.cells[it] shr 8) == c && v.piece(v.cells[it]).pawn }.map { sqName(v, it) }
				lines.add("doubleStepRegion${if (c == 0) "White" else "Black"} = ${sqs.joinToString(" ").ifEmpty { "-" }}")
			}
		} else lines.add("promotionPieceTypes = -")
		// рокировка: король и ближайшие к нему «рокирующиеся» фигуры в начальной расстановке, у чёрных — зеркально
		var castle: Castle? = null
		if (v.castling && royals.isNotEmpty()) {
			val wk = v.cells.indices.filter { v.cells[it] != 0 && (v.cells[it] shr 8) == 0 && v.piece(v.cells[it]).royal }
			if (wk.size == 1) {
				val kx = wk[0] % v.w; val ky = wk[0] / v.w
				fun rookFile(dir: Int): Int? {
					var x = kx + dir
					while (x in 0 until v.w) {
						val c = v.cells[v.idx(x, ky)]
						if (c != 0 && (c shr 8) == 0 && v.piece(c).castler) return x
						x += dir
					}
					return null
				}
				val kd = v.piece(v.cells[wk[0]])
				val rk = rookFile(1)?.takeIf { kd.castleShort && it - kx > kd.stepsShort }
				val rq = rookFile(-1)?.takeIf { kd.castleLong && kx - it > kd.stepsLong }
				if (rk != null || rq != null) {
					// у чёрных должно быть то же самое зеркально
					fun mirrored(x: Int) = v.cells[v.idx(x, v.h - 1 - ky)].let { c -> c != 0 && (c shr 8) == 1 && (c and 0xFF) == (v.cells[v.idx(x, ky)] and 0xFF) }
					if (!mirrored(kx) || (rk != null && !mirrored(rk)) || (rq != null && !mirrored(rq)))
						return Result(null, "рокировка у чёрных расставлена не зеркально белым")
					castle = Castle(ky, kx, rk, rq)
					val castlers = v.pieces.indices.filter { v.pieces[it].castler && it !in royals }.map { letters[it] }.joinToString("")
					val kingLetter = letters[(v.cells[wk[0]] and 0xFF) - 1]
					lines.add("castling = true")
					lines.add("castlingRank = ${ky + 1}")
					lines.add("castlingKingFile = ${file(kx)}")
					lines.add("castlingKingPiece = $kingLetter")
					lines.add("castlingRookPieces = $castlers")
					if (rk != null) { lines.add("castlingKingsideFile = ${file(kx + kd.stepsShort)}"); lines.add("castlingRookKingsideFile = ${file(rk)}") }
					if (rq != null) { lines.add("castlingQueensideFile = ${file(kx - kd.stepsLong)}"); lines.add("castlingRookQueensideFile = ${file(rq)}") }
				}
			}
		}
		if (castle == null) lines.add("castling = false")
		// как побеждают
		if (pseudo) {
			lines.add("extinctionValue = loss")
			lines.add("extinctionPieceTypes = ${letters[royals[0]]}")
			lines.add("extinctionPieceCount = ${royalCount[0] - 1}")
			lines.add("extinctionPseudoRoyal = true")
		}
		when {
			pseudo && v.win == Variant.WIN_HILL -> return Result(null, "холм при нескольких королях Fairy не поддерживает")
			royals.isEmpty() -> { lines.add("checking = false"); lines.add("extinctionValue = loss"); lines.add("extinctionPieceTypes = *") }
			v.win == Variant.WIN_CAPTURE_ROYAL -> { lines.add("checking = false"); lines.add("extinctionValue = loss"); lines.add("extinctionPieceTypes = ${letters[royals[0]]}") }
			v.win == Variant.WIN_HILL -> {
				val hill = v.hillCells().joinToString(" ") { sqName(v, it) }
				lines.add("flagPiece = k"); lines.add("flagRegionWhite = $hill"); lines.add("flagRegionBlack = $hill")
			}
			v.win == Variant.WIN_PROMO -> {
				val pawnTypes = v.pieces.indices.filter { v.pieces[it].pawn && it !in asKing }
				if (pawnTypes.size != 1) return Result(null, if (pawnTypes.isEmpty()) "нет пешек, а победа — превращением" else "несколько видов пешек при победе превращением")
				lines.add("flagPiece = ${letters[pawnTypes[0]]}")
				lines.add("flagRegionWhite = ${v.promoCells(0).joinToString(" ") { sqName(v, it) }}")
				lines.add("flagRegionBlack = ${v.promoCells(1).joinToString(" ") { sqName(v, it) }}")
			}
			v.win == Variant.WIN_CHECKS -> lines.add("checkCounting = true")
		}
		// без королевских фигур в «матовых» режимах наш судья тоже считает проигрышем потерю всех фигур — это extinction выше
		lines.add("stalemateValue = ${when (v.stalemate) { 1 -> "loss"; 2 -> "win"; else -> "draw" }}")
		lines.add("nMoveRule = 50")
		lines.add("nFoldRule = 3")
		val start = VPos(v)
		val plan0 = Plan("x", "", letters, castle)
		lines.add("startFen = ${fen(start, plan0)}")
		val body = lines.joinToString("\n")
		val section = "chessics" + Integer.toHexString(body.hashCode()).replace("-", "m")
		return Result(Plan(section, "[$section:chess]\n$body\n", letters, castle), null)
	}

	fun fen(p: VPos, plan: Plan): String {
		val v = p.v
		val sb = StringBuilder()
		for (y in v.h - 1 downTo 0) {
			var empty = 0
			for (x in 0 until v.w) {
				val i = v.idx(x, y)
				val c = p.sq[i]
				if (v.locked[i] || c != 0) {
					if (empty > 0) { sb.append(empty); empty = 0 }
					if (v.locked[i]) sb.append('*')
					else { val l = plan.letters[p.type(c)]; sb.append(if (p.color(c) == 0) l.uppercaseChar() else l) }
				} else empty++
			}
			if (empty > 0) sb.append(empty)
			if (y > 0) sb.append('/')
		}
		sb.append(if (p.turn == 0) " w " else " b ")
		sb.append(plan.castleRights(p)).append(' ')
		sb.append(if (p.ep >= 0) sqName(v, p.ep) else "-")
		if (v.win == Variant.WIN_CHECKS) sb.append(" ${maxOf(0, v.nChecks - p.checks[0])}+${maxOf(0, v.nChecks - p.checks[1])}")
		sb.append(" ${p.quiet} 1")
		return sb.toString()
	}

	// ход Fairy (e2e4, a7a8q) → наш ход из списка легальных; 0 — такого у нас нет
	fun parse(p: VPos, plan: Plan, uci: String): Int {
		for (m in p.legal()) {
			val s = sqName(p.v, VM.from(m)) + sqName(p.v, VM.to(m)) + (if (VM.promo(m) != 0) plan.letters[VM.promo(m) - 1].toString() else "")
			if (s == uci) return m
		}
		return 0
	}

	// перфт для сверки с Fairy: в законченной партии ходов нет
	fun perft(p: VPos, d: Int): Long {
		if (d == 0) return 1
		if (p.result() >= 0) return 0
		var n = 0L
		for (m in p.legal()) { val q = p.copy(); q.make(m); n += perft(q, d - 1) }
		return n
	}
}
