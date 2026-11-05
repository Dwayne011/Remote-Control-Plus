package dev.atvremote.protocol.mrp

import dev.atvremote.protocol.plist.BinaryPlist

/**
 * MRP voice-input messages, as defined in pyatv's protobuf files
 * (RegisterVoiceInputDeviceMessage, SetRecordingStateMessage,
 * SendVoiceInputMessage). Remote Control Plus addition.
 *
 * pyatv defines these but never sends them, and no public client is known to
 * use them on current tvOS, so the values below follow the 2016 capture in
 * jeanregisser/mediaremotetv-protocol (Opus, 16 kHz, mono) and are
 * otherwise best guesses.
 */
object MrpVoice {
    const val TYPE_REGISTER_VOICE_INPUT_DEVICE = 28
    const val TYPE_REGISTER_VOICE_INPUT_DEVICE_RESPONSE = 29
    const val TYPE_SET_RECORDING_STATE = 30
    const val TYPE_SEND_VOICE_INPUT = 31

    const val EXT_REGISTER_VOICE_INPUT_DEVICE = 33
    const val EXT_REGISTER_VOICE_INPUT_DEVICE_RESPONSE = 34
    const val EXT_SET_RECORDING_STATE = 35
    const val EXT_SEND_VOICE_INPUT = 36

    const val RECORDING = 1L
    const val NOT_RECORDING = 2L

    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 320 // 20 ms

    /** Audio formats offered to the Apple TV. */
    enum class Format { OPUS, LPCM }

    /** kAudioFormatOpus 'opus' and kAudioFormatLinearPCM 'lpcm' as four-character codes. */
    private const val FORMAT_OPUS = 0x6F707573L
    private const val FORMAT_LPCM = 0x6C70636DL

    private fun formatSettings(format: Format): Protobuf.Builder {
        val settings = linkedMapOf<Any?, Any?>(
            "AVFormatIDKey" to if (format == Format.OPUS) FORMAT_OPUS else FORMAT_LPCM,
            "AVSampleRateKey" to SAMPLE_RATE.toDouble(),
            "AVNumberOfChannelsKey" to 1L,
        )
        if (format == Format.LPCM) {
            settings["AVLinearPCMBitDepthKey"] = 16L
            settings["AVLinearPCMIsFloatKey"] = false
            settings["AVLinearPCMIsBigEndianKey"] = false
            settings["AVLinearPCMIsNonInterleaved"] = false
        }
        return Protobuf.Builder().bytes(1, BinaryPlist.write(settings))
    }


    // --- Siri key over MRP (pyatv messages.py: send_hid_event / send_button) ---

    const val TYPE_SEND_HID_EVENT = 8
    const val EXT_SEND_HID_EVENT = 13
    const val TYPE_SEND_BUTTON_EVENT = 39
    const val EXT_SEND_BUTTON_EVENT = 43
    const val TYPE_GENERIC = 42

    /** Consumer page, usage 0x04 (microphone): pyatv's commented-out "mic" key. */
    const val SIRI_USAGE_PAGE = 12
    const val SIRI_USAGE = 0x04

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun uid() = java.util.UUID.randomUUID().toString().uppercase()

    /** IOHID keyboard event blob in the layout pyatv documents in SendHIDEventMessage.proto. */
    fun hidEvent(usagePage: Int, usage: Int, down: Boolean): ByteArray {
        val data = byteArrayOf(
            (usagePage shr 8).toByte(), usagePage.toByte(),
            (usage shr 8).toByte(), usage.toByte(),
            0, if (down) 1 else 0,
        )
        val blob = hex("438922cf08020000") +
            hex("0000000000000000010000000000000002000000200000000300000001000000000000") +
            data +
            hex("0000000000000001000000")
        return Protobuf.Builder()
            .varint(Mrp.FIELD_TYPE, TYPE_SEND_HID_EVENT.toLong())
            .message(EXT_SEND_HID_EVENT, Protobuf.Builder().bytes(1, blob))
            .string(Mrp.FIELD_UNIQUE_IDENTIFIER, uid())
            .build()
    }

    fun buttonEvent(usagePage: Int, usage: Int, down: Boolean): ByteArray = Protobuf.Builder()
        .varint(Mrp.FIELD_TYPE, TYPE_SEND_BUTTON_EVENT.toLong())
        .message(
            EXT_SEND_BUTTON_EVENT,
            Protobuf.Builder().varint(1, usagePage.toLong()).varint(2, usage.toLong()).bool(3, down),
        )
        .string(Mrp.FIELD_UNIQUE_IDENTIFIER, uid())
        .build()

