package com.novelfa.reader.library

/**
 * Gating stub for the private tab. Task 8 provides the real PinManager;
 * private-tab gating here depends ONLY on [hasPin]. Default is locked-off.
 */
fun interface PinGate {
    fun hasPin(): Boolean
}

object NoPinGate : PinGate {
    override fun hasPin(): Boolean = false
}
