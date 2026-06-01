package com.industrialble.protocol

import java.util.LinkedList

/**
 * Buffer de reassembly para manejar la naturaleza stream del L2CAP CoC.
 *
 * L2CAP entrega datos como un stream continuo (como TCP), no como mensajes
 * discretos. Este buffer acumula bytes entrantes y extrae tramas completas
 * a medida que están disponibles.
 *
 * Uso típico:
 *   val buffer = FrameReassemblyBuffer()
 *   buffer.feed(dataChunk)           // Alimentar con datos del InputStream
 *   val frames = buffer.drain()      // Extraer todas las tramas completas
 */
class FrameReassemblyBuffer {

    private val buffer = LinkedList<Byte>()
    private var incompleteFrames = 0
    private var totalFramesReassembled = 0
    private var totalCorruptedFrames = 0

    data class ReassemblyStats(
        val bufferSize: Int,
        val incompleteFrames: Int,
        val totalFramesReassembled: Int,
        val totalCorruptedFrames: Int
    )

    // ────────────────────────────────────────────────────────────
    // ALIMENTAR
    // ────────────────────────────────────────────────────────────
    /**
     * Alimenta el buffer con nuevos datos del InputStream.
     * @param chunk Datos crudos recibidos desde L2CAP
     */
    fun feed(chunk: ByteArray) {
        for (byte in chunk) {
            buffer.add(byte)
        }
    }

    // ────────────────────────────────────────────────────────────
    // DRENAR TRAMAS COMPLETAS
    // ────────────────────────────────────────────────────────────
    /**
     * Extrae todas las tramas completas del buffer interno.
     * @return Lista de tramas válidas parseadas
     */
    fun drain(): List<ProtocolFrameBuilder.ParsedFrame> {
        val frames = mutableListOf<ProtocolFrameBuilder.ParsedFrame>()

        while (buffer.size >= ProtocolFrameBuilder.MIN_FRAME_SIZE) {
            val rawData = buffer.map { it }.toByteArray()
            val frame = ProtocolFrameBuilder.tryParseFromBuffer(rawData)

            if (frame != null) {
                // Trama válida encontrada: remover del buffer
                val frameSize = ProtocolFrameBuilder.HEADER_SIZE +
                        frame.payload.size +
                        ProtocolFrameBuilder.CHECKSUM_SIZE
                repeat(frameSize) { buffer.pollFirst() }
                frames.add(frame)
                totalFramesReassembled++
            } else {
                // No hay suficientes bytes para una trama completa, o
                // los bytes actuales no forman una trama válida.
                // Verificamos si el siguiente byte podría ser el inicio
                // de una trama (adelantamos 1 byte para resync).
                if (buffer.size >= ProtocolFrameBuilder.MIN_FRAME_SIZE) {
                    // Intentar resync: avanzar un byte y reintentar
                    buffer.pollFirst()
                    incompleteFrames++
                }
                break
            }
        }

        return frames
    }

    // ────────────────────────────────────────────────────────────
    // LIMPIEZA
    // ────────────────────────────────────────────────────────────
    /**
     * Limpia todo el buffer.
     */
    fun clear() {
        buffer.clear()
        incompleteFrames = 0
    }

    /**
     * Marca el buffer como corrompido y lo vacía.
     */
    fun flushCorrupted() {
        totalCorruptedFrames += getPartialFrames()
        clear()
    }

    // ────────────────────────────────────────────────────────────
    // ESTADÍSTICAS
    // ────────────────────────────────────────────────────────────
    fun getStats(): ReassemblyStats = ReassemblyStats(
        bufferSize = buffer.size,
        incompleteFrames = incompleteFrames,
        totalFramesReassembled = totalFramesReassembled,
        totalCorruptedFrames = totalCorruptedFrames
    )

    /**
     * Estima cuántas tramas parciales hay en el buffer.
     */
    fun getPartialFrames(): Int {
        if (buffer.size < ProtocolFrameBuilder.MIN_FRAME_SIZE) return 0
        return buffer.size / ProtocolFrameBuilder.MIN_FRAME_SIZE
    }

    fun getBufferedBytes(): Int = buffer.size

    private fun List<Byte>.toByteArray(): ByteArray =
        ByteArray(size) { this[it] }
}
