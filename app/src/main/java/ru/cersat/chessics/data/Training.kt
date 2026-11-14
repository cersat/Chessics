package ru.cersat.chessics.data

import android.content.Context
import ru.cersat.chessics.chess.Position

// Дебюты: открытая база lichess chess-openings (CC0). Ключ позиции — расстановка, очередь хода и рокировки.
object Openings {
	class Opening(val eco: String, val name: String, val moves: List<String>, val key: String) {
		val family get() = name.substringBefore(":").trim()
		val variation get() = name.substringAfter(":", "").trim()
	}

	private var list: List<Opening>? = null
	private var byKey: Map<String, Opening>? = null

	fun all(ctx: Context): List<Opening> {
		list?.let { return it }
		val out = ArrayList<Opening>()
		ctx.assets.open("openings.tsv").bufferedReader().forEachLine { line ->
			val t = line.split('\t')
			if (t.size >= 4) out.add(Opening(t[0], t[1], t[2].split(' ').filter { it.isNotBlank() }, t[3]))
		}
		list = out
		// при совпадении ключей берём более длинную (точную) линию
		byKey = out.groupBy { it.key }.mapValues { (_, v) -> v.maxBy { it.moves.size } }
		return out
	}

	fun key(p: Position) = p.fen().split(' ').take(3).joinToString(" ")

	// Последний известный дебют вдоль партии
	fun detect(ctx: Context, startFen: String, moves: List<Int>): Opening? {
		if (startFen != Position.START) return null
		all(ctx)
		val map = byKey ?: return null
		val p = Position.start()
		var found: Opening? = null
		for (m in moves.take(40)) {
			p.make(m)
			map[key(p)]?.let { found = it }
		}
		return found
	}

	fun families(ctx: Context): List<Pair<String, List<Opening>>> =
		all(ctx).groupBy { it.family }.toList().sortedBy { it.first }
}

// Тренажёр эндшпиля: позиции проверены Stockfish (выигрыш — мат найден; ничья — оценка около нуля)
object Endgames {
	class Endgame(val id: String, val title: String, val desc: String, val fen: String, val win: Boolean, val reward: Int)

	val ALL = listOf(
		Endgame("eg_qk", "Ферзь против короля", "Поставь мат ферзём и королём. Прижми короля к краю, не запатуй.", "8/8/8/4k3/8/8/8/4K2Q w - - 0 1", true, 2),
		Endgame("eg_rk", "Ладья против короля", "Мат ладьёй: оттесняй короля линией ладьи, подводи своего короля.", "8/8/8/4k3/8/8/8/R3K3 w - - 0 1", true, 2),
		Endgame("eg_kp", "Пешка: король впереди", "Король перед пешкой на 6-й — выигрыш при любом ходе. Проведи пешку в ферзи и заматуй.", "4k3/8/4K3/4P3/8/8/8/8 w - - 0 1", true, 2),
		Endgame("eg_opp", "Оппозиция (защита)", "Ты за чёрных. Держи оппозицию — и белые не проведут пешку. Продержись 40 ходов или добейся ничьей.", "4k3/8/8/4P3/4K3/8/8/8 b - - 0 1", false, 3),
		Endgame("eg_bb", "Два слона", "Мат двумя слонами: слоны отрезают диагонали, король помогает.", "8/8/8/4k3/8/8/8/2B1KB2 w - - 0 1", true, 4),
		Endgame("eg_lucena", "Позиция Лусены", "Построй «мост»: ладья на 4-ю горизонталь — и пешка проходит.", "1K6/1P1k4/8/8/8/8/r7/2R5 w - - 0 1", true, 5),
		Endgame("eg_philidor", "Позиция Филидора (защита)", "Ты за чёрных. Держи ладью на 6-й горизонтали, после хода пешки — шахуй сзади.", "4k3/8/r7/4PK2/8/8/8/7R b - - 0 1", false, 5),
		Endgame("eg_vancura", "Позиция Ваншуры (защита)", "Ты за чёрных. Ладья атакует пешку сбоку и не даёт королю подойти.", "8/8/8/R7/P5k1/8/6r1/2K5 b - - 0 1", false, 6),
		Endgame("eg_qp", "Ферзь против пешки на 2-й", "Чёрная пешка вот-вот станет ферзём. Шахами подведи своего короля и забери её.", "8/8/8/8/8/1Q6/3pk3/7K w - - 0 1", true, 5),
		Endgame("eg_qr", "Ферзь против ладьи", "Ферзь сильнее ладьи, но выигрыш требует точности: разлучи ладью с королём.", "8/8/8/8/3k4/8/6r1/1K5Q w - - 0 1", true, 7),
		Endgame("eg_bn", "Слон и конь", "Самый трудный базовый мат: гони короля в угол цвета своего слона. Успей за 50 ходов.", "8/8/8/4k3/8/8/8/1N2KB2 w - - 0 1", true, 12)
	)

	fun byId(id: String) = ALL.firstOrNull { it.id == id }
}
