package ru.cersat.chessics.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import ru.cersat.chessics.bots.MoveClass
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Значок типа хода у фигуры, стрелка лучшего хода и праздничная вспышка — как на обычной доске разбора.
// Доска даёт только геометрию: левый верхний угол клетки и её размер.
class MoveFx(private val view: View, private val corner: (Int) -> Pair<Float, Float>, private val cell: () -> Float) {
	var badgeSq = -1
	var badgeClass: MoveClass? = null
	private var fxT = -1f
	private var fxSq = -1
	private var fxClass: MoveClass? = null
	private var fxAnim: ValueAnimator? = null
	private val particles = ArrayList<FloatArray>()
	private val particleColors = ArrayList<Int>()
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }

	fun clear() { badgeSq = -1; badgeClass = null; fxAnim?.cancel(); fxT = -1f }

	fun set(sq: Int, cls: MoveClass?) { badgeSq = if (cls == null) -1 else sq; badgeClass = cls }

	// подкраска клетки с ходом — рисовать до фигур
	fun drawUnder(c: Canvas) {
		val cls = badgeClass ?: return
		if (badgeSq < 0) return
		val s = cell(); val (x, y) = corner(badgeSq)
		paint.color = (cls.color and 0x00FFFFFF) or 0x66000000
		c.drawRect(x, y, x + s, y + s, paint)
	}

	// стрелка, вспышка и значок — после фигур
	fun drawOver(c: Canvas, hintFrom: Int, hintTo: Int) {
		if (hintFrom >= 0 && hintTo >= 0) drawArrow(c, hintFrom, hintTo, 0xCC2E9BFF.toInt())
		if (fxT >= 0f) drawFx(c)
		val bc = badgeClass
		if (badgeSq >= 0 && bc != null) drawBadge(c, badgeSq, bc, if (fxT in 0f..1f && fxSq == badgeSq) badgeScale(fxT) else 1f)
	}

	private fun badgeScale(t: Float): Float = when {
		t < 0.18f -> t / 0.18f * 1.5f
		t < 0.35f -> 1.5f - (t - 0.18f) / 0.17f * 0.6f
		t < 0.5f -> 0.9f + (t - 0.35f) / 0.15f * 0.1f
		else -> 1f
	}

	private fun drawBadge(c: Canvas, sq: Int, cls: MoveClass, scale: Float) {
		if (scale <= 0f) return
		val s = cell(); val (x, y) = corner(sq)
		val r = s * 0.24f * scale
		val cx = x + s - s * 0.12f
		val cy = y + s * 0.12f
		paint.color = 0x55000000
		c.drawCircle(cx + s * 0.02f, cy + s * 0.03f, r, paint)
		paint.color = cls.color
		c.drawCircle(cx, cy, r, paint)
		paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.035f * scale; paint.color = 0xFFFFFFFF.toInt()
		c.drawCircle(cx, cy, r, paint)
		paint.style = Paint.Style.FILL
		text.color = 0xFFFFFFFF.toInt()
		text.textSize = r * (if (cls.symbol.length > 1) 0.95f else 1.15f)
		val tw = text.measureText(cls.symbol)
		c.drawText(cls.symbol, cx - tw / 2, cy + text.textSize * 0.36f, text)
	}

	private fun drawArrow(c: Canvas, from: Int, to: Int, color: Int) {
		val s = cell()
		val (fx, fy) = corner(from); val (tx, ty) = corner(to)
		val x1 = fx + s / 2; val y1 = fy + s / 2
		val x2 = tx + s / 2; val y2 = ty + s / 2
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

	// Бриллиант и отличный ход: вспышка клетки, кольца, искры и «выпрыгивающий» значок
	fun celebrate(sq: Int, cls: MoveClass) {
		badgeSq = sq; badgeClass = cls
		fxSq = sq; fxClass = cls
		particles.clear(); particleColors.clear()
		val n = if (cls == MoveClass.BRILLIANT) 34 else 14
		val rnd = java.util.Random()
		val palette = if (cls == MoveClass.BRILLIANT) intArrayOf(0xFF1BC6C9.toInt(), 0xFF7FF5F2.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFE27A.toInt(), 0xFF39A0FF.toInt())
		else intArrayOf(0xFF4D8BF5.toInt(), 0xFFAACBFF.toInt(), 0xFFFFFFFF.toInt())
		for (i in 0 until n) {
			particles.add(floatArrayOf((rnd.nextFloat() * Math.PI * 2).toFloat(), 1.2f + rnd.nextFloat() * 2.4f, 0.06f + rnd.nextFloat() * 0.09f, 0f, rnd.nextFloat() * 360f))
			particleColors.add(palette[rnd.nextInt(palette.size)])
		}
		fxAnim?.cancel()
		fxAnim = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = if (cls == MoveClass.BRILLIANT) 1900 else 1100
			interpolator = android.view.animation.LinearInterpolator()
			addUpdateListener { fxT = it.animatedValue as Float; view.invalidate() }
			addListener(object : android.animation.AnimatorListenerAdapter() {
				override fun onAnimationEnd(a: android.animation.Animator) { fxT = -1f; view.invalidate() }
			})
			start()
		}
	}

	private fun drawFx(c: Canvas) {
		val cls = fxClass ?: return
		val s = cell(); val (x0, y0) = corner(fxSq)
		val t = fxT
		val cx = x0 + s / 2; val cy = y0 + s / 2
		val base = cls.color and 0x00FFFFFF
		val glow = ((1 - t) * (0.6f + 0.4f * sin(t * 18f))).coerceIn(0f, 1f)
		paint.color = base or ((glow * 200).toInt() shl 24)
		c.drawRect(x0, y0, x0 + s, y0 + s, paint)
		if (cls == MoveClass.BRILLIANT && t < 0.7f) {
			val a = (1 - t / 0.7f)
			paint.color = 0xFFFFFF or ((a * 120).toInt() shl 24)
			c.save(); c.rotate(t * 120f, cx, cy)
			for (i in 0 until 8) {
				c.rotate(45f, cx, cy)
				val path = Path()
				path.moveTo(cx, cy); path.lineTo(cx + s * 0.08f, cy - s * 2.2f * (0.4f + t)); path.lineTo(cx - s * 0.08f, cy - s * 2.2f * (0.4f + t)); path.close()
				c.drawPath(path, paint)
			}
			c.restore()
		}
		paint.style = Paint.Style.STROKE
		for (k in 0 until (if (cls == MoveClass.BRILLIANT) 3 else 1)) {
			val tt = t - k * 0.12f
			if (tt !in 0f..1f) continue
			paint.strokeWidth = s * 0.08f * (1 - tt)
			paint.color = base or (((1 - tt) * 230).toInt() shl 24)
			c.drawCircle(cx, cy, s * (0.45f + tt * 2.6f), paint)
		}
		paint.style = Paint.Style.FILL
		val star = Path()
		for ((pi, pt) in particles.withIndex()) {
			val ang = pt[0]; val sp = pt[1]
			val d = s * sp * (1 - (1 - t) * (1 - t))
			val x = cx + cos(ang) * d
			val y = cy + sin(ang) * d + s * 0.9f * t * t
			val r = s * pt[2] * (1 - t * 0.7f)
			paint.color = (particleColors[pi] and 0x00FFFFFF) or (((1 - t) * 255).toInt().coerceIn(0, 255) shl 24)
			star.reset()
			for (i in 0 until 8) {
				val a = Math.toRadians((pt[4] + t * 400 + i * 45).toDouble())
				val rr = if (i % 2 == 0) r * 2.2f else r * 0.6f
				val px = x + cos(a).toFloat() * rr; val py = y + sin(a).toFloat() * rr
				if (i == 0) star.moveTo(px, py) else star.lineTo(px, py)
			}
			star.close()
			c.drawPath(star, paint)
		}
	}
}
