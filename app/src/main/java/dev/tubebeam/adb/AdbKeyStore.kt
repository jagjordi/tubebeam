package dev.tubebeam.adb

import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toPath
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Owns the phone's ADB identity: one RSA-2048 key pair, generated on first use and stored as a
 * PKCS#8 PEM in app-private storage. Kadb reads it through [KadbCert], which is process-global,
 * so this class is a singleton held by AppContainer.
 *
 * Never log [privateKeyPem] or anything derived from it except the public fingerprint.
 */
class AdbKeyStore(baseDir: File) {
    private val dir = File(baseDir, "adb")
    private val keyFile = File(dir, "adbkey.pem")

    init {
        KadbCert.configure(
            store = OkioFilePrivateKeyStore(keyFile.absolutePath.toPath()),
            policy = KadbCertPolicy(
                keySizeBits = 2048,
                subject = KadbCertPolicy.Subject(cn = "TubeBeam", o = "TubeBeam"),
            ),
        )
    }

    /** Loads the key pair, generating and persisting a new one if none exists. Blocking; call on IO. */
    fun ensureReady() {
        dir.mkdirs()
        KadbCert.ensureReady()
    }

    /** Deletes the key; every TV will need to trust the phone again. */
    fun reset() {
        KadbCert.clear()
        ensureReady()
    }

    /**
     * The fingerprint Android shows in its "Allow debugging?" dialog: the MD5 of the
     * base64-encoded public key in ADB's own wire format, as colon-separated hex.
     */
    fun publicKeyFingerprintMd5(): String {
        ensureReady()
        val pem = KadbCert.exportPrivateKeyOrNull() ?: error("ADB key missing after ensureReady()")
        val key = parsePkcs8(pem)
        val adbPubkey = Base64.getEncoder().encode(encodeAndroidPubkey(key.modulus, key.publicExponent))
        val md5 = MessageDigest.getInstance("MD5").digest(adbPubkey)
        return md5.joinToString(":") { "%02X".format(it) }
    }

    private fun parsePkcs8(pem: ByteArray): RSAPrivateCrtKey {
        val body = pem.decodeToString()
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .trim()
        val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(body))
        return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateCrtKey
    }

    /** Mirrors libcrypto_utils' android_pubkey_encode(): the struct adbd hashes for the prompt. */
    private fun encodeAndroidPubkey(modulus: BigInteger, exponent: BigInteger): ByteArray {
        val words = MODULUS_BYTES / 4
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(modulus.mod(r32).modInverse(r32))
        val r = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8)
        val rr = r.multiply(r).mod(modulus)
        return ByteBuffer.allocate(3 * 4 + 2 * MODULUS_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(words)
            .putInt(n0inv.toInt())
            .put(toLittleEndianPadded(modulus))
            .put(toLittleEndianPadded(rr))
            .putInt(exponent.toInt())
            .array()
    }

    private fun toLittleEndianPadded(value: BigInteger): ByteArray {
        val bigEndian = value.toByteArray().let { if (it.size > MODULUS_BYTES && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        require(bigEndian.size <= MODULUS_BYTES) { "value does not fit in $MODULUS_BYTES bytes" }
        val out = ByteArray(MODULUS_BYTES)
        for (i in bigEndian.indices) out[i] = bigEndian[bigEndian.size - 1 - i]
        return out
    }

    private companion object {
        const val MODULUS_BYTES = 256
    }
}
