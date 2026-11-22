package ru.cersat.chessics.data

import java.util.Calendar

// Пасхалки: находятся сами по ходу игры, в профиле видно только найденные
object Eggs {
	class Egg(val id: String, val title: String, val text: String)

	const val REWARD = 2

	val ALL = listOf(
		Egg("bongcloud", "Бонгклауд", "Вывести короля вперёд вторым же ходом. Смело."),
		Egg("holyhell", "Holy hell!", "Взять на проходе. Google en passant."),
		Egg("underpromo", "Скромность", "Превратить пешку не в ферзя."),
		Egg("foolsmate", "Дурацкий мат", "Партия закончилась матом на втором ходу."),
		Egg("scholar", "Детский мат", "Мат ферзём на f7 (или f2) в самом начале партии."),
		Egg("meadow", "Сенная ловушка", "1.a4 e5 2.Ra3 — ладья сама идёт под слона."),
		Egg("kingwalk", "Король-путешественник", "Довести своего короля до последней горизонтали соперника."),
		Egg("randomloss", "Неисповедимы пути", "Проиграть боту, который ходит наугад."),
		Egg("bigstalemate", "Пат с лишним ферзём", "Запатовать соперника, имея перевес в ферзь и больше."),
		Egg("marathon", "Марафон", "Сыграть партию длиной 200 полуходов."),
		Egg("instantresign", "Без боя", "Сдаться, не сделав ни одного хода."),
		Egg("owl", "Сова", "Доиграть партию между тремя и пятью часами ночи."),
		Egg("knock", "Тук-тук", "Постучать по названию на главном экране семь раз."),
		Egg("answer", "Ответ на главный вопрос", "Иметь ровно 42 алмаза."),
		Egg("chessday", "День шахмат", "Зайти в приложение 20 июля — в Международный день шахмат."),
		Egg("newyear", "С Новым годом", "Зайти в приложение 31 декабря или 1 января."),
		Egg("statue", "Памятник", "Сделать в конструкторе фигуру, которая вообще не ходит, и начать с ней партию."),
	)

	fun found(st: Store, id: String) = st.flag("egg_$id", false)

	// возвращает текст для всплывашки, если пасхалка найдена впервые
	fun find(st: Store, id: String): String? {
		val e = ALL.firstOrNull { it.id == id } ?: return null
		if (found(st, id)) return null
		st.setFlag("egg_$id", true)
		st.addDiamonds(REWARD)
		return "🥚 Пасхалка «${e.title}»! +$REWARD 💎"
	}

	// пасхалки, которые зависят только от даты, алмазов и времени
	fun onHome(st: Store): List<String> {
		val out = ArrayList<String>()
		val c = Calendar.getInstance()
		val m = c.get(Calendar.MONTH); val d = c.get(Calendar.DAY_OF_MONTH)
		if (m == Calendar.JULY && d == 20) find(st, "chessday")?.let { out.add(it) }
		if ((m == Calendar.DECEMBER && d == 31) || (m == Calendar.JANUARY && d == 1)) find(st, "newyear")?.let { out.add(it) }
		if (st.diamonds == 42) find(st, "answer")?.let { out.add(it) }
		return out
	}

	fun night(): Boolean = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 3..4
}
