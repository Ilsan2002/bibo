package de.tobiasschuerg.weekview.util

import java.time.Duration
import java.time.LocalTime
import kotlin.math.roundToLong

/** Wall-clock coordinates inside one day; the exclusive numeric boundary can represent 24:00. */
data class TimeSpan(
    val start: LocalTime,
    val endExclusive: LocalTime,
    val endSecondExclusive: Double = endExclusive.toNanoOfDay() / 1e9,
) {
    val startSecond: Double get() = start.toNanoOfDay() / 1e9
    init {
        require(startSecond >= 0 && endSecondExclusive <= 86400.0 && startSecond < endSecondExclusive) {
            "A day segment must have positive duration within [00:00, 24:00]."
        }
    }
    val duration: Duration get() = Duration.ofNanos(((endSecondExclusive - startSecond) * 1e9).roundToLong())
    fun hourlyTimes(): Sequence<LocalTime> = ((startSecond / 3600).toInt()..minOf(23, (endSecondExclusive / 3600).toInt())).asSequence().map { LocalTime.of(it, 0) }
    val endLabel: String get() = if (endSecondExclusive == 86400.0) "24:00" else endExclusive.toString()
    companion object {
        fun fromSeconds(startSecond: Double, endSecondExclusive: Double): TimeSpan {
            require(startSecond.isFinite() && endSecondExclusive.isFinite() && startSecond >= 0 && startSecond < endSecondExclusive && endSecondExclusive <= 86400.0)
            val start = LocalTime.ofNanoOfDay((startSecond * 1e9).roundToLong().coerceIn(0, 86_399_999_999_999))
            val end = if (endSecondExclusive == 86400.0) LocalTime.MAX else LocalTime.ofNanoOfDay((endSecondExclusive * 1e9).roundToLong().coerceIn(0, 86_399_999_999_999))
            return TimeSpan(start, end, endSecondExclusive)
        }
        fun of(start: LocalTime, duration: Duration): TimeSpan {
            require(!duration.isNegative && !duration.isZero)
            return fromSeconds(start.toNanoOfDay() / 1e9, start.toNanoOfDay() / 1e9 + duration.toNanos() / 1e9)
        }
    }
}
