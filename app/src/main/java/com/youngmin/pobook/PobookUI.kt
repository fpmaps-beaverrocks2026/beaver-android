package com.youngmin.pobook

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

internal object Pobook {
    val bg = 0xFF121212.toInt()
    val panel = 0xFF242424.toInt()
    val surface = 0xFF2B2B2B.toInt()
    val border = 0xFF393939.toInt()
    val text = 0xFFF9F9F9.toInt()
    val muted = 0xFFB6B6B6.toInt()
    val accent = 0xFFDDF53C.toInt()
    val danger = 0xFFFF767B.toInt()
    val success = 0xFF34C759.toInt()
    val secondary = 0xFFDBDBDB.toInt()
    val tabAccent = 0xFFF2FF3F.toInt()
    val card = 0xFF2A2A2A.toInt()
    val glassFill = 0x7D696969
    val glassHighlight = 0x4EFFFFFF
    val black = Color.BLACK
    private val fonts = HashMap<Int, Typeface?>()
    fun font(context: Context, bold: Boolean) = font(context,
        if (bold) R.font.pretendard_semibold else R.font.pretendard_regular)
    fun font(context: Context, id: Int) = fonts.getOrPut(id) { ResourcesCompat.getFont(context, id) }
}

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

internal fun Context.shape(color: Int, radius: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius).toFloat()
    if (stroke != null) setStroke(dp(1), stroke)
}

internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun Context.row() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}
internal fun View.pad(horizontal: Int, top: Int = horizontal, bottom: Int = top) =
    setPadding(context.dp(horizontal), context.dp(top), context.dp(horizontal), context.dp(bottom))
internal fun LinearLayout.gap(size: Int) {
    val value = context.dp(size)
    addView(View(context), if (orientation == LinearLayout.VERTICAL) LinearLayout.LayoutParams(1, value)
        else LinearLayout.LayoutParams(value, 1))
}
internal fun LinearLayout.fill(view: View) =
    addView(view, if (orientation == LinearLayout.VERTICAL) LinearLayout.LayoutParams(MATCH, 0, 1f)
        else LinearLayout.LayoutParams(0, WRAP, 1f))

internal fun Context.label(value: String, size: Int, color: Int = Pobook.text, bold: Boolean = false) = TextView(this).apply {
    text = value
    textSize = size.toFloat()
    setTextColor(color)
    typeface = Pobook.font(context, bold)
    includeFontPadding = false
    letterSpacing = -0.02f
    val iosLineHeight = when (size) {
        12 -> 16.8f
        14 -> 19.6f
        16 -> 22.4f
        18 -> 25f
        20 -> 28f
        24 -> 34f
        32 -> 45f
        else -> size * 1.38f
    }
    val desired = textSize / size * iosLineHeight
    val metrics = paint.fontMetrics
    setLineSpacing((desired - (metrics.descent - metrics.ascent)).coerceAtLeast(0f), 1f)
}

internal fun Context.scroll(content: View) = ScrollView(this).apply {
    isFillViewport = true
    isVerticalScrollBarEnabled = false
    addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
}

internal fun View.touch(color: Int = Color.TRANSPARENT, radius: Int = 12, stroke: Int? = null, action: () -> Unit) {
    background = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), context.shape(color, radius, stroke),
        context.shape(Color.WHITE, radius))
    isFocusable = true
    setOnClickListener { action() }
}

internal fun Context.button(value: String, primary: Boolean = true, enabled: Boolean = true, action: () -> Unit) =
    label(value, 18, if (!enabled) 0xFF777777.toInt() else if (primary) Pobook.surface else Pobook.text, true).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(52)
        pad(14, 10)
        if (enabled) touch(if (primary) Pobook.accent else 0xFF333333.toInt(), 26, action = action)
        else background = shape(0xFF333333.toInt(), 26)
        isEnabled = enabled
    }

internal fun Context.link(value: String, color: Int = Pobook.muted, action: () -> Unit) = label(value, 14, color, true).apply {
    gravity = Gravity.CENTER
    minimumHeight = dp(44)
    pad(10, 4)
    touch(action = action)
}

internal fun Context.chip(value: String, selected: Boolean, action: () -> Unit) =
    label(value, 14, if (selected) Pobook.bg else Pobook.text, true).apply {
        pad(14, 10)
        gravity = Gravity.CENTER
        minimumHeight = dp(42)
        isSelected = selected
        touch(if (selected) Pobook.accent else Pobook.surface, 22, action = action)
    }

internal fun Context.section(title: String, subtitle: String? = null) = column().apply {
    addView(label(title, 20, bold = true).apply { isAccessibilityHeading = true })
    if (subtitle != null) {
        gap(7)
        addView(label(subtitle, 14, Pobook.muted))
    }
}

