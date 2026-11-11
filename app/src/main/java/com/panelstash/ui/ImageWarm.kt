package com.panelstash.ui

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.allowHardware
import coil3.request.crossfade

/**
 * Fetches a page's encoded bytes into the disk cache and decodes nothing.
 *
 * Warming a page by decoding it only works for pages that fit in one bitmap. A webtoon strip is
 * 15,000 px tall, so the previous prefetcher skipped exactly the pages that needed help, and every
 * scroll forward waited on the network. The bytes are the expensive part: once they are on disk,
 * the bands of a tall page decode locally, which is a few dozen milliseconds.
 *
 * The memory cache key is separate from the page's own, so a 1x1 placeholder can never be served
 * in place of the real image.
 */
internal class ByteWarmDecoder(private val source: ImageSource) : Decoder {

    override suspend fun decode(): DecodeResult {
        // Draining the source is what commits the response to the disk cache; the pixels are
        // discarded rather than decoded.
        source.source().use { it.buffer.skip(Long.MAX_VALUE) }
        return DecodeResult(Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8).asImage(), false)
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder = ByteWarmDecoder(result.source)
    }
}

/** A request that only warms [url]'s bytes, sized so no real decode is attempted. */
internal fun warmRequest(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(1, 1)
        .memoryCacheKey("$url#bytes")
        .decoderFactory(ByteWarmDecoder.Factory())
        .crossfade(false)
        .allowHardware(false)
        .build()
