import korlibs.image.color.RGBA
import kotlin.math.abs
import kotlin.math.max

// Tracks the highest forged tier color for the "final" glow; sourced from the active theme.
val finalColor: RGBA get() = currentPalette().finalColor

enum class Rank(
    val blockName: String,
    val value: Int,
    val display: String,
    val emoji: String
    ) {
    // The per-tile fill + text colors live in the active theme's Palette (see Theme.kt), keyed by
    // this ordinal. The block names below name each tile's *family*, which every theme preserves so
    // the shared emoji grid keeps matching the board.
    ZERO("Black", 1, "1", "⬛"),
    ONE("Yellow", 3, "3", "🟨"),
    TWO("Brown", 9, "9", "🟫"),
    THREE("Green", 27, "27", "🟩"),
    FOUR("Purple", 81, "81", "🟪"),
    FIVE("Red", 243, "243", "🟥"),
    SIX("Blue", 729, "729", "🟦"),
    SEVEN("White", 2187, "2187", "⬜"),
    EIGHT("Orange", 6561, "6561", "🟧"),
    NINE("Yellow Heart", 19683, "19683", "💛"),
    TEN("Green Heart", 59049, "59049", "💚"),
    ELEVEN("Black Heart", 177147, "3^11", "🖤"),
    TWELVE("Brown Heart", 531441, "3^12", "🤎"),
    THIRTEEN("Blue Heart", 1594323, "3^13", "💙"),
    FOURTEEN("Pink Heart", 4782969, "3^14", "🩷"),
    FIFTEEN("White Heart", 14348907, "3^15", "🤍"),
    SIXTEEN("Purple Heart", 43046721, "3^16", "💜"),
    SEVENTEEN("Trophy", 129140163, "3^17", "🏆"),
    EIGHTEEN("Crown", 387420489, "3^18", "👑"),
    NINETEEN("100 Emoji", 1162261467, "3^19", "💯"),
    ;

    /** Tile fill color from the active theme. */
    val color: RGBA get() = currentPalette().rankFill[ordinal]

    /** Tile number color from the active theme. */
    val TextColor: RGBA get() = currentPalette().rankText[ordinal]

    fun next() = values()[(ordinal + 1) % values().size]
    fun previous() = values()[max((ordinal - 1), 0) % values().size]
}
fun findClosest(comparisonValue: Int) = Rank.values().minByOrNull { number: Rank -> abs(number.value - comparisonValue) }!!
fun findClosestRoundedUp(comparisonValue: Int) = Rank.values().find { number: Rank -> number.value > comparisonValue }!!
