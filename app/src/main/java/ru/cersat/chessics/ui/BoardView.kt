package ru.cersat.chessics.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import ru.cersat.chessics.chess.M
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.data.Shop
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

object PieceArt {
	private val cache = HashMap<String, Bitmap>()
	private const val CODES = " pnbrqk"

	fun get(ctx: Context, set: String, piece: Int): Bitmap? {
		if (piece == 0) return null
		val key = "$set${piece}"
		cache[key]?.let { return it }
		val name = "pc_${set}_${if (P.color(piece) == P.WHITE) 'w' else 'b'}${CODES[P.type(piece)]}"
		val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
		if (id == 0) return null
		val bmp = BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false })
		cache[key] = bmp
		return bmp
	}

	// Отрисовка доски в Canvas — используется и в BoardView, и в виджете задачи дня
	fun drawStatic(ctx: Context, c: Canvas, pos: Position, size: Float, whiteBottom: Boolean, board: Shop.Board, set: String) {
		val s = size / 8
		val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
		val dst = RectF()
		for (sq in 0 until 64) {
			val f = P.file(sq); val r = P.rank(sq)
			val col = if (whiteBottom) f else 7 - f
			val row = if (whiteBottom) 7 - r else r
			p.color = if ((f + r) % 2 == 1) board.light else board.dark
			c.drawRect(col * s, row * s, (col + 1) * s, (row + 1) * s, p)
			val bmp = get(ctx, set, pos.sq[sq]) ?: continue
			dst.set(col * s, row * s, (col + 1) * s, (row + 1) * s)
			c.drawBitmap(bmp, null, dst, p)
		}
	}
}

class BoardView(ctx: Context) : View(ctx) {
	var pos: Position = Position.start()
		private set
	var whiteBottom = true
		set(v) { field = v; invalidate() }
	// «Лицом к лицу»: фигуры верхней стороны развёрнуты к сидящему напротив
	var faceToFace = false
		set(v) { field = v; invalidate() }
	var showCoords = true
	var showLegal = true
	var lastMove = 0
	var hintMove = 0
	var canMove: (Int) -> Boolean = { false } // может ли человек ходить за этот цвет
	var onMove: (Int) -> Unit = {}
	var markSquare = -1 // красная/зелёная подсветка (задачи)
	var markColor = 0

	private var board: Shop.Board = Shop.board(ctx)
	private var set: String = Shop.pieces(ctx).set
	private var selected = -1
	private var legal = IntArray(0)
	private var dragFrom = -1
	private var dragX = 0f
	private var dragY = 0f
	private var dragging = false
	private var downX = 0f
	private var downY = 0f

	// анимация
	private var animFrom = -1
	private var animTo = -1
	private var animPiece = 0
	private var animT = 1f
	private var animator: ValueAnimator? = null

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
	private val rect = RectF()

	fun reloadStyle() {
		board = Shop.board(context)
		set = Shop.pieces(context).set
		invalidate()
	}

	fun setPosition(p: Position, move: Int = 0, animate: Boolean = false) {
		val prev = pos
		pos = p
		lastMove = move
		selected = -1
		legal = IntArray(0)
		dragFrom = -1
		dragging = false
		hintMove = 0
		animator?.cancel()
		if (animate && move != 0) {
			animFrom = M.from(move); animTo = M.to(move)
			animPiece = p.sq[animTo]
			if (M.promo(move) != 0) animPiece = P.make(P.color(animPiece), P.PAWN)
			if (animPiece == 0) animPiece = prev.sq[animFrom]
			animT = 0f
			animator = ValueAnimator.ofFloat(0f, 1f).apply {
				duration = 170
				addUpdateListener { animT = it.animatedValue as Float; invalidate() }
				start()
			}
		} else animT = 1f
		invalidate()
	}

	override fun onMeasure(w: Int, h: Int) {
		val size = min(MeasureSpec.getSize(w), if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(h))
		setMeasuredDimension(size, size)
	}

