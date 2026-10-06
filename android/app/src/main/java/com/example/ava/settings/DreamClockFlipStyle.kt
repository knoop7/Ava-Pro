package com.example.ava.settings

private fun creamOnBlack(): DreamClockFlipCardColors =
    DreamClockFlipCardColors(0xFFFBF7F2.toInt(), 0xFF000000.toInt())

private fun whiteOnBlack(): DreamClockFlipCardColors =
    DreamClockFlipCardColors(0xFFFFFFFF.toInt(), 0xFF000000.toInt())

private fun blackOnWhite(): DreamClockFlipCardColors =
    DreamClockFlipCardColors(0xFF000000.toInt(), 0xFFFFFFFF.toInt())

private fun card(cardColor: Int, textColor: Int): DreamClockFlipCardColors =
    DreamClockFlipCardColors(cardColor, textColor)

/**
 * StandBy [RetroFlipStyle] 1:1 — 46 themes, each with backdrop plus HH/MM/SS card colors.
 */
enum class DreamClockFlipStyle(
    val storageKey: String,
    val backdrop: DreamClockFlipBackdrop,
    val hours: DreamClockFlipCardColors,
    val minutes: DreamClockFlipCardColors,
    val seconds: DreamClockFlipCardColors,
) {
    BLACK(
        "black",
        DreamClockFlipBackdrop.Solid(0xFF000000.toInt()),
        whiteOnBlack(),
        whiteOnBlack(),
        whiteOnBlack(),
    ),
    CHARCOAL(
        "charcoal",
        DreamClockFlipBackdrop.Solid(0xFF111111.toInt()),
        card(0xFF222222.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFF222222.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFF222222.toInt(), 0xFFFFFFFF.toInt()),
    ),
    WHITE(
        "white",
        DreamClockFlipBackdrop.Solid(0xFFFFFFFF.toInt()),
        blackOnWhite(),
        blackOnWhite(),
        blackOnWhite(),
    ),
    GRADIENT_1(
        "gradient1",
        DreamClockFlipBackdrop.Image(DreamClockFlipImage.ONE),
        creamOnBlack(),
        creamOnBlack(),
        creamOnBlack(),
    ),
    GRADIENT_5(
        "gradient5",
        DreamClockFlipBackdrop.Image(DreamClockFlipImage.FOUR),
        card(0xFF0E022F.toInt(), 0xFFFF6421.toInt()),
        card(0xFF0E022F.toInt(), 0xFFFF6421.toInt()),
        card(0xFF0E022F.toInt(), 0xFFFF6421.toInt()),
    ),
    LIGHT_CLASSIC_1(
        "light_classic_1",
        DreamClockFlipBackdrop.Solid(0xFFF9F9F9.toInt()),
        card(0xFF1B1B1B.toInt(), 0xFFF9F9F9.toInt()),
        card(0xFF333333.toInt(), 0xFFF9F9F9.toInt()),
        card(0xFF555555.toInt(), 0xFFF9F9F9.toInt()),
    ),
    COLORED_MODERN_DARK(
        "colored_modern_dark",
        DreamClockFlipBackdrop.Solid(0xFF080C38.toInt()),
        card(0xFFFF9933.toInt(), 0xFF0A0D38.toInt()),
        card(0xFF66CCFF.toInt(), 0xFF0A0D38.toInt()),
        card(0xFF99FF66.toInt(), 0xFF0A0D38.toInt()),
    ),
    GRADIENT_3(
        "gradient3",
        DreamClockFlipBackdrop.Image(DreamClockFlipImage.THREE),
        card(0xFF112223.toInt(), 0xFF6CF275.toInt()),
        card(0xFF112223.toInt(), 0xFF6CF275.toInt()),
        card(0xFF112223.toInt(), 0xFF6CF275.toInt()),
    ),
    SAN_FRANCISCO(
        "san_francisco",
        DreamClockFlipBackdrop.Linear(0xFFEFEBE9.toInt(), 0xFFD1D1D1.toInt()),
        blackOnWhite(),
        blackOnWhite(),
        blackOnWhite(),
    ),
    ELEGANT_CLASSIC(
        "elegant_classic",
        DreamClockFlipBackdrop.Linear(0xFFE0E0E0.toInt(), 0xFFD1D1D1.toInt()),
        card(0xFF1B1B1B.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFF333333.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFF555555.toInt(), 0xFFFFFFFF.toInt()),
    ),
    WHITE_ON_BLUE(
        "white_on_blue",
        DreamClockFlipBackdrop.Linear(0xFF3793EF.toInt(), 0xFF0E49A3.toInt()),
        card(0xFFFFFFFF.toInt(), 0xFF080C38.toInt()),
        card(0xFFFFFFFF.toInt(), 0xFF080C38.toInt()),
        card(0xFFFFFFFF.toInt(), 0xFF080C38.toInt()),
    ),
    GRADIENT_2(
        "gradient2",
        DreamClockFlipBackdrop.Image(DreamClockFlipImage.TWO),
        creamOnBlack(),
        creamOnBlack(),
        creamOnBlack(),
    ),
    VERY_BLUEISH_LIGHT(
        "very_blueish_light",
        DreamClockFlipBackdrop.Solid(0xFFE6E7E8.toInt()),
        card(0xFF020434.toInt(), 0xFFE6E7E8.toInt()),
        card(0xFF020434.toInt(), 0xFFE6E7E8.toInt()),
        card(0xFF020434.toInt(), 0xFFE6E7E8.toInt()),
    ),
    GRADIENT_4(
        "gradient4",
        DreamClockFlipBackdrop.Image(DreamClockFlipImage.FOUR),
        card(0xFF821FFF.toInt(), 0xFFFFCFCF.toInt()),
        card(0xFF821FFF.toInt(), 0xFFFFCFCF.toInt()),
        card(0xFF821FFF.toInt(), 0xFFFFCFCF.toInt()),
    ),
    PASTEL_ORANGE_DARK(
        "pastel_orange_dark",
        DreamClockFlipBackdrop.Solid(0xFF000000.toInt()),
        card(0xFFFB6D45.toInt(), 0xFF000000.toInt()),
        card(0xFFFB6D45.toInt(), 0xFF000000.toInt()),
        card(0xFFFB6D45.toInt(), 0xFF000000.toInt()),
    ),
    SEMAPHORE(
        "semaphore",
        DreamClockFlipBackdrop.Solid(0xFF080C38.toInt()),
        card(0xFFFFD700.toInt(), 0xFF080C38.toInt()),
        card(0xFFFF4500.toInt(), 0xFF080C38.toInt()),
        card(0xFF9932CC.toInt(), 0xFF080C38.toInt()),
    ),
    RETRO_COLORFUL(
        "retro_colorful",
        DreamClockFlipBackdrop.Solid(0xFF020434.toInt()),
        card(0xFFFF3399.toInt(), 0xFF020434.toInt()),
        card(0xFF4EDDFF.toInt(), 0xFF020434.toInt()),
        card(0xFFFFD700.toInt(), 0xFF020434.toInt()),
    ),
    FUTURISTIC_RETRO_2(
        "futuristic_retro_2",
        DreamClockFlipBackdrop.Linear(0xFF0A192F.toInt(), 0xFF020C1B.toInt()),
        card(0xFFFFD700.toInt(), 0xFF0A192F.toInt()),
        card(0xFFFF6B6B.toInt(), 0xFF0A192F.toInt()),
        card(0xFF00BCD4.toInt(), 0xFFFFFFFF.toInt()),
    ),
    GREEN_AND_WHITE(
        "green_and_white",
        DreamClockFlipBackdrop.Linear(0xFF6FE275.toInt(), 0xFF164319.toInt()),
        card(0xFF57B85C.toInt(), 0xFFFADFFF.toInt()),
        card(0xFF46984A.toInt(), 0xFFFADFFF.toInt()),
        card(0xFF164319.toInt(), 0xFFFADFFF.toInt()),
    ),
    COSMIC_RETRO(
        "cosmic_retro",
        DreamClockFlipBackdrop.Linear(0xFF2E003E.toInt(), 0xFF17001D.toInt()),
        card(0xFFFF3399.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFF00A8E0.toInt(), 0xFF2E003E.toInt()),
        card(0xFFFFD700.toInt(), 0xFF2E003E.toInt()),
    ),
    PURPLE_AND_WHITE(
        "purple_and_white",
        DreamClockFlipBackdrop.Linear(0xFF9A27AF.toInt(), 0xFF6C1C9B.toInt()),
        card(0xFF6C1C9B.toInt(), 0xFFFADFFF.toInt()),
        card(0xFFA03BB2.toInt(), 0xFFFADFFF.toInt()),
        card(0xFF9A27AF.toInt(), 0xFFFADFFF.toInt()),
    ),
    ROARING_TWENTIES(
        "roaring_twenties",
        DreamClockFlipBackdrop.Linear(0xFF000000.toInt(), 0xFF333333.toInt()),
        card(0xFFD4AF37.toInt(), 0xFF000000.toInt()),
        card(0xFFD81159.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFF3333FF.toInt(), 0xFFFFFFFF.toInt()),
    ),
    VERY_BLUEISH_DARK(
        "very_blueish_dark",
        DreamClockFlipBackdrop.Solid(0xFF020434.toInt()),
        card(0xFF4EDDFF.toInt(), 0xFF020434.toInt()),
        card(0xFF4EDDFF.toInt(), 0xFF020434.toInt()),
        card(0xFF4EDDFF.toInt(), 0xFF020434.toInt()),
    ),
    GRADIENT_6(
        "gradient6",
        DreamClockFlipBackdrop.Image(DreamClockFlipImage.FIVE),
        card(0xFFFF7020.toInt(), 0xFFFFE1E1.toInt()),
        card(0xFFFF7020.toInt(), 0xFFFFE1E1.toInt()),
        card(0xFFFF7020.toInt(), 0xFFFFE1E1.toInt()),
    ),
    COLORED_MODERN(
        "colored_modern",
        DreamClockFlipBackdrop.Solid(0xFFF9F9F9.toInt()),
        card(0xFFFF9933.toInt(), 0xFF080C38.toInt()),
        card(0xFF66CCFF.toInt(), 0xFF080C38.toInt()),
        card(0xFF99FF66.toInt(), 0xFF080C38.toInt()),
    ),
    ART_DECO(
        "art_deco",
        DreamClockFlipBackdrop.Linear(0xFF000000.toInt(), 0xFF333333.toInt()),
        card(0xFFFFD700.toInt(), 0xFF000000.toInt()),
        card(0xFFFF4500.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFF9932CC.toInt(), 0xFFFFFFFF.toInt()),
    ),
    KINDA_GREENISH(
        "kinda_greenish",
        DreamClockFlipBackdrop.Solid(0xFF91C499.toInt()),
        card(0xFFF2E9DC.toInt(), 0xFF91C499.toInt()),
        card(0xFFF2E9DC.toInt(), 0xFF91C499.toInt()),
        card(0xFFF2E9DC.toInt(), 0xFF91C499.toInt()),
    ),
    BLUE_AND_WHITE(
        "blue_and_white",
        DreamClockFlipBackdrop.Linear(0xFF3793EF.toInt(), 0xFF0E49A3.toInt()),
        card(0xFF0E49A3.toInt(), 0xFFFADFFF.toInt()),
        card(0xFF3793EF.toInt(), 0xFFFADFFF.toInt()),
        card(0xFF1367D4.toInt(), 0xFFFADFFF.toInt()),
    ),
    WHITE_ON_RED(
        "white_on_red",
        DreamClockFlipBackdrop.Linear(0xFFD71B60.toInt(), 0xFF731037.toInt()),
        card(0xFFFFFFFF.toInt(), 0xFF080C38.toInt()),
        card(0xFFFFFFFF.toInt(), 0xFF080C38.toInt()),
        card(0xFFFFFFFF.toInt(), 0xFF080C38.toInt()),
    ),
    MODERN_STYLE_2(
        "modern_style_2",
        DreamClockFlipBackdrop.Linear(0xFF1E1E1E.toInt(), 0xFF292929.toInt()),
        card(0xFF00BFFF.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFFFF4500.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFF4CAF50.toInt(), 0xFFFFFFFF.toInt()),
    ),
    ALIEN_TECH(
        "alien_tech",
        DreamClockFlipBackdrop.Linear(0xFF3D3D3D.toInt(), 0xFF292929.toInt()),
        card(0xFF00BFFF.toInt(), 0xFF3D3D3D.toInt()),
        card(0xFFFF6600.toInt(), 0xFF3D3D3D.toInt()),
        card(0xFFFF33CC.toInt(), 0xFFFFFFFF.toInt()),
    ),
    KINDA_GREENISH_LIGHT(
        "kinda_greenish_light",
        DreamClockFlipBackdrop.Solid(0xFFF2E9DC.toInt()),
        card(0xFF91C499.toInt(), 0xFFF2E9DC.toInt()),
        card(0xFF91C499.toInt(), 0xFFF2E9DC.toInt()),
        card(0xFF91C499.toInt(), 0xFFF2E9DC.toInt()),
    ),
    CELESTIAL_DREAMS(
        "celestial_dreams",
        DreamClockFlipBackdrop.Linear(0xFF0F4C75.toInt(), 0xFF1B262C.toInt()),
        card(0xFFE1B382.toInt(), 0xFF000000.toInt()),
        card(0xFF348AA7.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFF525252.toInt(), 0xFFFFFFFF.toInt()),
    ),
    MATERIAL_DARK(
        "material_dark",
        DreamClockFlipBackdrop.Linear(0xFF212121.toInt(), 0xFF333333.toInt()),
        card(0xFF03A9F4.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFFFF9800.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFFFF5722.toInt(), 0xFFFFFFFF.toInt()),
    ),
    FOREST_RETRO(
        "forest_retro",
        DreamClockFlipBackdrop.Linear(0xFF005C3E.toInt(), 0xFF00A572.toInt()),
        card(0xFFFFAA00.toInt(), 0xFF005C3E.toInt()),
        card(0xFFEEAF30.toInt(), 0xFF005C3E.toInt()),
        card(0xFF00747D.toInt(), 0xFFFFFFFF.toInt()),
    ),
    IOS_16(
        "ios16",
        DreamClockFlipBackdrop.Linear(0xFF489EFF.toInt(), 0xFF347AF0.toInt()),
        whiteOnBlack(),
        whiteOnBlack(),
        whiteOnBlack(),
    ),
    PURPLE_MODERN_LIGHT(
        "purple_modern_light",
        DreamClockFlipBackdrop.Solid(0xFFFFFFFF.toInt()),
        card(0xFFA116FF.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFFA116FF.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFFA116FF.toInt(), 0xFFFFFFFF.toInt()),
    ),
    MATERIAL_ACCENT(
        "material_accent",
        DreamClockFlipBackdrop.Linear(0xFF303F9F.toInt(), 0xFF1976D2.toInt()),
        card(0xFFFF4081.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFF4CAF50.toInt(), 0xFFFFFFFF.toInt()),
        card(0xFFFF5722.toInt(), 0xFFFFFFFF.toInt()),
    ),
    PASTEL_ORANGE_LIGHT(
        "pastel_orange_light",
        DreamClockFlipBackdrop.Solid(0xFFFCF4F0.toInt()),
        card(0xFFFB6D45.toInt(), 0xFFE6E7E8.toInt()),
        card(0xFFFB6D45.toInt(), 0xFFE6E7E8.toInt()),
        card(0xFFFB6D45.toInt(), 0xFFE6E7E8.toInt()),
    ),
    MIDNIGHT_TEAL(
        "midnight_teal",
        DreamClockFlipBackdrop.Linear(0xFF004D40.toInt(), 0xFF00251A.toInt()),
        card(0xFF80CBC4.toInt(), 0xFF004D40.toInt()),
        card(0xFF80CBC4.toInt(), 0xFF004D40.toInt()),
        card(0xFF80CBC4.toInt(), 0xFF004D40.toInt()),
    ),
    WARM_AMBER(
        "warm_amber",
        DreamClockFlipBackdrop.Linear(0xFF3E2723.toInt(), 0xFF1B0000.toInt()),
        card(0xFFFFAB40.toInt(), 0xFF3E2723.toInt()),
        card(0xFFFFAB40.toInt(), 0xFF3E2723.toInt()),
        card(0xFFFFAB40.toInt(), 0xFF3E2723.toInt()),
    ),
    SAKURA(
        "sakura",
        DreamClockFlipBackdrop.Linear(0xFFFFCDD2.toInt(), 0xFFF8BBD0.toInt()),
        card(0xFFC62828.toInt(), 0xFFFCE4EC.toInt()),
        card(0xFFAD1457.toInt(), 0xFFFCE4EC.toInt()),
        card(0xFF880E4F.toInt(), 0xFFFCE4EC.toInt()),
    ),
    OCEAN_DEEP(
        "ocean_deep",
        DreamClockFlipBackdrop.Linear(0xFF0D47A1.toInt(), 0xFF01579B.toInt()),
        card(0xFF29B6F6.toInt(), 0xFF0D47A1.toInt()),
        card(0xFF4FC3F7.toInt(), 0xFF0D47A1.toInt()),
        card(0xFF81D4FA.toInt(), 0xFF01579B.toInt()),
    ),
    LAVENDER_MIST(
        "lavender_mist",
        DreamClockFlipBackdrop.Solid(0xFFE8EAF6.toInt()),
        card(0xFF5C6BC0.toInt(), 0xFFE8EAF6.toInt()),
        card(0xFF5C6BC0.toInt(), 0xFFE8EAF6.toInt()),
        card(0xFF5C6BC0.toInt(), 0xFFE8EAF6.toInt()),
    ),
    NEON_CYAN(
        "neon_cyan",
        DreamClockFlipBackdrop.Solid(0xFF0A0A0A.toInt()),
        card(0xFF00E5FF.toInt(), 0xFF0A0A0A.toInt()),
        card(0xFF00E5FF.toInt(), 0xFF0A0A0A.toInt()),
        card(0xFF00E5FF.toInt(), 0xFF0A0A0A.toInt()),
    ),
    ROSE_GOLD(
        "rose_gold",
        DreamClockFlipBackdrop.Linear(0xFF2C2C2C.toInt(), 0xFF1A1A1A.toInt()),
        card(0xFFE0BFA8.toInt(), 0xFF2C2C2C.toInt()),
        card(0xFFE0BFA8.toInt(), 0xFF2C2C2C.toInt()),
        card(0xFFE0BFA8.toInt(), 0xFF2C2C2C.toInt()),
    ),
    MINT_FRESH(
        "mint_fresh",
        DreamClockFlipBackdrop.Solid(0xFFE0F2F1.toInt()),
        card(0xFF00695C.toInt(), 0xFFE0F2F1.toInt()),
        card(0xFF00897B.toInt(), 0xFFE0F2F1.toInt()),
        card(0xFF26A69A.toInt(), 0xFFE0F2F1.toInt()),
    ),
    SUNSET_BLAZE(
        "sunset_blaze",
        DreamClockFlipBackdrop.Linear(0xFFBF360C.toInt(), 0xFF870000.toInt()),
        card(0xFFFFCC80.toInt(), 0xFFBF360C.toInt()),
        card(0xFFFFAB40.toInt(), 0xFFBF360C.toInt()),
        card(0xFFFF6D00.toInt(), 0xFF870000.toInt()),
    ),
    ELECTRIC_VIOLET(
        "electric_violet",
        DreamClockFlipBackdrop.Solid(0xFF0A0A14.toInt()),
        card(0xFFB388FF.toInt(), 0xFF0A0A14.toInt()),
        card(0xFF7C4DFF.toInt(), 0xFF0A0A14.toInt()),
        card(0xFFD500F9.toInt(), 0xFF0A0A14.toInt()),
    ),
    COPPER_PATINA(
        "copper_patina",
        DreamClockFlipBackdrop.Linear(0xFF263238.toInt(), 0xFF37474F.toInt()),
        card(0xFFB2835E.toInt(), 0xFF263238.toInt()),
        card(0xFF80CBC4.toInt(), 0xFF37474F.toInt()),
        card(0xFFB2835E.toInt(), 0xFF263238.toInt()),
    ),
    CANDY_POP(
        "candy_pop",
        DreamClockFlipBackdrop.Linear(0xFFFF6F91.toInt(), 0xFFFF9671.toInt()),
        card(0xFFFFFFFF.toInt(), 0xFFFF6F91.toInt()),
        card(0xFFFFFFFF.toInt(), 0xFFFF9671.toInt()),
        card(0xFFFFFFFF.toInt(), 0xFFFFC75F.toInt()),
    ),
    BLOOD_MOON(
        "blood_moon",
        DreamClockFlipBackdrop.Solid(0xFF1A0000.toInt()),
        card(0xFFD50000.toInt(), 0xFF1A0000.toInt()),
        card(0xFFFF1744.toInt(), 0xFF1A0000.toInt()),
        card(0xFFD50000.toInt(), 0xFF1A0000.toInt()),
    ),
    ;

    /** Localized display name — string resource `dream_clock_flip_style_<storageKey>`. */
    fun labelRes(context: android.content.Context): Int {
        val id = context.resources.getIdentifier(
            "dream_clock_flip_style_$storageKey", "string", context.packageName
        )
        return if (id != 0) id else 0
    }

    fun displayName(context: android.content.Context): String {
        val res = labelRes(context)
        return if (res != 0) context.getString(res) else storageKey
    }

    fun next(): DreamClockFlipStyle {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    fun previous(): DreamClockFlipStyle {
        val all = entries
        return all[(ordinal - 1 + all.size) % all.size]
    }

    companion object {
        fun fromStored(value: String?): DreamClockFlipStyle =
            entries.find { it.storageKey == value?.trim() } ?: BLACK
    }
}

data class DreamClockFlipCardColors(
    val card: Int,
    val text: Int,
)

sealed class DreamClockFlipBackdrop {
    data class Solid(val color: Int) : DreamClockFlipBackdrop()
    data class Linear(val start: Int, val end: Int) : DreamClockFlipBackdrop()
    data class Image(val image: DreamClockFlipImage) : DreamClockFlipBackdrop()
}

enum class DreamClockFlipImage {
    ONE,
    TWO,
    THREE,
    FOUR,
    FIVE,
}

/**
 * Flip digit typeface. StandBy stores `selectedRetroFlipFontName` the same way;
 * only LEAGUE_GOTHIC ships as an asset (16 KB), the rest are free system faces.
 */
enum class DreamClockFlipFont(val storageKey: String) {
    LEAGUE_GOTHIC("league_gothic"),
    SYSTEM("system"),
    CONDENSED("condensed"),
    SERIF("serif"),
    MONO("mono"),
    ;

    companion object {
        fun fromStored(value: String?): DreamClockFlipFont =
            entries.find { it.storageKey == value?.trim() } ?: LEAGUE_GOTHIC
    }
}
