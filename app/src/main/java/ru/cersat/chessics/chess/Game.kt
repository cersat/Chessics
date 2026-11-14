package ru.cersat.chessics.chess

object San {
	private val EN = charArrayOf(' ', 'P', 'N', 'B', 'R', 'Q', 'K')
	private val RU = arrayOf("", "", "К", "С", "Л", "Ф", "Кр")

	// SAN до совершения хода (позиция pos — до хода). ru = русские буквы для интерфейса.
	fun of(pos: Position, m: Int, ru: Boolean = false): String {
		val from = M.from(m); val to = M.to(m)
		val p = pos.sq[from]
		val type = P.type(p)
		val sb = StringBuilder()
		if (M.isCastle(m)) {
			sb.append(if (pos.isShortCastle(m)) "O-O" else "O-O-O")
		} else {
			val capture = pos.sq[to] != 0 || M.isEp(m)
			if (type == P.PAWN) {
				if (capture) sb.append('a' + P.file(from)).append(if (ru) ":" else "x")
				sb.append(P.name(to))
				val pr = M.promo(m)
				if (pr != 0) sb.append('=').append(if (ru) RU[pr] else EN[pr].toString())
			} else {
				sb.append(if (ru) RU[type] else EN[type].toString())
				// неоднозначность
				var sameFile = false; var sameRank = false; var other = false
				for (o in pos.legalMoves()) {
					if (o == m || M.to(o) != to || M.from(o) == from) continue
					if (pos.sq[M.from(o)] != p) continue
					other = true
					if (P.file(M.from(o)) == P.file(from)) sameFile = true
					if (P.rank(M.from(o)) == P.rank(from)) sameRank = true
				}
				if (other) {
					if (!sameFile) sb.append('a' + P.file(from))
					else if (!sameRank) sb.append('1' + P.rank(from))
					else sb.append(P.name(from))
				}
				if (capture) sb.append(if (ru) ":" else "x")
				sb.append(P.name(to))
			}
		}
		pos.make(m)
		if (pos.inCheck()) sb.append(if (pos.hasLegalMove()) "+" else "#")
		pos.unmake()
		return sb.toString()
	}

	fun parse(pos: Position, san: String): Int {
		val clean = san.replace("+", "").replace("#", "").replace("!", "").replace("?", "")
			.replace("0-0-0", "O-O-O").replace("0-0", "O-O")
		for (m in pos.legalMoves()) if (of(pos, m).replace("+", "").replace("#", "") == clean) return m
		return 0
	}
}

enum class Outcome { NONE, WHITE_WINS, BLACK_WINS, DRAW }

class GameResult(val outcome: Outcome, val reason: String) {
	val pgnTag get() = when (outcome) {
		Outcome.WHITE_WINS -> "1-0"
		Outcome.BLACK_WINS -> "0-1"
		Outcome.DRAW -> "1/2-1/2"
		Outcome.NONE -> "*"
	}
}

object Rules {
	// Результат по позиции (без времени и сдачи)
	fun status(pos: Position): GameResult {
		val vw = pos.variantWinner()
		if (vw >= 0) {
			val why = if (pos.variant == V.KOTH) "король в центре" else "три шаха"
			return GameResult(if (vw == P.WHITE) Outcome.WHITE_WINS else Outcome.BLACK_WINS, why)
		}
		if (!pos.hasLegalMove()) {
			return if (pos.inCheck()) {
				if (pos.side == P.WHITE) GameResult(Outcome.BLACK_WINS, "мат") else GameResult(Outcome.WHITE_WINS, "мат")
			} else GameResult(Outcome.DRAW, "пат")
		}
		if (pos.insufficientMaterial()) return GameResult(Outcome.DRAW, "недостаточно материала")
		if (pos.half >= 100) return GameResult(Outcome.DRAW, "правило 50 ходов")
		if (pos.repetitions() >= 3) return GameResult(Outcome.DRAW, "троекратное повторение")
		return GameResult(Outcome.NONE, "")
	}
}

// Партия: стартовая позиция + список ходов. Позволяет отмену и пересчёт.
class GameRecord(val startFen: String = Position.START, val variant: Int = V.STANDARD) {
	val moves = ArrayList<Int>()
	val sanEn = ArrayList<String>()
	val sanRu = ArrayList<String>()
	val pos = Position.of(startFen, variant)

	fun play(m: Int) {
		sanEn.add(San.of(pos, m, false))
		sanRu.add(San.of(pos, m, true))
		moves.add(m)
		pos.make(m)
	}

	fun undo(): Boolean {
		if (moves.isEmpty()) return false
		moves.removeAt(moves.size - 1)
		sanEn.removeAt(sanEn.size - 1)
		sanRu.removeAt(sanRu.size - 1)
		pos.unmake()
		return true
	}

	fun positionAt(ply: Int): Position {
		val p = Position.of(startFen, variant)
		for (i in 0 until minOf(ply, moves.size)) p.make(moves[i])
		return p
	}

	val lastMove: Int get() = if (moves.isEmpty()) 0 else moves[moves.size - 1]

	fun startSide(): Int = Position.of(startFen, variant).side

	fun pgn(white: String, black: String, result: String, date: String): String {
		val sb = StringBuilder()
		sb.append("[Event \"Chessics\"]\n[Date \"").append(date).append("\"]\n")
		sb.append("[White \"").append(white).append("\"]\n[Black \"").append(black).append("\"]\n")
		sb.append("[Result \"").append(result).append("\"]\n")
		if (variant != V.STANDARD) sb.append("[Variant \"").append(arrayOf("Standard", "Chess960", "Three-check", "King of the Hill")[variant]).append("\"]\n")
		if (startFen != Position.START) sb.append("[SetUp \"1\"]\n[FEN \"").append(startFen).append("\"]\n")
		sb.append('\n')
		val p = Position.of(startFen, variant)
		var num = p.full
		var white0 = p.side == P.WHITE
		for (i in sanEn.indices) {
			if (white0) sb.append(num).append(". ")
			else if (i == 0) sb.append(num).append("... ")
			sb.append(sanEn[i]).append(' ')
			if (!white0) num++
			white0 = !white0
		}
		sb.append(result)
		return sb.toString()
	}

	fun uciList(): String = moves.joinToString(" ") { M.uci(it) }

	companion object {
		fun fromUci(startFen: String, uci: String, variant: Int = V.STANDARD): GameRecord {
			val g = GameRecord(startFen, variant)
			for (u in uci.split(' ')) {
				if (u.isBlank()) continue
				val m = g.pos.parseUci(u)
				if (m == 0) break
				g.play(m)
			}
			return g
		}
	}
}
