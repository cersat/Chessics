package ru.cersat.chessics.data

import android.content.Context

object Shop {
	const val FREEZE_PRICE = 50

	enum class Cat(val title: String) { THEME("Темы оформления"), BOARD("Доски"), PIECES("Фигуры"), SOUND("Звуки ходов"), WIDGET("Скины виджетов") }

	open class Item(val id: String, val cat: Cat, val name: String, val price: Int)

	// Тема оформления (всё тёмное)
	class Theme(id: String, name: String, price: Int, val bg: Int, val card: Int, val card2: Int, val accent: Int, val text: Int, val sub: Int, val light: Boolean = false) :
		Item(id, Cat.THEME, name, price)

	class Board(id: String, name: String, price: Int, val light: Int, val dark: Int, val last: Int, val select: Int) :
		Item(id, Cat.BOARD, name, price)

	class Pieces(id: String, name: String, price: Int, val set: String, val credit: String) : Item(id, Cat.PIECES, name, price)

	// style: 0 — без звука, 1 — классика, 2 — дерево, 3 — 8 бит, 4 — стекло, 5 — мягкие, 6 — космос
	class Sound(id: String, name: String, price: Int, val style: Int, val desc: String) : Item(id, Cat.SOUND, name, price)

	class Widget(id: String, name: String, price: Int, val top: Int, val bottom: Int, val text: Int, val accent: Int, val style: Int) :
		Item(id, Cat.WIDGET, name, price)

	private fun c(hex: Long) = hex.toInt()

	val THEMES = listOf(
		Theme("th_graphite", "Графит", 0, c(0xFF15171C), c(0xFF20232B), c(0xFF2A2E38), c(0xFFF2A93B), c(0xFFF1F2F5), c(0xFF9AA0AD)),
		Theme("th_emerald", "Изумруд", 60, c(0xFF0E1714), c(0xFF16231E), c(0xFF1F3029), c(0xFF34D399), c(0xFFEAF7F1), c(0xFF8FB5A6)),
		Theme("th_ocean", "Океан", 60, c(0xFF0C1320), c(0xFF142033), c(0xFF1C2B44), c(0xFF4EA8FF), c(0xFFEAF2FF), c(0xFF8EA3C2)),
		Theme("th_sunset", "Закат", 80, c(0xFF1A1012), c(0xFF26171B), c(0xFF341F25), c(0xFFFF7A59), c(0xFFFFEDE8), c(0xFFC09A93)),
		Theme("th_amethyst", "Аметист", 80, c(0xFF130F1C), c(0xFF1D172B), c(0xFF29203D), c(0xFFB77CFF), c(0xFFF3EDFF), c(0xFFA698C2)),
		Theme("th_oled", "Полночь (OLED)", 100, c(0xFF000000), c(0xFF0E0E10), c(0xFF19191C), c(0xFFFFFFFF), c(0xFFFFFFFF), c(0xFF8A8A92)),
		Theme("th_ruby", "Рубин", 120, c(0xFF170A0D), c(0xFF241015), c(0xFF33171E), c(0xFFFF3D6E), c(0xFFFFE9EE), c(0xFFC48E9A)),
		Theme("th_day", "День (светлая)", 60, c(0xFFF3F4F7), c(0xFFFFFFFF), c(0xFFE6E8EE), c(0xFFE08A12), c(0xFF17191F), c(0xFF6B7280), true),
		Theme("th_paper", "Бумага (светлая)", 80, c(0xFFF6F0E4), c(0xFFFFFBF3), c(0xFFEDE4D2), c(0xFF8B5E34), c(0xFF2B2118), c(0xFF7D6F5E), true),
		Theme("th_mint", "Мята (светлая)", 80, c(0xFFEEF7F3), c(0xFFFFFFFF), c(0xFFDCEFE6), c(0xFF0E9F6E), c(0xFF10231B), c(0xFF5C7A6D), true),
		Theme("th_sky", "Небо (светлая)", 100, c(0xFFEEF4FC), c(0xFFFFFFFF), c(0xFFDDE8F7), c(0xFF2F6FDB), c(0xFF111C2E), c(0xFF5D6F8A), true)
	)

