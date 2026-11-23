package ru.cersat.chessics.bots

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Один процесс Stockfish 17.1 (UCI) со своим потоком.
// Бинарник лежит в nativeLibraryDir, нейросети копируются из assets в filesDir при первом запуске.
class Engine(private val name: String, private val threads: Int, val fairy: Boolean = false) {
	private var proc: Process? = null
	private var reader: BufferedReader? = null
	private var writer: OutputStreamWriter? = null
	private val lock = Object()
	private val writeLock = Object()
	private var curElo = -1
	private var curMultiPv = 1
	private var cur960 = false
	private var curVariant = "chess"
	private var curPath = ""
	@Volatile var lastError: String? = null
		private set

	// Свой поток на каждый движок: анализ не ждёт ход бота и наоборот
	val exec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "sf-$name").apply { priority = Thread.NORM_PRIORITY - 1 } }

	class Line(val cp: Int, val mate: Int, val move: String, val pv: List<String>) {
		// оценка в сантипешках с точки зрения белых, мат — как ±(10000 − 10·n)
		val score: Int get() = if (mate != 0) (if (mate > 0) 10000 - mate * 10 else -10000 - mate * 10) else cp
	}

	private fun send(cmd: String) {
		synchronized(writeLock) {
			writer?.write(cmd + "\n")
			writer?.flush()
		}
	}

	private fun readUntil(prefix: String): String? {
		while (true) {
			val line = reader?.readLine() ?: return null
			if (line.startsWith(prefix)) return line
		}
	}

	fun start(ctx: Context): Boolean {
		synchronized(lock) {
			if (proc?.isAlive == true) return true
			try {
				val bin = (if (fairy) Nets.fairy(ctx) else Nets.binary(ctx)) ?: run { lastError = "Движок не найден (нужен телефон arm64)"; return false }
				val p = ProcessBuilder(bin.absolutePath).redirectErrorStream(true).start()
				proc = p
				reader = BufferedReader(InputStreamReader(p.inputStream))
				writer = OutputStreamWriter(p.outputStream)
				send("uci")
				readUntil("uciok") ?: throw IllegalStateException("нет uciok")
				send("setoption name Threads value $threads")
				send("setoption name Hash value 32")
				if (fairy) send("setoption name Use NNUE value false")
				else {
					val (big, small) = Nets.files(ctx)
					send("setoption name EvalFile value ${big.absolutePath}")
					send("setoption name EvalFileSmall value ${small.absolutePath}")
				}
				send("isready")
				readUntil("readyok") ?: throw IllegalStateException("нет readyok")
				curElo = -1; curMultiPv = 1; cur960 = false; curVariant = "chess"; curPath = ""
				lastError = null
				return true
			} catch (e: Exception) {
				lastError = e.message ?: e.toString()
				proc?.destroy(); proc = null
				return false
			}
		}
	}

	private fun setElo(elo0: Int) {
		// у Fairy UCI_Elo от 500 до 2850
		val elo = if (fairy && elo0 > 0) elo0.coerceIn(500, 2850) else elo0
		if (elo == curElo) return
		if (elo <= 0) send("setoption name UCI_LimitStrength value false")
		else {
			send("setoption name UCI_LimitStrength value true")
			send("setoption name UCI_Elo value $elo")
		}
		curElo = elo
	}

	// variant — номер из V: Chess960 через UCI_Chess960, Три шаха и Царь горы — только у Fairy
	private fun setVariant(variant: Int) {
		val c960 = variant == ru.cersat.chessics.chess.V.CHESS960
		if (fairy) {
			val v = when (variant) { ru.cersat.chessics.chess.V.THREE_CHECK -> "3check"; ru.cersat.chessics.chess.V.KOTH -> "kingofthehill"; else -> "chess" }
			if (v != curVariant) { send("setoption name UCI_Variant value $v"); curVariant = v }
		}
		if (c960 != cur960) { send("setoption name UCI_Chess960 value $c960"); cur960 = c960 }
	}

	// В «Трёх шахах» Fairy ждёт в FEN счётчик оставшихся шахов
	private fun engineFen(fen: String, variant: Int): String {
		if (!fairy || variant != ru.cersat.chessics.chess.V.THREE_CHECK) return fen
		val t = fen.trim().split(Regex("\\s+")).toMutableList()
		if (t.size == 6) t.add(4, "3+3")
		return t.joinToString(" ")
	}

	private fun setMultiPv(n: Int) {
		if (n == curMultiPv) return
		send("setoption name MultiPV value $n")
		curMultiPv = n
	}

	private fun positionCmd(startFen: String, moves: String, variant: Int) =
		"position fen ${engineFen(startFen, variant)}" + if (moves.isNotBlank()) " moves $moves" else ""

	private fun fail(e: Exception) { lastError = e.message ?: e.toString(); proc?.destroy(); proc = null }

	// Ход бота. elo <= 0 — полная сила.
	fun bestMove(ctx: Context, startFen: String, moves: String, elo: Int, movetime: Int, variant: Int = 0): String? {
		if (!start(ctx)) return null
		synchronized(lock) {
			return try {
				setElo(elo); setMultiPv(1); setVariant(variant)
				send(positionCmd(startFen, moves, variant))
				send("go movetime $movetime")
				readUntil("bestmove")?.split(" ")?.getOrNull(1)?.takeIf { it != "(none)" }
			} catch (e: Exception) { fail(e); null }
		}
	}

	// Анализ полной силой. go — «depth 3» или «movetime 300». Строки отсортированы от лучшей.
	fun analyse(ctx: Context, startFen: String, moves: String, whiteToMove: Boolean, go: String, multipv: Int = 1, variant: Int = 0): List<Line>? {
		if (!start(ctx)) return null
		synchronized(lock) {
			try {
				setElo(0); setMultiPv(multipv); setVariant(variant)
				send(positionCmd(startFen, moves, variant))
				send("go $go")
				val lines = arrayOfNulls<Line>(multipv)
				val sign = if (whiteToMove) 1 else -1
				while (true) {
					val line = reader?.readLine() ?: return null
					if (line.startsWith("info") && line.contains(" score ") && line.contains(" pv ")) {
						val t = line.split(" ")
						val si = t.indexOf("score")
						val mi = t.indexOf("multipv")
						val k = if (mi >= 0) (t[mi + 1].toIntOrNull() ?: 1) - 1 else 0
						if (k !in 0 until multipv || si < 0 || si + 2 >= t.size) continue
						var cp = 0; var mate = 0
						if (t[si + 1] == "cp") cp = t[si + 2].toInt() else if (t[si + 1] == "mate") mate = t[si + 2].toInt()
						val pi = t.indexOf("pv")
						val pv = t.subList(pi + 1, t.size)
						lines[k] = Line(cp * sign, mate * sign, pv.firstOrNull() ?: "", pv)
					}
					if (line.startsWith("bestmove")) {
						val best = line.split(" ").getOrNull(1) ?: ""
						val out = lines.filterNotNull().toMutableList()
						if (out.isEmpty() && best.isNotEmpty() && best != "(none)") out.add(Line(0, 0, best, listOf(best)))
						return out
					}
				}
			} catch (e: Exception) { fail(e); return null }
		}
		@Suppress("UNREACHABLE_CODE")
		return null
	}

	// ===== Варианты из конструктора (только Fairy) =====

	private fun loadCustom(ctx: Context, section: String, ini: String) {
		if (curVariant != section) {
			val f = File(ctx.cacheDir, "fairy_$section.ini")
			if (!f.exists() || f.readText() != ini) f.writeText(ini)
			if (curPath != f.absolutePath) { send("setoption name VariantPath value ${f.absolutePath}"); curPath = f.absolutePath }
			send("setoption name UCI_Variant value $section")
			curVariant = section
		}
		if (cur960) { send("setoption name UCI_Chess960 value false"); cur960 = false }
	}

	fun customBest(ctx: Context, section: String, ini: String, fen: String, elo: Int, movetime: Int): String? {
		if (!fairy || !start(ctx)) return null
		synchronized(lock) {
			return try {
				loadCustom(ctx, section, ini); setElo(elo); setMultiPv(1)
				send("position fen $fen")
				send("go movetime $movetime")
				readUntil("bestmove")?.split(" ")?.getOrNull(1)?.takeIf { it != "(none)" }
			} catch (e: Exception) { fail(e); null }
		}
	}

	// анализ позиции варианта полной силой: лучшие линии с точки зрения белых (мат — ±(10000 − 10·n)), от лучшей
	fun customLines(ctx: Context, section: String, ini: String, fen: String, whiteToMove: Boolean, movetime: Int, multipv: Int = 1): List<Line>? {
		if (!fairy || !start(ctx)) return null
		synchronized(lock) {
			try {
				loadCustom(ctx, section, ini); setElo(0); setMultiPv(multipv)
				send("position fen $fen")
				send("go movetime $movetime")
				val sign = if (whiteToMove) 1 else -1
				val lines = arrayOfNulls<Line>(multipv)
				while (true) {
					val line = reader?.readLine() ?: return null
					if (line.startsWith("info") && line.contains(" score ") && line.contains(" pv ")) {
						val t = line.split(" ")
						val si = t.indexOf("score")
						val mi = t.indexOf("multipv")
						val k = if (mi >= 0) (t[mi + 1].toIntOrNull() ?: 1) - 1 else 0
						if (k !in 0 until multipv || si < 0 || si + 2 >= t.size) continue
						var cp = 0; var mate = 0
						if (t[si + 1] == "cp") cp = t[si + 2].toIntOrNull() ?: 0 else if (t[si + 1] == "mate") mate = t[si + 2].toIntOrNull() ?: 0
						val pv = t.subList(t.indexOf("pv") + 1, t.size)
						lines[k] = Line(cp * sign, mate * sign, pv.firstOrNull() ?: "", pv)
					}
					if (line.startsWith("bestmove")) {
						val best = line.split(" ").getOrNull(1)?.takeIf { it != "(none)" } ?: ""
						val out = lines.filterNotNull().toMutableList()
						if (out.isEmpty() && best.isNotEmpty()) out.add(Line(0, 0, best, listOf(best)))
						return out
					}
				}
			} catch (e: Exception) { fail(e); return null }
		}
		@Suppress("UNREACHABLE_CODE")
		return null
	}

	fun customPerft(ctx: Context, section: String, ini: String, fen: String, depth: Int): Long? {
		if (!fairy || !start(ctx)) return null
		synchronized(lock) {
			return try {
				loadCustom(ctx, section, ini)
				send("position fen $fen")
				send("go perft $depth")
				readUntil("Nodes searched")?.substringAfter(":")?.trim()?.toLongOrNull()
			} catch (e: Exception) { fail(e); null }
		}
	}

	// Прервать текущий расчёт (ответ bestmove всё равно придёт и будет проигнорирован вызывающим)
	fun stop() {
		if (proc?.isAlive != true) return
		try { send("stop") } catch (_: Exception) {}
	}

	fun newGame() {
		if (proc?.isAlive != true) return
		try { send("ucinewgame") } catch (_: Exception) {}
	}
}

