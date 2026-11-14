package local.iconstudio

import android.content.ComponentName
import android.content.Context
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

/** The editor preview and APK builder share this renderer. */
object IconRenderer {
    private const val SIZE = 192

    fun installedIcon(context: Context, component: String): Drawable {
        val launcher = requireNotNull(ComponentName.unflattenFromString(component))
        if (launcher.packageName == context.packageName) {
            return BitmapDrawable(context.resources, loadPrismLogo(context))
        }
        val pm = context.packageManager
        val info = pm.getActivityInfo(launcher, 0)
        // Load the source resource directly rather than an icon resolved by Samsung's theme layer.
        val resource = info.iconResource
        if (resource == 0) return pm.defaultActivityIcon
        return try {
            val resources = pm.getResourcesForApplication(info.applicationInfo)
            resources.getDrawable(resource, null)
        } catch (_: android.content.res.Resources.NotFoundException) {
            pm.defaultActivityIcon
        }
    }

    fun loadPrismLogo(context: Context): Bitmap =
        context.assets.open("prism-logo.png").use { BitmapFactory.decodeStream(it) }
            ?: error("Bundled Prism logo missing")

    fun snapshot(drawable: Drawable): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also {
        drawable.setBounds(0, 0, SIZE, SIZE)
        drawable.draw(Canvas(it))
    }

    fun renderFallback(drawable: Drawable, shape: String, scale: Float, background: Int?): Bitmap {
        if (background == null) {
            val original = snapshot(drawable)
            return try { render(original, shape, scale, normalizeVisible = true) } finally { original.recycle() }
        }

        val tile = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        canvas.drawColor(background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val foreground = (drawable as? AdaptiveIconDrawable)?.foreground?.let(::transparentForeground)
        val artwork = foreground ?: snapshot(drawable)
        try {
            val bounds = visibleBounds(artwork)
            val destination = fit(bounds, 126f)
            if (foreground != null) {
                paint.color = logoBacking(foreground)
                canvas.drawRoundRect(24f, 24f, 168f, 168f, 30f, 30f, paint)
                paint.color = Color.WHITE
            }
            canvas.drawBitmap(artwork, bounds, destination, paint)
            return render(tile, shape, scale)
        } finally {
            artwork.recycle()
            tile.recycle()
        }
    }

    // Keep the complete icon when the foreground fills or crosses the center crop.
    private fun transparentForeground(layer: Drawable): Bitmap? {
        val image = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        layer.setBounds(-48, -48, SIZE + 48, SIZE + 48)
        layer.draw(Canvas(image))
        val bounds = visibleBounds(image)
        if (bounds.left <= 1 || bounds.top <= 1 || bounds.right >= SIZE - 1 || bounds.bottom >= SIZE - 1) {
            image.recycle()
            return null
        }
        return image
    }

    private fun logoBacking(artwork: Bitmap): Int {
        val pixels = IntArray(SIZE * SIZE)
        artwork.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        var opaque = 0
        var bright = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) < 224) continue
            opaque++
            if (Color.luminance(pixel or 0xff000000.toInt()) > 0.45f) bright++
        }
        return if (bright > opaque / 2) 0xcc0e0e12.toInt() else 0xccffffff.toInt()
    }

    private fun visibleBounds(source: Bitmap): Rect {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        var left = source.width
        var top = source.height
        var right = -1
        var bottom = -1
        for (y in 0 until source.height) for (x in 0 until source.width) {
            if (Color.alpha(pixels[y * source.width + x]) >= 16) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        return if (right >= left && bottom >= top) Rect(left, top, right + 1, bottom + 1)
        else Rect(0, 0, source.width, source.height)
    }

    private fun fit(bounds: Rect, extent: Float): RectF {
        val factor = extent / maxOf(bounds.width(), bounds.height())
        val halfWidth = bounds.width() * factor / 2
        val halfHeight = bounds.height() * factor / 2
        val center = SIZE / 2f
        return RectF(center - halfWidth, center - halfHeight, center + halfWidth, center + halfHeight)
    }

    fun render(source: Bitmap, shape: String, scale: Float, normalizeVisible: Boolean = false): Bitmap {
        val result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val bounds = if (normalizeVisible) visibleBounds(source) else Rect(0, 0, source.width, source.height)
        val rect = fit(bounds, SIZE * scale.coerceIn(0.6f, 1f))
        canvas.drawBitmap(source, bounds, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (shape != "Original") {
            val mask = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            Canvas(mask).apply {
                when (shape) {
                    "Device" -> {
                        val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.WHITE), ColorDrawable(Color.TRANSPARENT))
                        adaptive.setBounds(0, 0, SIZE, SIZE)
                        val path = Path(adaptive.iconMask)
                        path.transform(Matrix().apply {
                            setRectToRect(RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), rect, Matrix.ScaleToFit.FILL)
                        })
                        drawPath(path, paint)
                    }
                    "Circle" -> drawOval(rect, paint)
                    else -> drawRoundRect(rect, rect.width() * 0.24f, rect.height() * 0.24f, paint)
                }
            }
            canvas.drawBitmap(mask, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
            mask.recycle()
        }
        return result
    }
}
