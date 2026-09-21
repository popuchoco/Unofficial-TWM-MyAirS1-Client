package com.kerberosclaw.myairs1

import org.junit.Assert.assertEquals
import org.junit.Test

class AirQualityTest {
    @Test fun officialPm25Boundaries() {
        assertEquals(Pm25Band.GOOD, Pm25Band.fromConcentration(15.4))
        assertEquals(Pm25Band.MODERATE, Pm25Band.fromConcentration(15.5))
        assertEquals(Pm25Band.SENSITIVE, Pm25Band.fromConcentration(35.5))
        assertEquals(Pm25Band.UNHEALTHY, Pm25Band.fromConcentration(54.5))
        assertEquals(Pm25Band.VERY_UNHEALTHY, Pm25Band.fromConcentration(150.5))
        assertEquals(Pm25Band.HAZARDOUS, Pm25Band.fromConcentration(250.5))
    }
}
