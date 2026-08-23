package com.ayushkataria.bikeryde.fuel

/**
 * One fill-up entry. [pricePerLiter] and [mileageSinceLastKm] are derived at write time from the
 * previous entry rather than stored redundently by the user (design doc §5.4).
 */
data class FuelLog(
    val id: Long,
    val timestamp: Long,
    val odoKm: Double,
    val litersFilled: Double,
    val cost: Double,
    val pricePerLiter: Double,
    val mileageSinceLastKm: Double?,
    val notes: String?
)
