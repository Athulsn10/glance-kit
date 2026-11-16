package com.athulsn10.glancekit

import android.content.Context
import android.graphics.Typeface

/**
 * Fonts for the Clock widget's time. Each has its own time-row layout (RemoteViews can't change a
 * font at run time, so the widget swaps in the layout for the chosen font) and a light weight for
 * the time plus a medium weight for AM/PM and the dial numbers. All are SIL Open Font License
 * fonts from Google Fonts, cut down to the characters a clock needs (licences in assets/licenses).
 *
 * [widthEm] is how wide "00:00" is (in em, after letter spacing) and [lineEm] the line height, so
 * the time can be sized to fill the widget without being cut off.
 */
enum class ClockFont(
    val key: String,
    val label: String,
    val note: String,
    val timeLayout: Int,
    val light: Int,
    val medium: Int,
    val widthEm: Float,
    val lineEm: Float,
) {
    OUTFIT("outfit", "Outfit", "Round and soft", R.layout.clock_time_outfit, R.font.clock_outfit_light, R.font.clock_outfit_medium, 2.75f, 1.26f),
    SORA("sora", "Sora", "Wide and technical", R.layout.clock_time_sora, R.font.clock_sora_light, R.font.clock_sora_medium, 2.96f, 1.26f),
    MANROPE("manrope", "Manrope", "Clean and soft", R.layout.clock_time_manrope, R.font.clock_manrope_light, R.font.clock_manrope_medium, 2.42f, 1.37f),
    URBANIST("urbanist", "Urbanist", "Geometric and elegant", R.layout.clock_time_urbanist, R.font.clock_urbanist_light, R.font.clock_urbanist_medium, 2.38f, 1.20f),
    LEXEND("lexend", "Lexend", "Friendly and easy to read", R.layout.clock_time_lexend, R.font.clock_lexend_light, R.font.clock_lexend_medium, 2.49f, 1.25f),
    SPACE_GROTESK("spacegrotesk", "Space Grotesk", "Digits with character", R.layout.clock_time_spacegrotesk, R.font.clock_spacegrotesk_light, R.font.clock_spacegrotesk_medium, 2.59f, 1.27f),
    FRAUNCES("fraunces", "Fraunces", "Soft serif, like a dress watch", R.layout.clock_time_fraunces, R.font.clock_fraunces_light, R.font.clock_fraunces_medium, 2.26f, 1.24f),
    BIG_SHOULDERS("bigshoulders", "Big Shoulders", "Tall and sporty", R.layout.clock_time_bigshoulders, R.font.clock_bigshoulders_light, R.font.clock_bigshoulders_medium, 1.72f, 1.19f);

    companion object {
        private const val PREF = "clock_font"

        fun current(c: Context): ClockFont {
            val k = Store.prefs(c).getString(PREF, null)
            return entries.firstOrNull { it.key == k } ?: OUTFIT
        }

        fun set(c: Context, f: ClockFont) {
            Store.prefs(c).edit().putString(PREF, f.key).apply()
        }

        private val cache = HashMap<Int, Typeface>()

        /** Typeface for a font resource, loaded once. Falls back to the system font. */
        fun typeface(c: Context, res: Int): Typeface = synchronized(cache) {
            cache.getOrPut(res) { runCatching { c.resources.getFont(res) }.getOrNull() ?: Typeface.DEFAULT }
        }
    }
}