    /** pyatv sends one of these after a key press as a flush. */
    fun generic(): ByteArray = Protobuf.Builder()
        .varint(Mrp.FIELD_TYPE, TYPE_GENERIC.toLong())
        .string(Mrp.FIELD_UNIQUE_IDENTIFIER, uid())
        .build()
    fun registerVoiceInputDevice(format: Format): ByteArray {
        // VoiceInputDeviceDescriptor { defaultFormat = 1; repeated supportedFormats = 2 }
        val descriptor = Protobuf.Builder()
            .message(1, formatSettings(format))
            .message(2, formatSettings(format))
        return Protobuf.Builder()
            .varint(Mrp.FIELD_TYPE, TYPE_REGISTER_VOICE_INPUT_DEVICE.toLong())
            .string(Mrp.FIELD_IDENTIFIER, uid())
            .message(
                EXT_REGISTER_VOICE_INPUT_DEVICE,
                Protobuf.Builder().message(1, descriptor),
            )
            .string(Mrp.FIELD_UNIQUE_IDENTIFIER, uid())
            .build()
    }

    fun setRecordingState(state: Long): ByteArray = Protobuf.Builder()
        .varint(Mrp.FIELD_TYPE, TYPE_SET_RECORDING_STATE.toLong())
        .message(EXT_SET_RECORDING_STATE, Protobuf.Builder().varint(1, state))
        .string(Mrp.FIELD_UNIQUE_IDENTIFIER, uid())
        .build()

    /** Experimental knobs for how audio is packed; see AppleTvController. */
    data class Packing(
        /** Packet description's variableFramesInPacket: 320, or 0 for "constant". */
        val variableFrames: Int = 0,
        /** Include formatSettings inside every AudioBuffer. */
        val formatInBuffer: Boolean = true,
        /** Use clock seconds for AudioTime.timestamp instead of a sample count. */
        val clockTimestamp: Boolean = false,
    )

    /**
     * Opus packets as SendVoiceInputMessage { dataBlock = 1 }:
     * AudioDataBlock { buffer = 1; time = 2; gain = 3 }, with one packet
     * description per packet. LPCM: one-frame packets, no descriptions.
     */
    fun sendVoiceInput(
        format: Format,
        packets: List<ByteArray>,
        sampleTime: Long,
        packing: Packing = Packing(),
    ): ByteArray {
        val buffer = Protobuf.Builder()
        if (packing.formatInBuffer) buffer.message(1, formatSettings(format))
        if (format == Format.OPUS) {
            val contents = packets.fold(ByteArray(0)) { acc, p -> acc + p }
            buffer.varint(2, packets.size.toLong())                       // packetCapacity
                .varint(3, packets.maxOf { it.size }.toLong())             // maximumPacketSize
                .varint(4, packets.size.toLong())                         // packetCount
                .bytes(5, contents)                                       // contents
            var offset = 0L
            for (p in packets) {
                buffer.message(
                    6,
                    Protobuf.Builder()
                        .varint(1, offset)                                // startOffset
                        .varint(2, packing.variableFrames.toLong())       // variableFramesInPacket
                        .varint(3, p.size.toLong()),                      // dataByteSize
                )
                offset += p.size
            }
        } else {
            val contents = packets.fold(ByteArray(0)) { acc, p -> acc + p }
            val frames = (contents.size / 2).toLong()
            buffer.varint(2, frames).varint(3, 2).varint(4, frames).bytes(5, contents)
        }
        val timestamp = if (packing.clockTimestamp) System.nanoTime() / 1e9 else sampleTime.toDouble()
        val time = Protobuf.Builder()
            .double(1, timestamp)
            .double(2, SAMPLE_RATE.toDouble())
        val block = Protobuf.Builder()
            .message(1, buffer)
            .message(2, time)
            .double(3, 1.0)
        return Protobuf.Builder()
            .varint(Mrp.FIELD_TYPE, TYPE_SEND_VOICE_INPUT.toLong())
            .message(EXT_SEND_VOICE_INPUT, Protobuf.Builder().message(1, block))
            .string(Mrp.FIELD_UNIQUE_IDENTIFIER, uid())
            .build()
    }
}
