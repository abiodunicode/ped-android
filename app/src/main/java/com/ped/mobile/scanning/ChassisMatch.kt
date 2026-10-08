package com.ped.mobile.scanning

import com.ped.mobile.extractChassisCandidate
import com.ped.mobile.extractVinSerialValue
import java.util.Locale

internal data class ChassisMatch(val number: String, val exact: Boolean, val suggestions: List<String>, val referenceAvailable: Boolean)

/** Bounded candidate extraction: don't concatenate identifiers from different lines. */
internal fun chassisCandidates(raw: String): List<String> {
    val result = linkedSetOf<String>()
    raw.take(16000).uppercase(Locale.ROOT).lineSequence().take(100).forEach { line ->
        val tokens = Regex("[A-Z0-9]+").findAll(line).map { it.value }.take(40).toList()
        for (start in tokens.indices) {
            var joined = ""
            for (end in start until minOf(tokens.size, start + 6)) {
                joined += tokens[end]
                if (joined.length > 25) break
                if (joined.length in 6..25 && joined.any { it in '0'..'9' }) result.add(joined)
                if (result.size >= 250) return result.toList()
            }
        }
    }
    return result.toList()
}

internal fun matchChassis(raw: String, reference: CargoLookup?): ChassisMatch? {
    val bounded = raw.take(16000)
    val lines = bounded.lines()
    val label = Regex("(?i)\\b(?:VIN(?:\\s*/\\s*SERIAL)?|CHASSIS(?:\\s*(?:NO|NUMBER))?|SERIAL(?:\\s*(?:NO|NUMBER))?)\\b[.:#\\s-]*")
    val labelled = lines.mapIndexedNotNull { i, line ->
        label.find(line)?.let { match ->
            line.substring(match.range.last + 1).ifBlank { lines.getOrNull(i + 1).orEmpty() }
                .split(Regex("(?i)\\b(?:BOOKING|VESSEL|CARRIER|DATE|ENGINE|POL|POD)\\b[.:#\\s-]*"), limit = 2).first()
        }
    }.joinToString("\n")
    // A clearly labelled chassis must not be replaced by a known booking ID elsewhere.
    val candidates = chassisCandidates(labelled.ifBlank { bounded })
    val exact = reference?.exact(candidates).orEmpty()
    val labelledFallback = extractChassisCandidate(labelled) ?: chassisCandidates(labelled).firstOrNull()
        ?: labelled.trim().uppercase(Locale.ROOT).takeIf { value ->
            value.length in 1..100 && value.all { it in 'A'..'Z' || it in '0'..'9' } && value.any { it in '0'..'9' }
        }
    val fallback = labelledFallback ?: extractVinSerialValue(bounded) ?: extractChassisCandidate(bounded)
    val selected = candidates.firstOrNull { it in exact && it.length == 17 }
        ?: candidates.firstOrNull { it in exact }
        ?: fallback ?: candidates.firstOrNull { it.length == 17 }
        ?: return null
    val suggestions = if (selected in exact) candidates.filter { it in exact && it != selected }.take(5)
        else reference?.similar(selected).orEmpty()
    return ChassisMatch(selected, selected in exact, suggestions, reference != null)
}