	val BOARDS = listOf(
		Board("bd_wood", "Дерево", 0, c(0xFFF0D9B5), c(0xFFB58863), c(0x9ACDD26A), c(0xA0829769)),
		Board("bd_green", "Турнир", 40, c(0xFFEEEED2), c(0xFF769656), c(0x99F6F669), c(0xA0BACA2B)),
		Board("bd_blue", "Лёд", 60, c(0xFFDEE3E6), c(0xFF8CA2AD), c(0x9A9BC7E8), c(0xA06B9AC4)),
		Board("bd_marble", "Мрамор", 80, c(0xFFD9D9D9), c(0xFF8E8E8E), c(0x99E8C547), c(0xA0B0A040)),
		Board("bd_sand", "Песок", 80, c(0xFFF4E3C1), c(0xFFD2A86A), c(0x99F2CC5A), c(0xA0C49A3C)),
		Board("bd_night", "Ночь", 100, c(0xFF5F6B85), c(0xFF2E364A), c(0x995AA9E6), c(0xA04A7FB0)),
		Board("bd_neon", "Неон", 120, c(0xFF3B2A5C), c(0xFF1B1430), c(0x9900E5FF), c(0xA0FF2BD6)),
		Board("bd_lava", "Лава", 150, c(0xFF6E3B32), c(0xFF2A1311), c(0x99FF8A00), c(0xA0FF4500))
	)

	val PIECES = listOf(
		Pieces("pc_cburnett", "Классика", 0, "cburnett", "cburnett — Colin M.L. Burnett, GPLv2+"),
		Pieces("pc_merida", "Мерида", 100, "merida", "merida — Armando Hernandez Marroquin, GPLv2+"),
		Pieces("pc_alpha", "Альфа", 80, "alpha", "alpha — Eric Bentzen, свободно для некоммерческого использования"),
		Pieces("pc_chessnut", "Чесснат", 80, "chessnut", "chessnut — Alexis Luengas, Apache 2.0"),
		Pieces("pc_companion", "Компаньон", 80, "companion", "companion — David L. Brown, freeware"),
		Pieces("pc_leipzig", "Лейпциг", 90, "leipzig", "leipzig — Armando Hernandez Marroquin, freeware"),
		Pieces("pc_maestro", "Маэстро", 150, "maestro", "maestro — sadsnake1, CC BY-NC-SA 4.0"),
		Pieces("pc_california", "Калифорния", 120, "california", "california — Jerry S., CC BY-NC-SA 4.0"),
		Pieces("pc_cardinal", "Кардинал", 120, "cardinal", "cardinal — sadsnake1, CC BY-NC-SA 4.0"),
		Pieces("pc_fresca", "Фреска", 120, "fresca", "fresca — sadsnake1, CC BY-NC-SA 4.0"),
		Pieces("pc_gioco", "Джоко", 120, "gioco", "gioco — sadsnake1, CC BY-NC-SA 4.0"),
		Pieces("pc_icpieces", "Айси", 120, "icpieces", "icpieces — sadsnake1, CC BY-NC-SA 4.0"),
		Pieces("pc_staunty", "Стаунти", 140, "staunty", "staunty — sadsnake1, CC BY-NC-SA 4.0"),
		Pieces("pc_tatiana", "Татьяна", 140, "tatiana", "tatiana — sadsnake1, CC BY-NC-SA 4.0"),
		Pieces("pc_dubrovny", "Дубровны", 140, "dubrovny", "dubrovny — sadsnake1, CC BY-NC-SA 4.0"),
		Pieces("pc_fantasy", "Фэнтези", 160, "fantasy", "fantasy — Maurizio Monge, MIT"),
		Pieces("pc_spatial", "Пространство", 160, "spatial", "spatial — Maurizio Monge, MIT"),
		Pieces("pc_celtic", "Кельты", 160, "celtic", "celtic — Maurizio Monge, MIT"),
		Pieces("pc_papercut", "Вырезка", 160, "papercut", "papercut — Nikolay Anzarov, CC BY 4.0"),
		Pieces("pc_pixel", "Пиксели", 180, "pixel", "pixel — therealqtpi, AGPLv3+"),
		Pieces("pc_shapes", "Фигурки-формы", 100, "shapes", "shapes — flugsio, CC BY-SA 4.0"),
		Pieces("pc_letter", "Буквы", 60, "letter", "letter — usolando, AGPLv3+"),
		Pieces("pc_kiwen", "Кивен-суви", 120, "kiwen_suwi", "kiwen-suwi — neverRare, CC BY 4.0"),
		Pieces("pc_horsey", "Лошадки", 200, "horsey", "horsey — cham, michael1241, CC BY-NC-SA 4.0"),
		Pieces("pc_anarcandy", "Анаркенди", 250, "anarcandy", "anarcandy — caderek, CC BY-NC-SA 4.0")
	)

