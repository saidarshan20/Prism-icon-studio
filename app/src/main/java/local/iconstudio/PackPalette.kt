package local.iconstudio

import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.abs

class PackPalette private constructor(val colors: List<Int>) {
    fun forPackage(packageName: String): Int = colors[Math.floorMod(packageName.hashCode(), colors.size)]

    companion object {
        private const val MAX_COLORS = 6
        private const val PALETTE_DISTANCE = 52
        private val edgeSamples = listOf(
            0.18f to 0.18f, 0.82f to 0.18f, 0.18f to 0.82f, 0.82f to 0.82f,
            0.50f to 0.12f, 0.50f to 0.88f, 0.12f to 0.50f, 0.88f to 0.50f,
        )

        fun fromArtwork(paths: List<String>): PackPalette? {
            val samples = paths.asSequence().take(80).mapNotNull(::backgroundColor).toMutableList()
            val colors = mutableListOf<Int>()
            while (samples.isNotEmpty() && colors.size < MAX_COLORS) {
                val color = samples.maxBy { candidate -> samples.count { distance(it, candidate) <= PALETTE_DISTANCE } }
                colors.add(color)
                samples.removeAll { distance(it, color) <= PALETTE_DISTANCE }
            }
            return colors.takeIf { it.isNotEmpty() }?.let(::PackPalette)
        }

        private fun backgroundColor(path: String): Int? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val size = maxOf(bounds.outWidth, bounds.outHeight)
            if (size < 1) return null
            val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, Integer.highestOneBit(size / 32)) }
            val image = BitmapFactory.decodeFile(path, options) ?: return null
            try {
                val opaque = edgeSamples.map { (x, y) ->
                    image.getPixel((image.width * x).toInt(), (image.height * y).toInt())
                }.filter { Color.alpha(it) >= 224 }
                val background = opaque.maxByOrNull { color -> opaque.count { distance(it, color) <= 60 } }
                return background?.takeIf { color -> opaque.count { distance(it, color) <= 60 } >= 3 }
                    ?.let { it or 0xff000000.toInt() }
            } finally {
                image.recycle()
            }
        }

        private fun distance(a: Int, b: Int): Int = abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
    }
}
