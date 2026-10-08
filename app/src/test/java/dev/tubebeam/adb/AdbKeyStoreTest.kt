package dev.tubebeam.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdbKeyStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fingerprintPattern = Regex("^([0-9A-F]{2}:){15}[0-9A-F]{2}$")

    @Test
    fun generatesKeyAndStableFingerprint() {
        val store = AdbKeyStore(tmp.root)
        store.ensureReady()
        assertTrue(File(tmp.root, "adb/adbkey.pem").length() > 0)

        val fp1 = store.publicKeyFingerprintMd5()
        assertTrue("bad format: $fp1", fingerprintPattern.matches(fp1))
        assertEquals(fp1, store.publicKeyFingerprintMd5())

        // A second store over the same directory must load, not regenerate.
        assertEquals(fp1, AdbKeyStore(tmp.root).publicKeyFingerprintMd5())
    }

    @Test
    fun resetProducesNewKey() {
        val store = AdbKeyStore(tmp.root)
        val before = store.publicKeyFingerprintMd5()
        store.reset()
        assertNotEquals(before, store.publicKeyFingerprintMd5())
    }
}

class AdbKeyStoreFingerprintAgainstKadbTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The fingerprint must be the MD5 of exactly the base64 key Kadb puts on the wire. */
    @Test
    fun fingerprintMatchesKadbWireEncoding() {
        val store = AdbKeyStore(tmp.root)
        store.ensureReady()

        // Kadb's encoder is internal; reach it via reflection, the same way adbd will see the key.
        val keyPair = com.flyfishxu.kadb.cert.KadbCert::class.java
            .getDeclaredMethod("currentKeyPair\$Kadb_kadb").apply { isAccessible = true }
            .invoke(com.flyfishxu.kadb.cert.KadbCert)
        val publicKey = keyPair.javaClass.getMethod("getPublicKey").invoke(keyPair) as java.security.PublicKey
        val pubkeyClass = Class.forName("com.flyfishxu.kadb.cert.AndroidPubkey")
        val instance = pubkeyClass.getDeclaredField("INSTANCE").get(null)
        val encoded = pubkeyClass.getMethod("encode", java.security.interfaces.RSAPublicKey::class.java)
            .invoke(instance, publicKey) as ByteArray
        val base64 = java.util.Base64.getEncoder().encode(encoded)
        val expected = java.security.MessageDigest.getInstance("MD5").digest(base64)
            .joinToString(":") { "%02X".format(it) }

        assertEquals(expected, store.publicKeyFingerprintMd5())
    }
}
