package com.oshi.messenger.ui.components

/**
 * Street addresses in free text, by pattern — the fallback for [MapOpener.detectAddresses]
 * when the system TextClassifier finds nothing (it is absent or model-less on many devices).
 *
 * __MAP_OPENER_2026_09_23__ Deliberately conservative: a match needs a HOUSE NUMBER and a
 * STREET WORD next to each other, in one of the four shapes streets are written in the
 * languages OSHI ships:
 *
 *   • number + street type first  — "12 rue de Rivoli", "5 avenue des Champs-Élysées"
 *   • number + name + street type — "1600 Amphitheatre Parkway", "221B Baker Street"
 *   • street type + name + number — "Calle Mayor 10", "Via Roma 12", "Rua Augusta 100"
 *   • compound name + number      — "Hauptstraße 5", "Karl-Marx-Allee 12", "Kalverstraat 1"
 *
 * then extends over a comma-separated city / postal-code tail ("…, 75001 Paris",
 * "…, Mountain View, CA 94043"). Pure: unit-tested in AddressHeuristicTest.
 */
object AddressHeuristic {

    private const val WORD = """(?:[dlDL]['’])?[\p{Lu}\d][\p{L}\p{M}\d'’.\-]*"""
    private const val PARTICLE =
        """(?:de|du|des|la|le|les|del|della|delle|dei|degli|di|da|do|dos|das|dello|y|e|van|von|der|den|het)"""
    private const val NAME = """(?:\s+(?:$PARTICLE\s+){0,2}$WORD){1,4}"""

    private const val TYPE_FIRST =
        "rue|avenue|av|boulevard|bd|blvd|chemin|place|all[ée]e|impasse|quai|route|cours|passage|square|" +
            "calle|avenida|avda|paseo|plaza|carrer|via|viale|piazza|corso|largo|vicolo|" +
            "rua|travessa|pra[çc]a|alameda|estrada"
    private const val TYPE_FIRST_CAPS =
        "Calle|Avenida|Avda\\.|Paseo|Plaza|Carrer|Via|Viale|Piazza|Corso|Largo|Rua|Travessa|Pra[çc]a|Alameda"
    private const val TYPE_LAST =
        "Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln|Way|Parkway|Pkwy|Place|Pl|" +
            "Court|Ct|Square|Sq|Terrace|Highway|Hwy|Circle|Crescent|Close|Plaza"
    private const val GERMANIC_SUFFIX =
        "stra(?:ß|ss)e|str\\.|weg|gasse|platz|allee|damm|ufer|chaussee|straat|laan|gracht|plein|kade|gatan|v[äa]gen|gade|vej"

    private const val NUMBER = """\d{1,5}(?:\s?(?:bis|ter)|[A-Za-z])?"""

    private val shapes = listOf(
        // 12 rue de Rivoli
        Regex("""(?<![\p{L}\d])$NUMBER,?\s+(?i:$TYPE_FIRST)\.?$NAME"""),
        // 1600 Amphitheatre Parkway
        Regex("""(?<![\p{L}\d])$NUMBER\s+(?:$WORD\s+){1,4}(?:$TYPE_LAST)\b\.?"""),
        // Calle Mayor 10
        Regex("""(?<![\p{L}\d])(?:$TYPE_FIRST_CAPS)$NAME,?\s+\d{1,5}[A-Za-z]?\b"""),
        // Hauptstraße 5
        Regex("""(?<![\p{L}\d])\p{Lu}[\p{L}\p{M}\-]*(?:$GERMANIC_SUFFIX)\s+\d{1,4}[A-Za-z]?\b"""),
    )

    /** ", 75001 Paris" · ", Mountain View" · ", CA 94043" · " 10115 Berlin" */
    private val tail = Regex(
        """(?:,\s*|\s+(?=\d{4,5}\s))(?:\d{4,5}(?:-\d{3,4})?\s+)?\p{Lu}[\p{L}\p{M}'’.\-]*""" +
            """(?:\s+\p{Lu}[\p{L}\p{M}'’.\-]*){0,2}(?:\s+\d{4,6}(?:-\d{4})?)?"""
    )

    fun find(text: String): List<IntRange> {
        if (text.length < 8 || text.none { it.isDigit() }) return emptyList()
        val raw = shapes.flatMap { r -> r.findAll(text).map { it.range } }
            .sortedWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })
        val kept = mutableListOf<IntRange>()
        for (r in raw) {
            val prev = kept.lastOrNull()
            if (prev != null && r.first <= prev.last) {
                if (r.last > prev.last) kept[kept.lastIndex] = prev.first..r.last
                continue
            }
            kept += r
        }
        return kept.map { r -> extend(text, r) }
    }

    private val abbreviations =
        setOf("st", "ave", "av", "rd", "dr", "blvd", "bd", "ln", "pl", "ct", "sq", "hwy", "pkwy", "str", "avda")

    private fun extend(text: String, r: IntRange): IntRange {
        var end = r.last + 1
        for (i in 0 until 3) {
            val m = tail.find(text, end) ?: break
            if (m.range.first != end) break
            end = m.range.last + 1
        }
        // A trailing period belongs to the sentence — unless it closes "St." & co.
        if (end - 1 > r.first && text[end - 1] == '.') {
            val lastWord = text.substring(r.first, end - 1).substringAfterLast(' ').lowercase()
            if (lastWord !in abbreviations) end--
        }
        return r.first until end
    }
}
