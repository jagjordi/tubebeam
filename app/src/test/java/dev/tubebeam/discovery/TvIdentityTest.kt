package dev.tubebeam.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvIdentityTest {
    @Test fun prefersSerial() = assertEquals("serial:ABC123XYZ", TvIdentity.parse("ABC123XYZ\n1a2b3c4d5e6f7a8b\n"))
    @Test fun fallsBackToAndroidIdWhenSerialUnknown() =
        assertEquals("android_id:1a2b3c4d5e6f7a8b", TvIdentity.parse("unknown\n1a2b3c4d5e6f7a8b\n"))
    @Test fun nullWhenNothingUsable() = assertNull(TvIdentity.parse("unknown\nnull\n"))
    @Test fun nullOnEmpty() = assertNull(TvIdentity.parse(""))
    @Test fun ignoresSettingsError() = assertNull(TvIdentity.parse("0\nError: no such setting"))
    @Test fun displayStripsKind() = assertEquals("ABC", TvIdentity.display("serial:ABC"))
    @Test fun displayNull() = assertNull(TvIdentity.display(null))
}
