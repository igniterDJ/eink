package com.keychain.epd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Maps each event category to the horizontal background images bundled under
 * assets/event_backgrounds/ (copied from eink_event_backgrounds_horizontal, one
 * pair per category except Health which only has a single image).
 */
object EventBackgrounds {
    private const val ASSET_DIR = "event_backgrounds"

    private val FILES: Map<EventCategory, List<String>> = mapOf(
        EventCategory.ANNIVERSARY to listOf("Anniversary_01_Rings.png", "Anniversary_02_Lovebirds.png"),
        EventCategory.BIRTHDAY to listOf("Birthday_01_Cake.png", "Birthday_02_Cupcake.png"),
        EventCategory.FRIENDS to listOf("Friends_01_Clinking_Mugs.png"),
        EventCategory.HEALTH to listOf("health.png"),
        EventCategory.TRAVEL to listOf("Travel_01_Airplane_Suitcase.png", "Travel_02_Passport_Compass.png")
    )

    fun count(category: EventCategory): Int = FILES.getValue(category).size

    /** [index] wraps around, so callers can cycle "next image" without bounds-checking. */
    fun load(context: Context, category: EventCategory, index: Int): Bitmap {
        val files = FILES.getValue(category)
        val name = files[((index % files.size) + files.size) % files.size]
        context.assets.open("$ASSET_DIR/$name").use { stream ->
            return BitmapFactory.decodeStream(stream)
        }
    }
}
