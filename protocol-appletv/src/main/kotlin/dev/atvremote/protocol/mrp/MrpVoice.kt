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

    /** kAudioFormatOpus, the four-character code 'opus'. */
    private const val FORMAT_OPUS = 0x6F707573L

    /** AudioFormatSettings { formatSettingsPlistData = 1 } */
    private fun formatSettings(): Protobuf.Builder = Protobuf.Builder().bytes(
        1,
        BinaryPlist.write(
            linkedMapOf<Any?, Any?>(
                "AVFormatIDKey" to FORMAT_OPUS,
                "AVSampleRateKey" to SAMPLE_RATE.toDouble(),
                "AVNumberOfChannelsKey" to 1L,
            ),
        ),
    )

    fun registerVoiceInputDevice(): ByteArray {
        // VoiceInputDeviceDescriptor { defaultFormat = 1; repeated supportedFormats = 2 }
        val descriptor = Protobuf.Builder()
            .message(1, formatSettings())
            .message(2, formatSettings())
        return Protobuf.Builder()
            .varint(Mrp.FIELD_TYPE, TYPE_REGISTER_VOICE_INPUT_DEVICE.toLong())
            .string(Mrp.FIELD_IDENTIFIER, "register-voice")
            .message(
                EXT_REGISTER_VOICE_INPUT_DEVICE,
                Protobuf.Builder().message(1, descriptor),
            )
            .build()
    }

    fun setRecordingState(state: Long): ByteArray = Protobuf.Builder()
        .varint(Mrp.FIELD_TYPE, TYPE_SET_RECORDING_STATE.toLong())
        .message(EXT_SET_RECORDING_STATE, Protobuf.Builder().varint(1, state))
        .build()

    /**
     * One Opus packet (20 ms) as SendVoiceInputMessage { dataBlock = 1 }:
     * AudioDataBlock { buffer = 1; time = 2; gain = 3 }.
     */
    fun sendVoiceInput(packet: ByteArray, sampleTime: Long): ByteArray {
        val description = Protobuf.Builder()
            .varint(1, 0)                          // startOffset
            .varint(2, FRAME_SAMPLES.toLong())     // variableFramesInPacket
            .varint(3, packet.size.toLong())       // dataByteSize
        val buffer = Protobuf.Builder()
            .message(1, formatSettings())          // formatSettings
            .varint(2, 1)                          // packetCapacity
            .varint(3, packet.size.toLong())       // maximumPacketSize
            .varint(4, 1)                          // packetCount
            .bytes(5, packet)                      // contents
            .message(6, description)               // packetDescriptions
        val time = Protobuf.Builder()
            .double(1, sampleTime.toDouble())      // timestamp, in samples
            .double(2, SAMPLE_RATE.toDouble())     // sampleRate
        val block = Protobuf.Builder()
            .message(1, buffer)
            .message(2, time)
            .double(3, 1.0)                        // gain
        return Protobuf.Builder()
            .varint(Mrp.FIELD_TYPE, TYPE_SEND_VOICE_INPUT.toLong())
            .message(EXT_SEND_VOICE_INPUT, Protobuf.Builder().message(1, block))
            .build()
    }
}