object Nets {
	private const val BIG = "nn-1c0000000000.nnue"
	private const val SMALL = "nn-37f18f62d772.nnue"
	private val lock = Object()

	private fun hasDotProd(): Boolean = try {
		File("/proc/cpuinfo").readText().contains("asimddp")
	} catch (e: Exception) { false }

	fun fairy(ctx: Context): File? = File(ctx.applicationInfo.nativeLibraryDir, "libfairy.so").takeIf { it.exists() }

	fun binary(ctx: Context): File? {
		val f = File(ctx.applicationInfo.nativeLibraryDir, if (hasDotProd()) "libstockfish_dp.so" else "libstockfish.so")
		return if (f.exists()) f else null
	}

	fun files(ctx: Context): Pair<File, File> = synchronized(lock) { Pair(copy(ctx, BIG), copy(ctx, SMALL)) }

	private fun copy(ctx: Context, name: String): File {
		val dir = File(ctx.filesDir, "nets").apply { mkdirs() }
		val f = File(dir, name)
		val size = ctx.assets.openFd(name).use { it.length }
		if (!f.exists() || f.length() != size) {
			val tmp = File(dir, "$name.tmp")
			ctx.assets.open(name).use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
			tmp.renameTo(f)
		}
		return f
	}
}

// Stockfish 17.1 — обычные шахматы и Chess960 от 1350 Эло до полной силы.
// Fairy-Stockfish 14 — низкие уровни (500–1150 Эло) и варианты, которых Stockfish не знает.
// У каждого — три процесса: ходы ботов и подсказки, полоса оценки, разбор партий. Fairy запускается только при надобности.
object Stockfish {
	private val cores = Runtime.getRuntime().availableProcessors()
	private val sfPlay = Engine("play", maxOf(1, minOf(4, cores / 2)))
	private val sfBar = Engine("bar", 1)
	private val sfAnalysis = Engine("analysis", maxOf(1, minOf(2, cores / 4)))
	private val fPlay = Engine("fplay", maxOf(1, minOf(4, cores / 2)), true)
	private val fBar = Engine("fbar", 1, true)
	private val fAnalysis = Engine("fanalysis", maxOf(1, minOf(2, cores / 4)), true)

