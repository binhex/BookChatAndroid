package com.bookchat.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

object DccDownloader {

    suspend fun download(
        ippDotted: String,
        port: Int,
        fileSize: Long,
        destFile: File,
        timeoutSeconds: Int = 60,
        onProgress: (bytesReceived: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        require(fileSize < Int.MAX_VALUE) { "File too large for 32-bit ACK" }

        val ackBuf = ByteBuffer.allocate(4)
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(ippDotted, port), 30_000)
            // Fail-safe: if no data received for timeoutSeconds, read() throws SocketTimeoutException
            socket.soTimeout = timeoutSeconds * 1000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val buf = ByteArray(8192)
            var totalReceived = 0L
            var lastAcked = 0L

            destFile.outputStream().use { fos ->
                while (isActive && totalReceived < fileSize) {
                    val read = input.read(buf, 0, minOf(buf.size.toLong(), fileSize - totalReceived).toInt())
                    if (read < 0) break
                    fos.write(buf, 0, read)
                    totalReceived += read
                    onProgress(totalReceived)

                    if (totalReceived - lastAcked >= 4096) {
                        output.write(ackBuf.encodeAck(totalReceived))
                        output.flush()
                        lastAcked = totalReceived
                    }
                }
            }

            output.write(ackBuf.encodeAck(totalReceived))
            output.flush()

            if (!isActive) {
                destFile.delete()
                Result.failure(CancellationException("Cancelled"))
            } else {
                Result.success(destFile)
            }
        } catch (e: CancellationException) {
            destFile.delete()
            throw e
        } catch (e: Exception) {
            destFile.delete()
            Result.failure(e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun ByteBuffer.encodeAck(value: Long): ByteArray {
        rewind()
        putInt(value.toInt())
        return array()
    }
}
