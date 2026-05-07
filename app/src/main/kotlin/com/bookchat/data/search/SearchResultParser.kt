package com.bookchat.data.search

object SearchResultParser {

    fun parseResultLine(line: String): SearchResult? {
        if (line.isBlank()) return null
        if (!line.startsWith("!")) return null
        if (line.contains("Search results from") || line.contains("Searched")) return null

        return runCatching {
            val result = MutableSearchResult(rawLine = line)

            val botMatch = Regex("""^!(\w+(?:-\w+)*)""").find(line) ?: return null
            result.botName = botMatch.groupValues[1]
            var remainder = line.substring(botMatch.value.length).trimStart()

            // Format: %HEX% hash (Firebound, FWServer)
            val percentHash = Regex("""^%([0-9A-Fa-f]+)%\s+(.+)""").find(remainder)
            if (percentHash != null) {
                result.fileHash = percentHash.groupValues[1]
                remainder = percentHash.groupValues[2]
            } else {
                // Format: hash | filename (Dumbledoo, GER-Borg, TrainFiles)
                val pipeHash = Regex("""^([0-9a-f]+)\s*\|\s*(.+)""").find(remainder)
                if (pipeHash != null) {
                    result.fileHash = pipeHash.groupValues[1]
                    remainder = pipeHash.groupValues[2]
                } else if (remainder.contains("::HASH::")) {
                    // Format: ::HASH:: at end (Ook)
                    val endHash = Regex("""::HASH::\s*([0-9a-f]+)""").find(remainder)
                    if (endHash != null) {
                        result.fileHash = endHash.groupValues[1]
                        remainder = remainder.replace(endHash.value, "").trim()
                    }
                } else if (remainder.contains(" - ")) {
                    // Format: hash - Author - Title (Ashurbanipal)
                    val ashur = Regex("""^([^\s\-]+)\s+-\s+(.+)""").find(remainder)
                    if (ashur != null) {
                        result.fileHash = ashur.groupValues[1]
                        return parseAshurbanipal(result, ashur.groupValues[2])
                    }
                }
            }

            // Extract ::INFO:: size
            val infoSize = Regex(
                """::INFO::\s*(\d+\.?\d*\s*(?:KB|MB|GB|KiB|MiB|GiB))""",
                RegexOption.IGNORE_CASE,
            ).find(remainder)
            if (infoSize != null) {
                result.fileSize = infoSize.groupValues[1]
                remainder = remainder.replace(infoSize.value, "").trim()
            } else {
                val bareSize = Regex(
                    """(\d+\.?\d*\s*(?:KB|MB|GB|KiB|MiB|GiB))(?:\s|$)""",
                    RegexOption.IGNORE_CASE,
                ).find(remainder)
                if (bareSize != null) result.fileSize = bareSize.groupValues[1]
            }

            val filename = remainder.trim()
            parseFilename(filename, result)
            result.fileName = filename
            if (result.fileHash.isEmpty()) result.fileHash = filename

            if (result.botName.isNotEmpty() && (result.title.isNotEmpty() || result.author.isNotEmpty())) {
                result.toSearchResult()
            } else null
        }.getOrNull()
    }

    private fun parseAshurbanipal(result: MutableSearchResult, remainder: String): SearchResult? {
        val parts = remainder.split(" - ")
        if (parts.isEmpty()) return null

        result.author = parts[0].trim()

        if (parts.size >= 2) {
            val titlePart = parts[1].trim()
            val langMatch = Regex("""\[([^\]]+)\]""").find(titlePart)
            if (langMatch != null) {
                result.language = langMatch.groupValues[1]
                result.title = titlePart.replace(langMatch.value, "").trim()
            } else {
                result.title = titlePart
            }
        }

        if (parts.size >= 3) {
            val seriesPart = parts[2].trim()
            val formatMatch = Regex("""\(([^)]+)\)""").find(seriesPart)
            if (formatMatch != null) result.format = formatMatch.groupValues[1].uppercase()
            val sizeMatch = Regex(
                """(\d+\.?\d*\s*(?:KB|MB|GB))""", RegexOption.IGNORE_CASE,
            ).find(seriesPart)
            if (sizeMatch != null) result.fileSize = sizeMatch.groupValues[1]
            var series = seriesPart
            if (formatMatch != null) series = series.replace(formatMatch.value, "")
            if (sizeMatch != null) series = series.replace(sizeMatch.value, "")
            result.series = series.trim()
        }

        if (parts.size >= 4) {
            val tagsMatch = Regex("""\[([^\]]+)\]""").find(parts[3])
            if (tagsMatch != null) result.tags = tagsMatch.groupValues[1]
        }

        return result.toSearchResult()
    }

    private fun parseFilename(filename: String, result: MutableSearchResult) {
        if (filename.isBlank()) return
        var name = filename

        val extMatch = Regex("""\.([a-zA-Z0-9]+)$""").find(name)
        if (extMatch != null) {
            val ext = extMatch.groupValues[1].uppercase()
            if (ext in setOf("EPUB", "MOBI", "AZW3", "PDF", "RAR", "ZIP", "HTML")) {
                if (result.format.isEmpty()) result.format = ext
                name = name.dropLast(extMatch.value.length)
            }
        }

        val formatInParens = Regex(
            """\(([^)]*(?:epub|mobi|azw3|pdf|html)[^)]*)\)""", RegexOption.IGNORE_CASE,
        ).find(name)
        if (formatInParens != null) {
            if (result.format.isEmpty()) {
                val fm = Regex("(epub|mobi|azw3|pdf|html)", RegexOption.IGNORE_CASE)
                    .find(formatInParens.groupValues[1])
                if (fm != null) result.format = fm.groupValues[1].uppercase()
            }
            name = name.replace(formatInParens.value, "").trim()
        }

        name = name.replace(Regex("""\s*\([^)]*\)"""), "").trim()
        name = name.replace(Regex("""\s*\[[^\]]*\]"""), "").trim()

        if (name.contains(" - ")) {
            val dashParts = name.split(" - ", limit = 2)
            result.author = dashParts[0].trim()
            result.title = dashParts[1].trim()
        } else {
            result.title = name.trim()
        }
    }

    fun parseZip(bytes: ByteArray): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        runCatching {
            val zis = java.util.zip.ZipInputStream(bytes.inputStream())
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".txt", ignoreCase = true)) {
                    zis.bufferedReader().lineSequence().forEach { line ->
                        parseResultLine(line)?.let { results.add(it) }
                    }
                    break
                }
                entry = zis.nextEntry
            }
        }
        return results
    }

    private data class MutableSearchResult(
        var botName: String = "",
        var fileHash: String = "",
        var author: String = "",
        var title: String = "",
        var series: String = "",
        var language: String = "",
        var format: String = "",
        var fileSize: String = "",
        var tags: String = "",
        var rawLine: String = "",
        var fileName: String = "",
    ) {
        fun toSearchResult() = SearchResult(
            botName = botName,
            fileHash = fileHash,
            author = author,
            title = title,
            series = series,
            language = language,
            format = format,
            fileSize = fileSize,
            tags = tags,
            rawLine = rawLine,
            fileName = fileName,
        )
    }
}