internal fun Context.icon(name: String, accessible: String, color: Int = Pobook.text, background: Int = Pobook.surface,
                          action: () -> Unit): View = LineIcon(this, name, color).apply {
    contentDescription = accessible
    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    touch(background, 24, action = action)
}

internal fun Context.glass(radius: Int, tint: Int = Pobook.glassFill): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TL_BR, intArrayOf(
        Color.argb(Color.alpha(tint), minOf(Color.red(tint) + 28, 255),
            minOf(Color.green(tint) + 28, 255), minOf(Color.blue(tint) + 28, 255)),
        tint,
        tint,
    )
).apply {
    cornerRadius = dp(radius).toFloat()
    setStroke(dp(1), Pobook.glassHighlight)
}

internal fun View.glassTouch(radius: Int, tint: Int = Pobook.glassFill, action: () -> Unit) {
    background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), context.glass(radius, tint), context.shape(Color.WHITE, radius))
    setOnClickListener { action() }
    isFocusable = true
}

internal fun Context.glassSymbol(name: String, accessible: String, size: Int = 44,
                                 tint: Int = Pobook.glassFill, action: () -> Unit): View =
    LineIcon(this, name, Color.WHITE).apply {
        contentDescription = accessible
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        glassTouch(size / 2, tint, action)
    }

internal fun Context.divider(color: Int = 0x38FFFFFF): View = View(this).apply {
    setBackgroundColor(color)
    layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
}

