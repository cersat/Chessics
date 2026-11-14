package ru.cersat.chessics.data

// Цели на день и неделю: по три штуки, выбор зависит от даты (у всех одинаковый в один день)
object Goals {
	class Goal(val key: String, val title: String, val target: Int, val reward: Int, val max: Boolean = false)

	private val DAILY = listOf(
		Goal("game", "Сыграй 2 партии", 2, 8),
		Goal("win", "Победи бота", 1, 10),
		Goal("puzzle", "Реши 5 задач", 5, 10),
		Goal("puzzleClean", "Реши 3 задачи с первой попытки", 3, 12),
		Goal("storm", "Набери 8 очков в штурме", 8, 12, true),
		Goal("endgame", "Пройди позицию эндшпиля", 1, 12),
		Goal("opening", "Пройди дебютную линию", 1, 8),
		Goal("winSf", "Победи Stockfish любого уровня", 1, 15),
		Goal("online", "Сыграй с живым соперником", 1, 12)
	)

	private val WEEKLY = listOf(
		Goal("win", "Победи ботов 10 раз", 10, 50),
		Goal("puzzle", "Реши 40 задач", 40, 50),
		Goal("storm", "Набери 20 очков в штурме", 20, 60, true),
		Goal("endgame", "Пройди 3 позиции эндшпиля", 3, 50),
		Goal("opening", "Пройди 5 дебютных линий", 5, 40),
		Goal("game", "Сыграй 15 партий", 15, 50),
		Goal("winSf", "Победи Stockfish 3 раза", 3, 70),
		Goal("tactic", "Проведи 2 тактических приёма (вилка, шампур, ловушка ферзя)", 2, 40),
		Goal("brilliant", "Сыграй бриллиантовый ход", 1, 60)
	)

	private fun pick(pool: List<Goal>, seed: Long): List<Goal> = pool.shuffled(java.util.Random(seed * 2654435761L)).take(3)

	fun daily(day: Long) = pick(DAILY, day)
	fun weekly(week: Long) = pick(WEEKLY, week + 100000)
}