	val SOUNDS = listOf(
		Sound("sn_off", "Без звука", 0, 0, "Тишина"),
		Sound("sn_classic", "Классика", 0, 1, "Щелчок фигуры о доску"),
		Sound("sn_wood", "Дерево", 40, 2, "Глухой тёплый стук"),
		Sound("sn_soft", "Мягкие", 50, 5, "Приглушённые, для ночной игры"),
		Sound("sn_8bit", "8 бит", 70, 3, "Как в старой приставке"),
		Sound("sn_glass", "Стекло", 90, 4, "Звонкие колокольчики"),
		Sound("sn_space", "Космос", 120, 6, "Свипы и бипы")
	)

	// style: 0 — пламя, 1 — снежинка, 2 — молния, 3 — корона, 4 — минимал, 5 — радуга
	val WIDGETS = listOf(
		Widget("wg_classic", "Классика", 0, c(0xFF262A33), c(0xFF15171C), c(0xFFFFFFFF), c(0xFFFF9F1C), 0),
		Widget("wg_fire", "Пламя", 60, c(0xFFFF6B1A), c(0xFF8E1B0C), c(0xFFFFF4E6), c(0xFFFFE066), 0),
		Widget("wg_ice", "Лёд", 60, c(0xFF7FD3FF), c(0xFF1D4E89), c(0xFFFFFFFF), c(0xFFE0F7FF), 1),
		Widget("wg_neon", "Неон", 90, c(0xFF2B0A3D), c(0xFF07020E), c(0xFF00F0FF), c(0xFFFF2BD6), 2),
		Widget("wg_gold", "Золото", 150, c(0xFFFFD86B), c(0xFF9C6B00), c(0xFF2B1D00), c(0xFFFFF7D6), 3),
		Widget("wg_minimal", "Минимал", 40, c(0xCC000000), c(0xCC000000), c(0xFFFFFFFF), c(0xFFBBBBBB), 4),
		Widget("wg_rainbow", "Радуга", 200, c(0xFFFF5F6D), c(0xFF6A5BFF), c(0xFFFFFFFF), c(0xFFFFFFFF), 5),
		Widget("wg_forest", "Лес", 70, c(0xFF2F7D4F), c(0xFF0E2A1B), c(0xFFE9FFF1), c(0xFFA6F4C5), 0)
	)

	fun items(cat: Cat): List<Item> = when (cat) {
		Cat.THEME -> THEMES
		Cat.BOARD -> BOARDS
		Cat.PIECES -> PIECES.filter { it.set in installedSets }
		Cat.SOUND -> SOUNDS
		Cat.WIDGET -> WIDGETS
	}

	// Наборы фигур, которые есть в этой сборке (в варианте для F-Droid — только со свободными лицензиями)
	private var installedSets: Set<String> = PIECES.map { it.set }.toSet()

	fun init(ctx: Context) {
		installedSets = PIECES.map { it.set }.filter {
			ctx.resources.getIdentifier("pc_${it}_wk", "drawable", ctx.packageName) != 0
		}.toSet()
	}

	fun byId(id: String): Item? = (THEMES + BOARDS + PIECES + SOUNDS + WIDGETS).firstOrNull { it.id == id }

	fun theme(ctx: Context) = byId(Store.get(ctx).selected(Cat.THEME)) as? Theme ?: THEMES[0]
	fun board(ctx: Context) = byId(Store.get(ctx).selected(Cat.BOARD)) as? Board ?: BOARDS[0]
	fun pieces(ctx: Context) = (byId(Store.get(ctx).selected(Cat.PIECES)) as? Pieces)?.takeIf { it.set in installedSets } ?: PIECES[0]
	fun sound(ctx: Context) = byId(Store.get(ctx).selected(Cat.SOUND)) as? Sound ?: SOUNDS[1]
	fun widget(ctx: Context) = byId(Store.get(ctx).selected(Cat.WIDGET)) as? Widget ?: WIDGETS[0]
}
