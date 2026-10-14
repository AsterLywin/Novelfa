package com.novelfa.reader.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task 8 RED: PinManager (port of SettingsService.cs HashPin/HasPin/SetPin/
 * VerifyPin + PinWindow.xaml.cs set/verify/change flow).
 */
class PinManagerTest {

    // Verified BEFORE writing the test with:
    //   $b=[Text.Encoding]::UTF8.GetBytes('1234'); ([Security.Cryptography.SHA256]::Create().ComputeHash($b) | % { $_.ToString('x2') }) -join ''
    // (PowerShell equivalent of: printf '1234' | sha256sum).
    private val pin1234Hash = "03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4"

    private fun manager() = PinManager(FakePinPrefs())

    @Test
    fun hashPin_matchesCSharpX2Vector() {
        assertEquals(pin1234Hash, PinManager.hashPin("1234"))
    }

    @Test
    fun noPin_hasPinFalse_verifyFalse() {
        val m = manager()
        assertFalse(m.hasPin())
        assertFalse(m.verify("1234"))
    }

    @Test
    fun setPin_verifyRoundTrip() {
        val m = manager()
        m.setPin("1234")
        assertTrue(m.hasPin())
        assertTrue(m.verify("1234"))
        assertFalse(m.verify("0000"))
    }

    @Test
    fun setPin_shortPin_throwsFaMessage() {
        val m = manager()
        try {
            m.setPin("12")
            fail("expected PinException")
        } catch (e: PinException) {
            assertEquals("پین باید حداقل ۴ کاراکتر باشه", e.message)
        }
        assertFalse(m.hasPin())
    }

    @Test
    fun changePin_requiresOldPin() {
        val m = manager()
        m.setPin("1234")
        try {
            m.changePin("0000", "5678")
            fail("expected PinException")
        } catch (e: PinException) {
            assertEquals("پین اشتباهه", e.message)
        }
        assertTrue(m.verify("1234"))
        m.changePin("1234", "5678")
        assertTrue(m.verify("5678"))
        assertFalse(m.verify("1234"))
    }

    @Test
    fun clearPin_removesPin() {
        val m = manager()
        m.setPin("1234")
        assertTrue(m.hasPin())
        m.clearPin()
        assertFalse(m.hasPin())
        assertFalse(m.verify("1234"))
    }
}