	const val SF_MIN_ELO = 1320

	fun fairyVariant(variant: Int) = variant == ru.cersat.chessics.chess.V.THREE_CHECK || variant == ru.cersat.chessics.chess.V.KOTH

	// для хода бота: Fairy — если вариант его или Эло ниже минимума Stockfish
	fun play(variant: Int, elo: Int = 0): Engine = if (fairyVariant(variant) || (elo in 1 until SF_MIN_ELO)) fPlay else sfPlay
	fun bar(variant: Int): Engine = if (fairyVariant(variant)) fBar else sfBar
	fun analysis(variant: Int): Engine = if (fairyVariant(variant)) fAnalysis else sfAnalysis

	// обычные шахматы — Stockfish
	val play get() = sfPlay
	val analysis get() = sfAnalysis

	// Fairy для вариантов из конструктора: ходы и разбор партий
	val fairyCustom get() = fPlay
	val fairyReview get() = fAnalysis

	fun stopPlay() { sfPlay.stop(); fPlay.stop() }
	fun newGame() { sfPlay.newGame(); fPlay.newGame() }
}

// Описание игрока: человек или бот
data class Player(val kind: Kind, val level: Int = 0) {
	enum class Kind { HUMAN, RANDOM, ALGO, MATERIAL, STOCKFISH, REMOTE, NEURAL }

