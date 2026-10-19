package com.reeltracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IgAccountTest {

    @Test fun `action bar title from ig 451 keeps the handle`() {
        assertEquals("i.m_.ayush", IgAccount.cleanUsername("  i.m_.ayush"))
    }

    @Test fun `decoration after the handle is dropped`() {
        assertEquals("some.one", IgAccount.cleanUsername("Some.One ▾"))
    }

    @Test fun `nothing usable is null`() {
        assertNull(IgAccount.cleanUsername("   "))
        assertNull(IgAccount.cleanUsername(null))
        assertNull(IgAccount.cleanUsername("a".repeat(31)))
    }

    @Test fun `full name is trimmed and blank becomes null`() {
        assertEquals("AYUSH", IgAccount.cleanFullName(" AYUSH "))
        assertNull(IgAccount.cleanFullName("  "))
    }
}
