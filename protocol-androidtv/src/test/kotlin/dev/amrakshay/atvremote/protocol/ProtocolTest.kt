package dev.amrakshay.atvremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

class FramingTest {

    private fun message(size: Int) = ByteArray(size) { 0x41 }

    @Test
    fun `one whole message`() {
        val r = FrameReader()
        assertEquals(1, r.add(FrameReader.frame(message(5))).size)
    }

    @Test
    fun `several messages in one read`() {
        val r = FrameReader()
        val wire = FrameReader.frame(message(3)) + FrameReader.frame(message(4))
        assertEquals(2, r.add(wire).size)
    }

    @Test
    fun `message split across reads`() {
        val r = FrameReader()
        val wire = FrameReader.frame(message(10))
        assertTrue(r.add(wire.copyOfRange(0, 4)).isEmpty())
        assertEquals(1, r.add(wire.copyOfRange(4, wire.size)).size)
    }

    @Test
    fun `split inside a multi-byte varint`() {
        val r = FrameReader()
        // 300 bytes needs a two-byte varint; cut between the two.
        val wire = FrameReader.frame(message(300))
        assertTrue(r.add(wire.copyOfRange(0, 1)).isEmpty())
        val out = r.add(wire.copyOfRange(1, wire.size))
        assertEquals(1, out.size)
        assertEquals(300, out.first().size)
    }

    @Test
    fun `byte at a time delivery`() {
        val r = FrameReader()
        val wire = FrameReader.frame(message(200))
        var got = 0
        for (b in wire) got += r.add(byteArrayOf(b)).size
        assertEquals(1, got)
    }

    @Test
    fun `trailing partial message is kept for the next read`() {
        val r = FrameReader()
        val wire = FrameReader.frame(message(3)) + FrameReader.frame(message(9))
        val first = r.add(wire.copyOfRange(0, 8))
        assertEquals(1, first.size)
        assertEquals(1, r.add(wire.copyOfRange(8, wire.size)).size)
    }
}

class PairingHashTest {

    private fun publicKey(resource: String): RSAPublicKey {
        val pem = checkNotNull(javaClass.getResourceAsStream(resource)) { "missing $resource" }
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(pem) as X509Certificate
        return certificate.publicKey as RSAPublicKey
    }

    private val clientKey by lazy { publicKey("/client.pem") }
    private val serverKey by lazy { publicKey("/server.pem") }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /**
     * Vectors produced by python-androidtvremote2 over these same two
     * certificates. The Python implementation is the known-good oracle: any
     * disagreement here means this implementation is wrong, and it is settled
     * offline rather than against a TV.
     */
    private val vectors = mapOf(
        "00ABCD" to "29676ead55845d831ce82161b67d1901665412c67e51a109521632de42e5d550",
        "FF1234" to "f5c755b693112808a61e5f8ceec19ad921c6a320b32a73a95fc84a1ac47f762b",
        "0A0000" to "958dfadc2df327a0a47016095858db38a85e721382e35c8aca6b64109dde72e6",
    )

    @Test
    fun `matches the python reference`() {
        for ((code, expected) in vectors) {
            val actual = computePairingHash(clientKey, serverKey, code).digest.hex()
            assertEquals("code $code", expected, actual)
        }
    }

    @Test
    fun `lowercase code is accepted`() {
        assertEquals(
            vectors.getValue("FF1234"),
            computePairingHash(clientKey, serverKey, "ff1234").digest.hex(),
        )
    }

    @Test
    fun `checksum byte is validated`() {
        // The digest for ...ABCD starts with 0x29, so a code claiming 29 is consistent.
        assertTrue(computePairingHash(clientKey, serverKey, "29ABCD").checksumOk)
        assertFalse(computePairingHash(clientKey, serverKey, "00ABCD").checksumOk)
    }

    @Test
    fun `exponent encodes without a sign byte`() {
        // 65537 must hash as 01 00 01 — BigInteger.toByteArray would be right here
        // but wrong for the modulus, so both go through the same helper.
        assertEquals("010001", bigIntToBytes(java.math.BigInteger.valueOf(65537)).hex())
        assertEquals("ff", bigIntToBytes(java.math.BigInteger.valueOf(255)).hex())
    }
}

class CertificateTest {

    @Test
    fun `generated certificate has the shape the TV expects`() {
        val identity = ClientIdentity.generate()
        val key = identity.certificate.publicKey as RSAPublicKey
        assertEquals(2048, key.modulus.bitLength())
        assertEquals(java.math.BigInteger.valueOf(65537), key.publicExponent)
        assertEquals("CN=atvremote", identity.certificate.subjectX500Principal.name)
        assertEquals(
            identity.certificate.subjectX500Principal,
            identity.certificate.issuerX500Principal,
        )
        // basicConstraints CA:TRUE pathlen:0, as the reference implementation sets.
        assertEquals(0, identity.certificate.basicConstraints)
    }

    @Test
    fun `identity survives a PEM round trip`() {
        val original = ClientIdentity.generate()
        val restored = ClientIdentity.fromPem(original.certificatePem(), original.privateKeyPem())
        assertEquals(original.certificate, restored.certificate)
        assertEquals(original.privateKey, restored.privateKey)
    }
}
