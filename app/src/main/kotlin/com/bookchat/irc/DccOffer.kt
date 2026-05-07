package com.bookchat.irc

data class DccOffer(
    val senderNick: String,
    val fileName: String,
    val ippDotted: String,
    val port: Int,
    val fileSize: Long,
)

object DccParser {

    private val DCC_PATTERN = Regex(
        """DCC SEND\s+"?([^"\s]+(?:\s+[^"\s]+)*)"?\s+(\d+)\s+(\d+)\s+(\d+)""",
        RegexOption.IGNORE_CASE,
    )

    // Also handle quoted filenames with spaces
    private val DCC_QUOTED_PATTERN = Regex(
        """DCC SEND\s+"([^"]+)"\s+(\d+)\s+(\d+)\s+(\d+)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(rawLine: String, senderNick: String): DccOffer? {
        // Strip CTCP delimiters (\x01)
        val clean = rawLine.replace("", "")

        val match = DCC_QUOTED_PATTERN.find(clean) ?: DCC_PATTERN.find(clean) ?: return null

        return runCatching {
            val fileName = match.groupValues[1].trim('"')
            val ipDecimal = match.groupValues[2].toLong()
            val port = match.groupValues[3].toInt()
            val fileSize = match.groupValues[4].toLong()

            DccOffer(
                senderNick = senderNick,
                fileName = fileName,
                ippDotted = decimalToIp(ipDecimal),
                port = port,
                fileSize = fileSize,
            )
        }.getOrNull()
    }

    private fun decimalToIp(decimal: Long): String {
        return java.net.InetAddress.getByAddress(
            java.nio.ByteBuffer.allocate(4).putInt(decimal.toInt()).array()
        ).hostAddress ?: ""
    }
}
