import korlibs.image.color.*

// Every color below now sources from the active theme's Palette (see Theme.kt). The names are
// unchanged so all call sites keep working; switching theme swaps what these return.

val patternBorderOptions: Array<RGBA> get() = currentPalette().patternBorderOptions

val gameFieldColor: RGBA get() = currentPalette().gameFieldColor

val grayedGameFieldColor: RGBA get() = currentPalette().grayedGameFieldColor

val cellColor: RGBA get() = currentPalette().cellColor

// Faded to match the power-up tray backdrops so the board shows through.
val restartAndScoreColor: RGBA get() = currentPalette().restartAndScoreColor

val scoreTextColor: RGBA get() = currentPalette().scoreTextColor

// Power-up tray backdrops: a soft, faded tint matching each power-up.
val bombContainerColor: RGBA get() = currentPalette().bombContainerColor
val rocketContainerColor: RGBA get() = currentPalette().rocketContainerColor

val emptyCartridgeColor: RGBA get() = currentPalette().emptyCartridgeColor

// Semi-transparent so the cartridge outline reads as a soft border, not a glaring frame.
val cartridgeBorderColor: RGBA get() = currentPalette().cartridgeBorderColor

val loadedBombCartridgeColor: RGBA get() = currentPalette().loadedBombCartridgeColor

val loadedRocketCartridgeColor: RGBA get() = currentPalette().loadedRocketCartridgeColor

val pauseScreenBlockColor: RGBA get() = currentPalette().pauseScreenBlockColor
val pauseScreenBlockCopiedColor: RGBA get() = currentPalette().pauseScreenBlockCopiedColor

val pauseScreenTextColor: RGBA get() = currentPalette().pauseScreenTextColor
val pauseScreenTextHoverColor: RGBA get() = currentPalette().pauseScreenTextHoverColor
val pauseScreenTextDownColor: RGBA get() = currentPalette().pauseScreenTextDownColor
