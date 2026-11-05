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

    /**
     * One 20 ms block as SendVoiceInputMessage { dataBlock = 1 }:
     * AudioDataBlock { buffer = 1; time = 2; gain = 3 }.
     * Opus: one packet with a packet description. LPCM: 320 one-frame
     * packets of 2 bytes, no descriptions (constant-size format).
     */
    fun sendVoiceInput(format: Format, contents: ByteArray, sampleTime: Long): ByteArray {
        val buffer = Protobuf.Builder().message(1, formatSettings(format))
        if (format == Format.OPUS) {
            val description = Protobuf.Builder()
                .varint(1, 0)                          // startOffset
                .varint(2, FRAME_SAMPLES.toLong())     // variableFramesInPacket
                .varint(3, contents.size.toLong())     // dataByteSize
            buffer.varint(2, 1)                        // packetCapacity
                .varint(3, contents.size.toLong())     // maximumPacketSize
                .varint(4, 1)                          // packetCount
                .bytes(5, contents)                    // contents
                .message(6, description)               // packetDescriptions
        } else {
            val frames = (contents.size / 2).toLong()
            buffer.varint(2, frames)                   // packetCapacity
                .varint(3, 2)                          // maximumPacketSize
                .varint(4, frames)                     // packetCount
                .bytes(5, contents)                    // contents
        }
        val time = Protobuf.Builder()
            .double(1, sampleTime.toDouble())          // timestamp, in samples
            .double(2, SAMPLE_RATE.toDouble())         // sampleRate
        val block = Protobuf.Builder()
            .message(1, buffer)
            .message(2, time)
            .double(3, 1.0)                            // gain
        return Protobuf.Builder()
            .varint(Mrp.FIELD_TYPE, TYPE_SEND_VOICE_INPUT.toLong())
            .message(EXT_SEND_VOICE_INPUT, Protobuf.Builder().message(1, block))
            .string(Mrp.FIELD_UNIQUE_IDENTIFIER, uid())
            .build()
    }
}
