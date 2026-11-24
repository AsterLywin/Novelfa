package com.novelfa.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartTest {

    @Test
    fun eyeCareLayers_off() {
        assertEquals(0f to 0f, eyeCareLayers(0, 100))
    }

    @Test
    fun eyeCareLayers_fullWarmth() {
        // Warmth 60 at full brightness: warm alpha 0.26, no dim.
        val (warm, dim) = eyeCareLayers(60, 100)
        assertEquals(0.26f, warm, 0.001f)
        assertEquals(0f, dim, 0.001f)
    }

    @Test
    fun eyeCareLayers_darkest() {
        // Warmth 0, brightness 45: dim (100-45)/100*0.65 = 0.3575.
        val (warm, dim) = eyeCareLayers(0, 45)
        assertEquals(0f, warm, 0.001f)
        assertEquals(0.3575f, dim, 0.001f)
    }

    @Test
    fun eyeCareLayers_mixed() {
        // Warmth 30, brightness 70: warm 0.13, dim 0.195.
        val (warm, dim) = eyeCareLayers(30, 70)
        assertEquals(0.13f, warm, 0.001f)
        assertEquals(0.195f, dim, 0.001f)
    }

    @Test
    fun remainingMinutes_roundsUp() {
        // 420 words left at 210 wpm = exactly 2 min; 421 -> 3.
        assertEquals(2, remainingMinutes(420, 0f, 210))
        assertEquals(3, remainingMinutes(421, 0f, 210))
        // Half the chapter read: 210 of 420 left -> 1 min.
        assertEquals(1, remainingMinutes(420, 0.5f, 210))
        // Nothing left, or no words: 0.
        assertEquals(0, remainingMinutes(420, 1f, 210))
        assertEquals(0, remainingMinutes(0, 0f, 210))
    }

    @Test
    fun autoScrollStepDelta_minimum40() {
        assertEquals(270, autoScrollStepDelta(600, 45))
        assertEquals(40, autoScrollStepDelta(100, 10))
        assertEquals(600, autoScrollStepDelta(600, 100))
    }

    @Test
    fun smoothPageIntervalSec() {
        // 45 px/s -> 60/45 = 1.33 s; 240 px/s -> clamped to 1 s.
        assertEquals(60.0 / 45.0, smoothPageIntervalSec(45), 0.001)
        assertEquals(1.0, smoothPageIntervalSec(240), 0.001)
        assertEquals(6.0, smoothPageIntervalSec(10), 0.001)
    }
}
