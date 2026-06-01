package com.industrialble.protocol

import java.io.ByteArrayOutputStream

/**
 * Constructor y parser de tramas binarias del protocolo propietario.
 *
 * Estructura de trama:
 *   [Header (2 bytes)] [Payload (Variable)] [Checksum CRC16 (2 bytes)]
 *
 * Header:
 *   [Command ID (1 byte)] [Payload Length (1 byte)]
 *
 * Máximo payload: 255 bytes (limitado por el campo Length de 1 byte).
 */
object ProtocolFrameBuilder {

    // Offset constants
    const val HEADER_SIZE = 2
    const val CHECKSUM_SIZE = 2
    const val MIN_FRAME_SIZE = HEADER_SIZE + CHECKSUM_SIZE          // 4 bytes
    const val MAX_PAYLOAD_SIZE = 0xFF                                 // 255 bytes
    const val MAX_FRAME_SIZE = HEADER_SIZE + MAX_PAYLOAD_SIZE + CHECKSUM_SIZE

    data class ParsedFrame(
        val commandId: Int,           // 0..255
        val payload: ByteArray,
        val rawData: ByteArray
    )

    // ────────────────────────────────────────────────────────────
    // CONSTRUCCIÓN
    // ────────────────────────────────────────────────────────────
    fun buildDataFrame(commandId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(commandId in 0..0xFF) { "commandId debe estar entre 0 y 255" }
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "Payload no puede exceder $MAX_PAYLOAD_SIZE bytes (recibidos ${payload.size})"
        }

        val header = byteArrayOf(commandId.toByte(), payload.size.toByte())

        // Calcular CRC16-CCITT sobre header + payload
        val dataForCrc = ByteArrayOutputStream().apply {
            write(header)
            write(payload)
        }.toByteArray()

        val checksum = calculateCRC16CCITT(dataForCrc)

        return ByteArrayOutputStream().apply {
            write(header)
            write(payload)
            write(checksum.toByteArrayLE())
        }.toByteArray()
    }

    // ────────────────────────────────────────────────────────────
    // PARSEO
    // ────────────────────────────────────────────────────────────
    fun parseFrame(rawData: ByteArray): ParsedFrame {
        require(rawData.size >= MIN_FRAME_SIZE) {
            "Trama demasiado corta: ${rawData.size} bytes (mínimo $MIN_FRAME_SIZE)"
        }

        val commandId = rawData[0].toInt() and 0xFF
        val payloadLength = rawData[1].toInt() and 0xFF

        val expectedTotalSize = HEADER_SIZE + payloadLength + CHECKSUM_SIZE
        require(rawData.size >= expectedTotalSize) {
            "Trama incompleta: esperaba $expectedTotalSize bytes, recibí ${rawData.size}"
        }

        val payload = rawData.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLength)
        val receivedChecksum = rawData.readUInt16LE(HEADER_SIZE + payloadLength)
        val dataForCrc = rawData.copyOfRange(0, HEADER_SIZE + payloadLength)
        val expectedChecksum = calculateCRC16CCITT(dataForCrc)

        require(receivedChecksum == expectedChecksum) {
            "Checksum inválido: recibido 0x${receivedChecksum.toString(16).padStart(4, '0')}, " +
                    "esperado 0x${expectedChecksum.toString(16).padStart(4, '0')}"
        }

        return ParsedFrame(
            commandId = commandId,
            payload = payload,
            rawData = rawData.copyOfRange(0, expectedTotalSize)
        )
    }

    /**
     * Escanea un buffer en busca de una trama válida a partir de un offset.
     * Útil para reassembly cuando los datos llegan fragmentados.
     * @return ParsedFrame si se encuentra una trama válida, null si no.
     */
    fun tryParseFromBuffer(buffer: ByteArray, offset: Int = 0): ParsedFrame? {
        if (buffer.size - offset < MIN_FRAME_SIZE) return null

        val payloadLength = buffer[offset + 1].toInt() and 0xFF
        val frameSize = HEADER_SIZE + payloadLength + CHECKSUM_SIZE

        if (buffer.size - offset < frameSize) return null

        return try {
            parseFrame(buffer.copyOfRange(offset, offset + frameSize))
        } catch (_: Exception) {
            null
        }
    }

    // ────────────────────────────────────────────────────────────
    // CRC16-CCITT CON LOOKUP TABLE
    // ────────────────────────────────────────────────────────────
    private val crcTable = IntArray(256).also { table ->
        val polynomial = 0x1021
        for (i in 0..255) {
            var crc = i shl 8
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor polynomial else crc shl 1
            }
            table[i] = crc and 0xFFFF
        }
    }

    fun calculateCRC16CCITT(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            val tableIndex = ((crc shr 8) xor (byte.toInt() and 0xFF)) and 0xFF
            crc = ((crc shl 8) xor crcTable[tableIndex]) and 0xFFFF
        }
        return crc
    }

    // ────────────────────────────────────────────────────────────
    // UTILIDADES BYTE
    // ────────────────────────────────────────────────────────────
    private fun Int.toByteArrayLE(): ByteArray =
        byteArrayOf((this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte())

    private fun ByteArray.readUInt16LE(startIndex: Int): Int =
        (this[startIndex].toInt() and 0xFF) or
                ((this[startIndex + 1].toInt() and 0xFF) shl 8)

    fun bytesToHex(data: ByteArray, separator: String = " "): String =
        data.joinToString(separator) { String.format("%02X", it) }

    fun hexStringToBytes(hex: String): ByteArray {
        val sanitized = hex.replace(" ", "").replace("0x", "").replace("0X", "")
        require(sanitized.length % 2 == 0) { "La cadena hex debe tener longitud par" }
        return sanitized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