	private val cell get() = width / 8f

	private fun colOf(sq: Int) = if (whiteBottom) P.file(sq) else 7 - P.file(sq)
	private fun rowOf(sq: Int) = if (whiteBottom) 7 - P.rank(sq) else P.rank(sq)
	private fun sqAt(x: Float, y: Float): Int {
		val col = (x / cell).toInt(); val row = (y / cell).toInt()
		if (col !in 0..7 || row !in 0..7) return -1
		val f = if (whiteBottom) col else 7 - col
		val r = if (whiteBottom) 7 - row else row
		return P.sq(f, r)
	}

	private fun lum(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

	override fun onDraw(c: Canvas) {
		val s = cell
		// клетки
		for (sq in 0 until 64) {
			val x = colOf(sq) * s; val y = rowOf(sq) * s
			val light = (P.file(sq) + P.rank(sq)) % 2 == 1
			paint.color = if (light) board.light else board.dark
			c.drawRect(x, y, x + s, y + s, paint)
			if (lastMove != 0 && (sq == M.from(lastMove) || sq == M.to(lastMove))) {
				paint.color = board.last; c.drawRect(x, y, x + s, y + s, paint)
			}
			if (sq == selected) { paint.color = board.select; c.drawRect(x, y, x + s, y + s, paint) }
			if (sq == markSquare) { paint.color = markColor; c.drawRect(x, y, x + s, y + s, paint) }
		}
		// шах
		if (pos.inCheck()) {
			val k = pos.king[pos.side]
			val cx = colOf(k) * s + s / 2; val cy = rowOf(k) * s + s / 2
			paint.shader = android.graphics.RadialGradient(cx, cy, s * 0.7f, intArrayOf(0xFFFF2020.toInt(), 0x00FF0000), null, android.graphics.Shader.TileMode.CLAMP)
			c.drawCircle(cx, cy, s * 0.7f, paint)
			paint.shader = null
		}
		// координаты: цвет противоположен клетке + тень-обводка
		if (showCoords) {
			text.textSize = s * 0.22f
			for (i in 0..7) {
				// цифры — левый край, буквы — нижний край
				val sqL = sqAt(1f, i * s + 1f)
				drawLabel(c, "${P.rank(sqL) + 1}", s * 0.06f, i * s + s * 0.26f, sqL)
				val sqB = sqAt(i * s + 1f, 7 * s + 1f)
				val ch = "${'a' + P.file(sqB)}"
				drawLabel(c, ch, (i + 1) * s - s * 0.06f - text.measureText(ch), 8 * s - s * 0.07f, sqB)
			}
		}
		// фигуры
		for (sq in 0 until 64) {
			val pc = pos.sq[sq]
			if (pc == 0) continue
			if (dragging && sq == dragFrom) continue
			if (animT < 1f && sq == animTo) continue
			drawPiece(c, pc, colOf(sq) * s, rowOf(sq) * s, s)
		}
		// легальные ходы
		if (showLegal && selected >= 0) {
			for (m in legal) {
				if (M.from(m) != selected) continue
				val t = M.to(m)
				val cx = colOf(t) * s + s / 2; val cy = rowOf(t) * s + s / 2
				paint.color = 0x55000000
				if (pos.sq[t] != 0 || M.isEp(m)) {
					paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.08f
					c.drawCircle(cx, cy, s * 0.44f, paint)
					paint.style = Paint.Style.FILL
				} else c.drawCircle(cx, cy, s * 0.15f, paint)
			}
		}
		// анимация
		if (animT < 1f && animPiece != 0) {
			val x = (colOf(animFrom) + (colOf(animTo) - colOf(animFrom)) * animT) * s
			val y = (rowOf(animFrom) + (rowOf(animTo) - rowOf(animFrom)) * animT) * s
			drawPiece(c, animPiece, x, y, s)
		}
		// стрелки
		if (hintMove != 0) drawArrow(c, M.from(hintMove), M.to(hintMove), 0xCC2E9BFF.toInt())
		// перетаскиваемая
		if (dragging && dragFrom >= 0) {
			val pc = pos.sq[dragFrom]
			drawPiece(c, pc, dragX - s * 0.6f, dragY - s * 0.9f, s * 1.2f)
		}
	}

	private fun drawLabel(c: Canvas, t: String, x: Float, y: Float, sq: Int) {
		val light = (P.file(sq) + P.rank(sq)) % 2 == 1
		val bg = if (light) board.light else board.dark
		val dark = lum(bg) > 140
		text.color = if (dark) 0xFF1A1A1A.toInt() else 0xFFF5F5F5.toInt()
		text.setShadowLayer(2.5f, 0f, 0f, if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
		c.drawText(t, x, y, text)
	}

	private fun drawPiece(c: Canvas, pc: Int, x: Float, y: Float, s: Float) {
		val bmp = PieceArt.get(context, set, pc) ?: return
		rect.set(x, y, x + s, y + s)
		val flip = faceToFace && P.color(pc) == (if (whiteBottom) P.BLACK else P.WHITE)
		if (flip) {
			c.save(); c.rotate(180f, x + s / 2, y + s / 2)
			c.drawBitmap(bmp, null, rect, paint)
			c.restore()
		} else c.drawBitmap(bmp, null, rect, paint)
	}

	private fun drawArrow(c: Canvas, from: Int, to: Int, color: Int) {
		val s = cell
		val x1 = colOf(from) * s + s / 2; val y1 = rowOf(from) * s + s / 2
		val x2 = colOf(to) * s + s / 2; val y2 = rowOf(to) * s + s / 2
		val ang = atan2(y2 - y1, x2 - x1)
		val len = hypot(x2 - x1, y2 - y1)
		val head = s * 0.42f
		val w = s * 0.17f
		val path = Path()
		c.save()
		c.translate(x1, y1)
		c.rotate(Math.toDegrees(ang.toDouble()).toFloat())
		path.moveTo(0f, -w / 2); path.lineTo(len - head, -w / 2); path.lineTo(len - head, -head / 1.6f)
		path.lineTo(len, 0f); path.lineTo(len - head, head / 1.6f); path.lineTo(len - head, w / 2); path.lineTo(0f, w / 2); path.close()
		paint.color = color
		c.drawPath(path, paint)
		c.restore()
	}

	private fun select(sq: Int) {
		selected = sq
		legal = if (sq >= 0) pos.legalMoves() else IntArray(0)
		invalidate()
	}

	private fun tryMove(from: Int, to: Int): Boolean {
		val cands = legal.filter { M.from(it) == from && M.to(it) == to }
		if (cands.isEmpty()) return false
		selected = -1
		if (cands.size > 1) {
			// превращение — выбор отдаёт хост через onMove (ход без promo, хост спросит фигуру)
			onMove(M.make(from, to, 0, 0) or PROMO_ASK)
		} else onMove(cands[0])
		return true
	}

	override fun onTouchEvent(e: MotionEvent): Boolean {
		val sq = sqAt(e.x, e.y)
		when (e.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				downX = e.x; downY = e.y
				val pc = if (sq >= 0) pos.sq[sq] else 0
				if (selected >= 0 && sq >= 0 && (pc == 0 || P.color(pc) != pos.side) && tryMove(selected, sq)) return true
				if (pc != 0 && P.color(pc) == pos.side && canMove(pos.side)) {
					select(sq); dragFrom = sq; dragX = e.x; dragY = e.y
				} else if (selected >= 0) select(-1)
				invalidate()
				return true
			}
			MotionEvent.ACTION_MOVE -> {
				if (dragFrom >= 0) {
					if (!dragging && hypot(e.x - downX, e.y - downY) > cell * 0.2f) dragging = true
					dragX = e.x; dragY = e.y
					invalidate()
				}
				return true
			}
			MotionEvent.ACTION_UP -> {
				if (dragging && dragFrom >= 0) {
					val from = dragFrom
					dragging = false; dragFrom = -1
					if (sq < 0 || sq == from || !tryMove(from, sq)) select(from)
				}
				dragFrom = -1
				invalidate()
				return true
			}
			MotionEvent.ACTION_CANCEL -> { dragging = false; dragFrom = -1; invalidate(); return true }
		}
		return super.onTouchEvent(e)
	}

	companion object {
		const val PROMO_ASK = 1 shl 30
	}
}

// Горизонтальная полоса преимущества: белая часть слева, оценка в сантипешках
class EvalBar(ctx: Context) : View(ctx) {
	var cp = 0
		set(v) { field = v; invalidate() }
	var label = ""
		set(v) { field = v; invalidate() }
	private val p = Paint(Paint.ANTI_ALIAS_FLAG)