internal class LineIcon(context: Context, private val name: String, private val color: Int, private val size: Int = 22) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.65f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = this@LineIcon.color
    }

    override fun onDraw(canvas: Canvas) {
        val scale = context.dp(size) / 24f
        canvas.save()
        canvas.translate((width - 24 * scale) / 2, (height - 24 * scale) / 2)
        canvas.scale(scale, scale)
        fun path(vararg data: Float) {
            val shape = Path()
            shape.moveTo(data[0], data[1])
            for (index in 2 until data.size step 2) shape.lineTo(data[index], data[index + 1])
            canvas.drawPath(shape, ink)
        }
        when (name) {
            "bookmark", "bookmark_line" -> {
                val bookmark = Path().apply {
                    moveTo(6.5f, 2.5f)
                    quadTo(4.5f, 2.5f, 4.5f, 4.5f)
                    lineTo(4.5f, 21f)
                    quadTo(4.5f, 22.8f, 6.2f, 21.6f)
                    lineTo(12f, 17f)
                    lineTo(17.8f, 21.6f)
                    quadTo(19.5f, 22.8f, 19.5f, 21f)
                    lineTo(19.5f, 4.5f)
                    quadTo(19.5f, 2.5f, 17.5f, 2.5f)
                    close()
                }
                if (name == "bookmark") ink.style = Paint.Style.FILL
                canvas.drawPath(bookmark, ink)
                ink.style = Paint.Style.STROKE
            }
            "basket" -> {
                path(5f, 8.5f, 8.6f, 2.3f)
                path(19f, 8.5f, 15.4f, 2.3f)
                ink.style = Paint.Style.FILL
                canvas.drawRoundRect(0.5f, 8f, 23.5f, 11.6f, 1.8f, 1.8f, ink)
                val bowl = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    moveTo(2.5f, 13f); lineTo(21.5f, 13f); lineTo(19.5f, 20.1f)
                    quadTo(18.8f, 22f, 17.3f, 22f); lineTo(6.7f, 22f)
                    quadTo(5.2f, 22f, 4.5f, 20.1f); close()
                    for (x in listOf(7f, 10.3f, 13.7f, 17f)) addRoundRect(x-0.55f, 14.3f, x+0.55f, 20.4f, 0.5f, 0.5f, Path.Direction.CW)
                }
                canvas.drawPath(bowl, ink)
                ink.style = Paint.Style.STROKE
            }
            "person_filled" -> {
                ink.style = Paint.Style.FILL
                canvas.drawOval(8f, 2f, 16f, 10.7f, ink)
                canvas.drawPath(Path().apply {
                    moveTo(3.5f, 21f); cubicTo(3.5f, 13f, 20.5f, 13f, 20.5f, 21f)
                    quadTo(20.5f, 22f, 19.2f, 22f); lineTo(4.8f, 22f); quadTo(3.5f, 22f, 3.5f, 21f)
                }, ink)
                ink.style = Paint.Style.STROKE
            }
            "home_filled" -> {
                path(1.8f, 11f, 12f, 2f, 22.2f, 11f)
                ink.style = Paint.Style.FILL
                canvas.drawPath(Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    moveTo(4f, 10.7f); lineTo(12f, 4f); lineTo(20f, 10.7f)
                    lineTo(20f, 20.65f); quadTo(20f, 22f, 18.7f, 22f); lineTo(5.3f, 22f)
                    quadTo(4f, 22f, 4f, 20.65f); close()
                    addRoundRect(9.2f, 14.5f, 14.8f, 22f, 0.65f, 0.65f, Path.Direction.CW)
                }, ink)
                canvas.drawRoundRect(17f, 3.3f, 19.3f, 8f, 0.5f, 0.5f, ink)
                ink.style = Paint.Style.STROKE
            }
            "book_filled" -> {
                ink.style = Paint.Style.FILL
                val sheet = Path().apply {
                    moveTo(1.1f, 4.1f); quadTo(6.3f, 0.3f, 11.15f, 4.1f); lineTo(11.15f, 20.2f)
                    quadTo(6.3f, 16.5f, 1.1f, 20.2f); close()
                }
                canvas.drawPath(sheet, ink)
                canvas.save(); canvas.translate(24f, 0f); canvas.scale(-1f, 1f); canvas.drawPath(sheet, ink); canvas.restore()
                ink.style = Paint.Style.STROKE
            }
            "trash" -> {
                path(3.5f, 6.5f, 20.5f, 6.5f)
                path(9f, 6f, 9f, 3.5f, 15f, 3.5f, 15f, 6f)
                path(5.5f, 7f, 6.5f, 21f, 17.5f, 21f, 18.5f, 7f)
                path(10f, 10f, 10f, 18f)
                path(14f, 10f, 14f, 18f)
            }
            "check" -> path(5f, 12f, 10f, 17f, 19f, 7f)
            "back" -> path(15f, 5f, 8f, 12f, 15f, 19f)
            "next" -> path(9f, 5f, 16f, 12f, 9f, 19f)
            "close" -> {
                path(6f, 6f, 18f, 18f); path(18f, 6f, 6f, 18f)
            }
            "plus" -> {
                path(5f, 12f, 19f, 12f); path(12f, 5f, 12f, 19f)
            }
            "home" -> {
                path(2f, 11f, 12f, 3f, 22f, 11f)
                path(5f, 10f, 5f, 21f, 10f, 21f, 10f, 15f, 14f, 15f, 14f, 21f, 19f, 21f, 19f, 10f)
            }
            "book" -> {
                path(12f, 6f, 8f, 4f, 3f, 5f, 3f, 19f, 8f, 18f, 12f, 20f, 16f, 18f, 21f, 19f, 21f, 5f, 16f, 4f, 12f, 6f, 12f, 20f)
            }
            "search" -> {
                canvas.drawCircle(10f, 10f, 5.7f, ink); path(14.5f, 14.5f, 20f, 20f)
            }
            "favorite", "favorited" -> {
                val heart = Path().apply {
                    moveTo(12f, 20f)
                    cubicTo(9f, 17f, 2.5f, 13f, 2.5f, 8.5f)
                    cubicTo(2.5f, 3.7f, 9f, 2.5f, 12f, 7f)
                    cubicTo(15f, 2.5f, 21.5f, 3.7f, 21.5f, 8.5f)
                    cubicTo(21.5f, 13f, 15f, 17f, 12f, 20f)
                    close()
                }
                if (name == "favorited") ink.style = Paint.Style.FILL
                canvas.drawPath(heart, ink)
                ink.style = Paint.Style.STROKE
            }
            "cart" -> {
                path(2f, 4f, 5f, 4f, 8f, 16f, 19f, 16f)
                path(6f, 7f, 21f, 7f, 19f, 13f, 8f, 13f)
                canvas.drawCircle(9f, 20f, 1.25f, ink); canvas.drawCircle(18f, 20f, 1.25f, ink)
            }
            "person" -> {
                canvas.drawCircle(12f, 7.5f, 3.5f, ink)
                canvas.drawArc(RectF(5f, 13.5f, 19f, 27f), 180f, 180f, false, ink)
            }
            "location" -> {
                canvas.drawCircle(12f, 12f, 6.5f, ink); canvas.drawCircle(12f, 12f, 2f, ink)
                path(12f, 2f, 12f, 5f); path(12f, 19f, 12f, 22f)
                path(2f, 12f, 5f, 12f); path(19f, 12f, 22f, 12f)
            }
        }
        canvas.restore()
    }
}

internal class BookCovers {
    private val memory = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(view: ImageView, raw: String, scope: CoroutineScope) {
        val key = httpsUrl(raw)
        if (key.isEmpty()) return
        view.tag = key
        memory.get(key)?.let { view.setImageBitmap(it); return }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetch(key) }
            if (view.tag == key && bitmap != null) view.setImageBitmap(bitmap)
        }
    }

    private fun fetch(key: String): Bitmap? = runCatching {
        val connection = URI(key).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200 || connection.contentLengthLong > MAX_BYTES) return@runCatching null
            val bytes = connection.inputStream.use { stream ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > MAX_BYTES) return@runCatching null
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth < 2 || options.outHeight < 2) return@runCatching null
            options.inSampleSize = maxOf(1, maxOf(options.outHeight, options.outWidth) / 460)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { memory.put(key, it) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    companion object { private const val MAX_BYTES = 4_000_000L }
}
