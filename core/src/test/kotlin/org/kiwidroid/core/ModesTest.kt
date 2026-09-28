package org.kiwidroid.core

import kotlin.test.*

class ModesTest {
    @Test fun `every requested mode has valid defaults and the right wire name`() {
        assertEquals(setOf("am", "usb", "lsb", "cw", "sam", "sau", "sal", "sas", "iq", "cwn"),
            Mode.entries.map { it.wireName }.toSet())
        Mode.entries.forEach { mode ->
            val tuning = Tuning(mode = mode)
            assertEquals(mode.defaultPassband, tuning.passband)
            assertTrue(tuning.command().startsWith("SET mod=${mode.wireName} low_cut=${mode.lowCut} high_cut=${mode.highCut}"))
            assertEquals(if (mode in listOf(Mode.SAS, Mode.IQ)) 2 else 1, mode.channels)
        }
    }

    @Test fun `passband edits are independent for each mode and survive tuning steps`() {
        var tuning = Tuning().withPassband(Passband(-3500, 3500))
        tuning = tuning.copy(mode = Mode.USB).withPassband(Passband(200, 2400))
        tuning = tuning.copy(mode = Mode.SAL).withPassband(Passband(-4200, -100))
        tuning = tuning.step(100)
        assertEquals(Passband(-3500, 3500), tuning.copy(mode = Mode.AM).passband)
        assertEquals(Passband(200, 2400), tuning.copy(mode = Mode.USB).passband)
        assertEquals(Passband(-4200, -100), tuning.passband)
        assertEquals(Mode.SAU.defaultPassband, tuning.copy(mode = Mode.SAU).passband)
        assertEquals("SET mod=sal low_cut=-4200 high_cut=-100 freq=5000.100", tuning.command())
    }

    @Test fun `CW and CWN displayed frequency stays centered including half Hz cut midpoints`() {
        assertEquals("SET mod=cwn low_cut=470 high_cut=530 freq=334.500", Tuning(335000, Mode.CWN).command())
        assertEquals("SET mod=cw low_cut=600 high_cut=1000 freq=334.200", Tuning(335000, Mode.CW).command())
        val tuning = Tuning(335000, Mode.CWN).withPassband(Passband(600, 701))
        assertEquals("SET mod=cwn low_cut=600 high_cut=701 freq=334.3495", tuning.command())
        assertEquals(335000, tuning.frequencyHz)
        assertEquals(668699, tuning.radioFrequencyHalfHz)
    }

    @Test fun `bad cut order limits missing defaults and offset frequencies are rejected`() {
        for ((low, high) in listOf(500 to 500, 1000 to 500, -6001 to 5000, -5000 to 6001)) {
            assertFailsWith<IllegalArgumentException> { Passband(low, high) }
        }
        assertFailsWith<IllegalArgumentException> { Tuning(passbands = emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Tuning(499, Mode.CWN) }
        assertFailsWith<IllegalArgumentException> { Tuning(500, Mode.CWN).withPassband(Passband(500, 501)) }
        assertEquals(Passband(-6000, 6000), Passband(-6000, 6000))
    }
}
