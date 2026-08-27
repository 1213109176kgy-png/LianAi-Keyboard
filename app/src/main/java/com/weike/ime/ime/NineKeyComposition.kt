package com.weike.ime.ime

/** Keeps a nine-key composition visible even before the decoder has an exact match. */
object NineKeyComposition {
    fun display(
        raw: String,
        preedit: String,
        candidateReadings: List<String>,
        previousRaw: String = "",
        previousDisplay: String = ""
    ): String {
        val code = raw.filter(Char::isDigit)
        if (code.isBlank()) return preedit.ifBlank { raw }

        val normalizedReadings = candidateReadings.asSequence()
            .map { it.lowercase().filter(Char::isLetter) }
            .filter(String::isNotBlank)
            .toList()

        normalizedReadings.firstOrNull { codeFor(it) == code }?.let { return it }
        normalizedReadings.firstOrNull { codeFor(it).startsWith(code) }?.let { reading ->
            return reading.take(code.length.coerceAtMost(reading.length))
        }

        val readablePreedit = preedit.takeIf { value -> value.isNotBlank() && value.none(Char::isDigit) }
        if (readablePreedit != null) return readablePreedit

        val previousCode = previousRaw.filter(Char::isDigit)
        if (previousDisplay.isNotBlank() && previousCode.isNotBlank() && code.startsWith(previousCode)) {
            return previousDisplay + code.removePrefix(previousCode)
        }
        return code
    }

    fun codeFor(pinyin: String): String = buildString(pinyin.length) {
        pinyin.lowercase().forEach { char ->
            append(
                when (char) {
                    in 'a'..'c' -> '2'
                    in 'd'..'f' -> '3'
                    in 'g'..'i' -> '4'
                    in 'j'..'l' -> '5'
                    in 'm'..'o' -> '6'
                    in 'p'..'s' -> '7'
                    in 't'..'v' -> '8'
                    in 'w'..'z' -> '9'
                    else -> return@forEach
                }
            )
        }
    }
}
