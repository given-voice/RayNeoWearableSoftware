package com.givenvoice.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwitchInputTest {
    @Test
    fun parsesEachButton() {
        assertEquals(SwitchButton.NEXT, SwitchInput.parse("BTN:1"))
        assertEquals(SwitchButton.SELECT, SwitchInput.parse("BTN:2"))
        assertEquals(SwitchButton.PREVIOUS, SwitchInput.parse("BTN:3"))
    }

    @Test
    fun acceptsEitherLineEnding() {
        assertEquals(SwitchButton.NEXT, SwitchInput.parse("BTN:1\r"))
        assertEquals(SwitchButton.PREVIOUS, SwitchInput.parse("BTN:3\r\n"))
    }

    @Test
    fun acceptsGarbageBeforeTheValue() {
        // The first line after connecting can start mid-way through boot output.
        assertEquals(SwitchButton.SELECT, SwitchInput.parse("\u0000x9BTN:2"))
    }

    @Test
    fun ignoresBootMessagesAndUnknownValues() {
        assertNull(SwitchInput.parse("rst:0x1 (POWERON_RESET),boot:0x13 (SPI_FAST_FLASH_BOOT)"))
        assertNull(SwitchInput.parse(""))
        assertNull(SwitchInput.parse("BTN:9"))
        assertNull(SwitchInput.parse("BTN:12"))
        assertNull(SwitchInput.parse("BTN:"))
    }
}
