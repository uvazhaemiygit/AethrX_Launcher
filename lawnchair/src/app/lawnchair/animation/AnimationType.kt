package app.lawnchair.animation

enum class AnimationType(val value: String) {
    FAST("fast"),
    SMOOTH("smooth");

    companion object {
        fun fromValue(value: String): AnimationType = entries.find { it.value == value } ?: FAST
    }
}
