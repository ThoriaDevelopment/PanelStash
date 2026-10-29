package com.panelstash.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.crossfade

/** As tall as a bitmap may be before a decode starts throwing pixels away. */
private const val MAX_DECODE_HEIGHT = 16_384

/**
 * Decodes one horizontal band of a very tall page.
 *
 * A webtoon page is often a single strip 40,000 px tall. Decoded whole it is far past the bitmap
 * and texture limits, so a single request gets downsampled hard and drawn back up as a blurred
 * smear. Cutting bands out of the encoded bytes with [BitmapRegionDecoder] keeps every bitmap a
 * normal size while the page is still drawn at the resolution its source actually has.
 *
 * The band boundaries are fractions of the image height, so the caller does not need to know how
 * tall the page is — only how many bands it should be cut into.
 */
internal class PageBandDecoder(
    private val source: ImageSource,
    private val band: Int,
    private val bands: Int,
) : Decoder {

    @Suppress("DEPRECATION") // The byte array overload is the only one below Android 12.
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        // WebP only became region-decodable in Android 12; an older phone gets a sampled whole
        // image with its band cut out instead, which is soft but not blank.
        val region = runCatching {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }.getOrNull() ?: return decodeWithoutRegions(bytes)
        try {
            val top = (region.height.toLong() * band / bands).toInt()
            // One row of overlap: a band that rounds to a pixel less than its share of the page
            // would leave a hairline of background between it and the next one. The extra row is
            // cropped away, and a seam that cannot open is worth a row nobody sees.
            val bottom = (region.height.toLong() * (band + 1) / bands).toInt()
                .plus(1)
                .coerceAtMost(region.height)
            val rect = Rect(0, top, region.width, bottom.coerceAtLeast(top + 1))
            val bitmap = region.decodeRegion(rect, BitmapFactory.Options())
                ?: throw IllegalStateException("Band $band of $bands decoded empty")
            return DecodeResult(bitmap.asImage(), false)
        } finally {
            region.recycle()
        }
    }

    private fun decodeWithoutRegions(bytes: ByteArray): DecodeResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outHeight / sample > MAX_DECODE_HEIGHT) sample *= 2
        val whole = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IllegalStateException("Image cannot be decoded")
        val top = (whole.height.toLong() * band / bands).toInt()
        val bottom = (whole.height.toLong() * (band + 1) / bands).toInt()
            .plus(1)
            .coerceAtMost(whole.height)
        val bandBitmap = Bitmap.createBitmap(whole, 0, top, whole.width, (bottom - top).coerceAtLeast(1))
        if (bandBitmap !== whole) whole.recycle()
        return DecodeResult(bandBitmap.asImage(), true)
    }

    class Factory(private val band: Int, private val bands: Int) : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder = PageBandDecoder(result.source, band, bands)
    }
}

/**
 * The request for one band of a page.
 *
 * It carries the same data as the whole-page request, so the encoded bytes come from the image
 * cache instead of the network. Only the memory cache key differs: a band is a different bitmap
 * from the page it belongs to.
 */
internal fun bandRequest(
    context: Context,
    model: Any,
    band: Int,
    bands: Int,
): ImageRequest = ImageRequest.Builder(context)
    .data(model)
    .decoderFactory(PageBandDecoder.Factory(band, bands))
    .memoryCacheKey("$model#band/$band/$bands")
    .crossfade(false)
    .build()
