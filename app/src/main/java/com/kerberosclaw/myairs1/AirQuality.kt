package com.kerberosclaw.myairs1

enum class Pm25Band(val label: String, val max: Double, val backgroundArgb: Long, val darkText: Boolean) {
    GOOD("良好", 15.4, 0xFF00E400, true),
    MODERATE("普通", 35.4, 0xFFFFFF00, true),
    SENSITIVE("對敏感族群不健康", 54.4, 0xFFFF7E00, true),
    UNHEALTHY("對所有族群不健康", 150.4, 0xFFFF0000, false),
    VERY_UNHEALTHY("非常不健康", 250.4, 0xFF8F3F97, false),
    HAZARDOUS("危害", Double.POSITIVE_INFINITY, 0xFF7E0023, false);

    companion object {
        fun fromConcentration(pm25: Double): Pm25Band = entries.first { pm25 <= it.max }
    }
}
