package dev.amrakshay.atvremote.protocol

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date

/** The client certificate the TV remembers, plus its private key. */
class ClientIdentity(val certificate: X509Certificate, val privateKey: PrivateKey) {

    fun certificatePem(): String = StringWriter().also { w ->
        JcaPEMWriter(w).use { it.writeObject(certificate) }
    }.toString()

    fun privateKeyPem(): String = StringWriter().also { w ->
        JcaPEMWriter(w).use { it.writeObject(privateKey) }
    }.toString()

    companion object {
        /**
         * Mirrors python-androidtvremote2: RSA-2048, CN=atvremote, SAN atvremote,
         * serial 1000, ten years, SHA-256. The TV stores the public key at pairing
         * and recognises this certificate on every later connection.
         */
        fun generate(hostname: String = "atvremote"): ClientIdentity {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=$hostname")
            val from = Date()
            val to = Date(from.time + 3650L * 24 * 60 * 60 * 1000)

            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(1000), from, to, name, keyPair.public,
            )
            builder.addExtension(Extension.basicConstraints, false, BasicConstraints(0))
            builder.addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.dNSName, hostname)),
            )

            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return ClientIdentity(certificate, keyPair.private)
        }

        fun fromPem(certificatePem: String, privateKeyPem: String): ClientIdentity {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(certificatePem.byteInputStream()) as X509Certificate
            val parsed = PEMParser(StringReader(privateKeyPem)).readObject()
            val converter = JcaPEMKeyConverter()
            val privateKey = when (parsed) {
                is PEMKeyPair -> converter.getKeyPair(parsed).private
                is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> converter.getPrivateKey(parsed)
                else -> error("unsupported private key format: ${parsed?.javaClass}")
            }
            return ClientIdentity(certificate, privateKey)
        }
    }
}

data class PairingHash(val digest: ByteArray, val checksumOk: Boolean) {
    override fun equals(other: Any?) = other is PairingHash &&
        digest.contentEquals(other.digest) && checksumOk == other.checksumOk

    override fun hashCode() = digest.contentHashCode() * 31 + checksumOk.hashCode()
}

/**
 * Big-endian, minimal length, left-padded to a whole number of bytes.
 *
 * This exact encoding is what the pairing hash is defined over. Note that
 * [BigInteger.toByteArray] is NOT equivalent: it prepends a zero sign byte when
 * the high bit is set, which silently produces the wrong digest.
 */
fun bigIntToBytes(value: BigInteger): ByteArray {
    var hex = value.toString(16).uppercase()
    if (hex.length % 2 != 0) hex = "0$hex"
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

/**
 * SHA-256 over the client's modulus and exponent, the TV's modulus and exponent,
 * and the nonce — the last four characters of the code. The first two characters
 * are a checksum over that digest, so a mistyped code is caught locally rather
 * than by the TV, which only ever answers "no".
 */
fun computePairingHash(
    clientKey: RSAPublicKey,
    serverKey: RSAPublicKey,
    code: String,
): PairingHash {
    require(code.length == 6) { "pairing code must be 6 characters" }
    val normalized = code.uppercase()
    val nonceHex = normalized.substring(2)
    val nonce = ByteArray(nonceHex.length / 2) { i ->
        nonceHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    val digest = MessageDigest.getInstance("SHA-256").run {
        update(bigIntToBytes(clientKey.modulus))
        update(bigIntToBytes(clientKey.publicExponent))
        update(bigIntToBytes(serverKey.modulus))
        update(bigIntToBytes(serverKey.publicExponent))
        update(nonce)
        digest()
    }

    val expected = normalized.substring(0, 2).toInt(16)
    return PairingHash(digest, (digest[0].toInt() and 0xFF) == expected)
}