	val isHuman get() = kind == Kind.HUMAN

	val title: String get() = when (kind) {
		Kind.HUMAN -> "Человек"
		Kind.RANDOM -> "Случайный"
		Kind.ALGO -> "Алгоритм · ${AlgoBot.LABELS[(level - 1).coerceIn(0, 4)]}"
		Kind.MATERIAL -> "Материал · глубина $level"
		Kind.STOCKFISH -> if (level <= 0) "Stockfish · полная сила" else if (level < Stockfish.SF_MIN_ELO) "Stockfish $level (Fairy)" else "Stockfish $level"
		Kind.REMOTE -> "Соперник по сети"
		Kind.NEURAL -> "Нейросеть (через промпт)"
	}

	val shortName: String get() = when (kind) {
		Kind.HUMAN -> "Человек"
		Kind.RANDOM -> "Случайный"
		Kind.ALGO -> "Алгоритм $level"
		Kind.MATERIAL -> "Материал $level"
		Kind.STOCKFISH -> if (level <= 0) "Stockfish MAX" else "Stockfish $level"
		Kind.REMOTE -> "Соперник"
		Kind.NEURAL -> "Нейросеть"
	}

	// Примерный рейтинг. Для Stockfish — его UCI_Elo; для наших ботов — оценка «на глаз».
	val elo: Int get() = when (kind) {
		Kind.HUMAN -> 0
		Kind.RANDOM -> 200
		Kind.ALGO -> intArrayOf(400, 600, 800, 950, 1100)[(level - 1).coerceIn(0, 4)]
		Kind.MATERIAL -> intArrayOf(500, 700, 900, 1050, 1200, 1300, 1400, 1450)[(level - 1).coerceIn(0, 7)]
		Kind.STOCKFISH -> if (level <= 0) 2900 else level
		Kind.REMOTE -> 0
		Kind.NEURAL -> 0
	}

	fun encode() = "${kind.name}:$level"

	companion object {
		val HUMAN = Player(Kind.HUMAN)
		val SF_LEVELS = intArrayOf(500, 800, 1000, 1150, 1350, 1500, 1700, 1900, 2100, 2400, 2700, 3000, 0)
		fun decode(s: String): Player = try {
			val (k, l) = s.split(":")
			Player(Kind.valueOf(k), l.toInt())
		} catch (e: Exception) { HUMAN }
	}
}
