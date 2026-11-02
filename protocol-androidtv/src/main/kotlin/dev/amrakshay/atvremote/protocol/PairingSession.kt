package dev.amrakshay.atvremote.protocol

import com.google.polo.wire.protobuf.PoloProto.Configuration
import com.google.polo.wire.protobuf.PoloProto.Options
import com.google.polo.wire.protobuf.PoloProto.OuterMessage
import com.google.polo.wire.protobuf.PoloProto.PairingRequest
import com.google.polo.wire.protobuf.PoloProto.Secret
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class PairingFailed(message: String) : Exception(message)
class InvalidPairingCode(message: String) : Exception(message)

/**
 * Port 6467. Run once per TV; afterwards the TV recognises the certificate and
 * the remote session needs no user interaction ever again.
 *
 * The handshake is strictly request/response, so this is written as a plain
 * blocking sequence rather than a callback machine.
 */
class PairingSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val clientName: String = "Android Remote",
) : Closeable {

    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val reader = FrameReader()
    private var serverKey: RSAPublicKey? = null

    /**
     * Connects and walks the handshake until the TV puts a code on screen.
     * Returns when the code is displayed.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        val s = connectTls(host, PAIRING_PORT, identity)
        socket = s
        input = s.inputStream
        output = s.outputStream
        serverKey = (s.session.peerCertificates.first() as java.security.cert.X509Certificate)
            .publicKey as RSAPublicKey

        send(
            outer().setPairingRequest(
                PairingRequest.newBuilder()
                    .setClientName(clientName)
                    .setServiceName("atvremote"),
            ),
        )

        while (true) {
            val message = receive()
            if (message.status != OuterMessage.Status.STATUS_OK) {
                throw PairingFailed("TV returned status ${message.status}")
            }
            when {
                message.hasPairingRequestAck() -> send(
                    outer().setOptions(
                        Options.newBuilder()
                            .setPreferredRole(Options.RoleType.ROLE_TYPE_INPUT)
                            .addInputEncodings(hexEncoding()),
                    ),
                )

                message.hasOptions() -> send(
                    outer().setConfiguration(
                        Configuration.newBuilder()
                            .setClientRole(Options.RoleType.ROLE_TYPE_INPUT)
                            .setEncoding(hexEncoding()),
                    ),
                )

                // The TV is now showing the code.
                message.hasConfigurationAck() -> return@withContext

                else -> throw PairingFailed("unexpected pairing message")
            }
        }
    }

    /**
     * Submits the code shown on the TV. The code's own checksum is verified
     * first, so a typo fails here instead of as an opaque rejection.
     */
    suspend fun finish(code: String) = withContext(Dispatchers.IO) {
        val trimmed = code.trim()
        if (trimmed.length != 6) throw InvalidPairingCode("the code is 6 characters")
        if (!trimmed.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
            throw InvalidPairingCode("the code is letters A-F and digits only")
        }

        val hash = computePairingHash(
            clientKey = identity.certificate.publicKey as RSAPublicKey,
            serverKey = checkNotNull(serverKey) { "start() has not run" },
            code = trimmed,
        )
        if (!hash.checksumOk) throw InvalidPairingCode("that code does not look right")

        send(outer().setSecret(Secret.newBuilder().setSecret(ByteString.copyFrom(hash.digest))))

        val reply = receive()
        if (reply.status != OuterMessage.Status.STATUS_OK || !reply.hasSecretAck()) {
            throw PairingFailed("the TV rejected the code")
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun hexEncoding() = Options.Encoding.newBuilder()
        .setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
        .setSymbolLength(6)

    private fun outer() = OuterMessage.newBuilder()
        .setProtocolVersion(2)
        .setStatus(OuterMessage.Status.STATUS_OK)

    private fun send(builder: OuterMessage.Builder) {
        val out = checkNotNull(output) { "not connected" }
        out.write(FrameReader.frame(builder.build().toByteArray()))
        out.flush()
    }

    private fun receive(): OuterMessage {
        val stream = checkNotNull(input) { "not connected" }
        val buffer = ByteArray(8192)
        while (true) {
            // Messages arrive one at a time here, but the framing still has to
            // cope with a body split across reads.
            val read = stream.read(buffer)
            if (read < 0) throw PairingFailed("the TV closed the connection")
            val frames = reader.add(buffer, read)
            if (frames.isNotEmpty()) return OuterMessage.parseFrom(frames.first())
        }
    }
}
