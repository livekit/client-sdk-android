package io.livekit.android.room.track.video


data class ScalabilityMode(val spatial: Int, val temporal: Int, val suffix: String) {
    companion object {
        private val REGEX = """L(\d)T(\d)(h|_KEY|_KEY_SHIFT)?""".toRegex()
        fun parseFromString(mode: String): ScalabilityMode {
            val match = REGEX.matchEntire(mode) ?: throw IllegalArgumentException("can't parse scalability mode: $mode")
            val (spatial, temporal, suffix) = match.destructured

            return ScalabilityMode(spatial.toInt(), temporal.toInt(), suffix)
        }
    }
}
