package fr.niakimbo.dayone

/** Tous les réglages de personnalisation du widget. */
data class Config(
    val days: Boolean = true,
    val hours: Boolean = false,
    val minutes: Boolean = false,
    val seconds: Boolean = false,
    val dots: Boolean = true,
    val accent: Int = RED,
    val showName: Boolean = true,
    val showPlatforms: Boolean = true,
    val showDate: Boolean = true,
    val showPagination: Boolean = true,
    val showGrid: Boolean = true,
    val dim: Int = 100,
    val corner: Int = 26,
    val numScale: Int = 100,
    val nameScale: Int = 100,
    val updateAvailable: Boolean = false,
) {
    companion object {
        const val RED = 0xFFD71921.toInt()
    }
}
