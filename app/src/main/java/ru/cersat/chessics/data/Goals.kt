package ru.cersat.chessics.data

// Цели на день и неделю: по три штуки, выбор зависит от даты (у всех одинаковый в один день)
object Goals {
	class Goal(val key: String, val title: String, val target: Int, val reward: Int, val max: Boolean = false)

	private val DAILY = listOf(
		Goal("game", "Сыграй 2 партии", 2, 3),
		Goal("win", "Победи бота", 1, 4),
		Goal("puzzle", "Реши 5 задач", 5, 4),
		Goal("puzzleClean", "Реши 3 задачи с первой попытки", 3, 4),
		Goal("storm", "Набери 8 очков в штурме", 8, 4, true),
		Goal("endgame", "Пройди позицию эндшпиля", 1, 4),
		Goal("opening", "Пройди дебютную линию", 1, 3),
		Goal("winSf", "Победи Stockfish любого уровня", 1, 6),
		Goal("online", "Сыграй с живым соперником", 1, 4)
	)

	private val WEEKLY = listOf(
		Goal("win", "Победи ботов 10 раз", 10, 19),
		Goal("puzzle", "Реши 40 задач", 40, 19),
		Goal("storm", "Набери 20 очков в штурме", 20, 22, true),
		Goal("endgame", "Пройди 3 позиции эндшпиля", 3, 19),
		Goal("opening", "Пройди 5 дебютных линий", 5, 15),
		Goal("game", "Сыграй 15 партий", 15, 19),
		Goal("winSf", "Победи Stockfish 3 раза", 3, 26),
		Goal("tactic", "Проведи 2 тактических приёма (вилка, шампур, ловушка ферзя)", 2, 15),
		Goal("brilliant", "Сыграй бриллиантовый ход", 1, 22)
	)

	private fun pick(pool: List<Goal>, seed: Long): List<Goal> = pool.shuffled(java.util.Random(seed * 2654435761L)).take(3)

	fun daily(day: Long) = pick(DAILY, day)
	fun weekly(week: Long) = pick(WEEKLY, week + 100000)
}