	override fun onDraw(c: Canvas) {
		val w = width.toFloat(); val h = height.toFloat()
		val share = (0.5 + 0.5 * (2 / (1 + Math.exp(-0.004 * cp)) - 1)).toFloat()
		val r = h / 2
		p.color = 0xFF2B2B2B.toInt()
		c.drawRoundRect(0f, 0f, w, h, r, r, p)
		p.color = 0xFFEDEDED.toInt()
		c.save(); c.clipRect(0f, 0f, w * share, h)
		c.drawRoundRect(0f, 0f, w, h, r, r, p)
		c.restore()
		p.color = 0x66FF9F1C
		c.drawRect(w / 2 - 1, 0f, w / 2 + 1, h, p)
		if (label.isNotEmpty()) {
			p.textSize = h * 0.72f
			p.isFakeBoldText = true
			val white = share >= 0.5f
			val tw = p.measureText(label)
			// текст на своей половине, цвет противоположен фону + тень
			p.color = if (white) 0xFF1A1A1A.toInt() else 0xFFF5F5F5.toInt()
			p.setShadowLayer(2f, 0f, 0f, if (white) 0xAAFFFFFF.toInt() else 0xAA000000.toInt())
			val x = if (white) h * 0.4f else w - tw - h * 0.4f
			c.drawText(label, x, h * 0.76f, p)
			p.clearShadowLayer()
		}
	}
}

// Мини-график оценки по ходам (для анализа)
class EvalGraph(ctx: Context) : View(ctx) {
	var values: List<Int> = emptyList()
		set(v) { field = v; invalidate() }
	var cursor = -1
		set(v) { field = v; invalidate() }
	var onPick: (Int) -> Unit = {}
	private val p = Paint(Paint.ANTI_ALIAS_FLAG)

	override fun onDraw(c: Canvas) {
		val w = width.toFloat(); val h = height.toFloat()
		p.color = 0xFF2B2B2B.toInt(); c.drawRect(0f, 0f, w, h, p)
		if (values.size < 2) return
		val path = Path()
		path.moveTo(0f, h)
		val n = values.size
		for (i in 0 until n) {
			val share = (0.5 + 0.5 * (2 / (1 + Math.exp(-0.004 * values[i])) - 1)).toFloat()
			path.lineTo(i * w / (n - 1), h - share * h)
		}
		path.lineTo(w, h); path.close()
		p.color = 0xFFEDEDED.toInt(); c.drawPath(path, p)
		p.color = 0x66FF9F1C; c.drawRect(0f, h / 2 - 1, w, h / 2 + 1, p)
		if (cursor >= 0) {
			p.color = 0xFFFF9F1C.toInt()
			val x = cursor * w / (n - 1)
			c.drawRect(x - 2, 0f, x + 2, h, p)
		}
	}

	override fun onTouchEvent(e: MotionEvent): Boolean {
		if (values.size < 2) return false
		val i = ((e.x / width) * (values.size - 1) + 0.5f).toInt().coerceIn(0, values.size - 1)
		onPick(i)
		return true
	}
}
