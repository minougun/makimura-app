package com.minou.pedometer

data class StrideModel(
    val walkMeters: Double,
    val briskMeters: Double,
    val runMeters: Double,
)

object StrideEstimator {
    private const val MALE_RATIO = 0.415
    private const val FEMALE_RATIO = 0.413
    private const val OTHER_RATIO = 0.414

    fun estimate(profile: UserProfile): StrideModel {
        val heightMeters = profile.normalizedHeightCm / 100.0
        val baseRatio = when (profile.sex) {
            UserSex.MALE -> MALE_RATIO
            UserSex.FEMALE -> FEMALE_RATIO
            UserSex.OTHER -> OTHER_RATIO
        }

        val walk = (heightMeters * baseRatio * profile.normalizedStrideScale)
            .coerceIn(0.45, 1.10)

        return StrideModel(
            walkMeters = walk,
            briskMeters = (walk * 1.12).coerceIn(0.50, 1.25),
            runMeters = (walk * 1.50).coerceIn(0.65, 1.80),
        )
    }
}
