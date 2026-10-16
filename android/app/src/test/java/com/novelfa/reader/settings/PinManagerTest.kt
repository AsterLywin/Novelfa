package com.novelfa.reader.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task 8 RED: PinManager (port of SettingsService.cs HashPin/HasPin/SetPin/
 * VerifyPin + PinWindow.xaml.cs set/verify/change flow).
 * Manager + prefs are suspend (never block main); tests drive them with
 * runBlocking on the test thread.
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
    fun noPin_hasPinFalse_verifyFalse() = runBlocking {
        val m = manager()
        assertFalse(m.hasPin())
        assertFalse(m.verify("1234"))
    }

    @Test
    fun setPin_verifyRoundTrip() = runBlocking {
        val m = manager()
        m.setPin("1234")
        assertTrue(m.hasPin())
        assertTrue(m.verify("1234"))
        assertFalse(m.verify("0000"))
    }

    @Test
    fun setPin_shortPin_throwsFaMessage() = runBlocking {
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
    fun changePin_requiresOldPin() = runBlocking {
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
    fun clearPin_removesPin() = runBlocking {
        val m = manager()
        m.setPin("1234")
        assertTrue(m.hasPin())
        m.clearPin()
        assertFalse(m.hasPin())
        assertFalse(m.verify("1234"))
    }

    @Test
    fun pin_hashedRaw_likeCSharp_noTrim() = runBlocking {
        // C# HashPin hashes the raw input (SettingsService.cs: SetPin/VerifyPin
        // do not trim); a padded PIN must NOT verify as the bare PIN.
        val m = manager()
        m.setPin(" 1234 ")
        assertTrue(m.verify(" 1234 "))
        assertFalse(m.verify("1234"))
    }

    // v2 salted PBKDF2 scheme (new installs / setPin output).

    @Test
    fun secureHash_hasV2Prefix_andVerifies() {
        val h = PinManager.hashPinSecure("1234")
        assertTrue(h.startsWith("v2$"))
        assertTrue(PinManager.verifyHash("1234", h))
        assertFalse(PinManager.verifyHash("0000", h))
    }

    @Test
    fun secureHash_randomSalt_eachHashDiffers() {
        assertFalse(PinManager.hashPinSecure("1234") == PinManager.hashPinSecure("1234"))
    }

    @Test
    fun secureHash_malformed_rejected() {
        assertFalse(PinManager.verifyHash("1234", "v2$broken"))
        assertFalse(PinManager.verifyHash("1234", "v2$120000$!!!$???"))
    }

    @Test
    fun setPin_writesV2_verifyRoundTrip() = runBlocking {
        val prefs = FakePinPrefs()
        val m = PinManager(prefs)
        m.setPin("1234")
        val stored = prefs.hash!!
        assertTrue(stored.startsWith("v2$"))
        assertTrue(m.verify("1234"))
        assertFalse(m.verify("0000"))
    }

    @Test
    fun legacyHash_verifiesAndUpgradesToV2() = runBlocking {
        val prefs = FakePinPrefs()
        // Simulate a hash written by an older install (unsalted C# parity).
        prefs.hash = PinManager.hashPin("1234")
        val m = PinManager(prefs)
        assertTrue(m.verify("1234"))
        // Transparent upgrade on successful unlock.
        val upgraded = prefs.hash!!
        assertTrue(upgraded.startsWith("v2$"))
        assertTrue(m.verify("1234"))
        assertFalse(m.verify("0000"))
    }
}
