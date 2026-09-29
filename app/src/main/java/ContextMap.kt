package com.givenvoice.wearable

/**
 * Context Map v2 — reworked from the original Context Engineering data
 * (Context_Engineering.xlsx — "Context Map" sheet) based on team review:
 *
 *  - Situations are ordered by real-world frequency/importance rather
 *    than the spreadsheet's original order, since the situation picker
 *    is scanned top-to-bottom via temple touch — putting the most common
 *    need (Physical discomfort) first minimizes scan steps on average.
 *  - "Morning routine" and "Morning greeting" (previously two separate,
 *    overlapping 7-9AM / 7-10AM situations) are merged into one "Morning"
 *    situation, since care needs and social greetings can both come up
 *    during the same morning window and splitting them forced a choice
 *    the data didn't need to make.
 *  - Kept as a 2-level hierarchy (situation picker -> phrase list)
 *    rather than adding a third "category" layer — for someone with
 *    limited, effortful input, fewer navigation steps matters more than
 *    fewer items per scan.
 *  - Time-of-day is used to REORDER the picker (boost the current
 *    time-relevant situation to the top) rather than to skip the picker
 *    entirely — the user still sees and can choose among all situations,
 *    just with the most likely one surfaced first.
 *  - "Equipment issue" remains deliberately outside this list entirely
 *    (see MainActivity's persistent urgentButton) since losing the
 *    ability to flag a broken device is the one failure mode this
 *    interface cannot recover from through normal navigation.
 */

data class Situation(
    val name: String,
    val trigger: String,
    val phraseIds: List<Int>,
    val notes: String,
    val timeWindow: IntRange? = null, // hour-of-day, 24h clock; null = not time-boosted
    val urgent: Boolean = false
)

object ContextMap {

    /** Base order = frequency/importance, NOT the original spreadsheet order. */
    val situations: List<Situation> = listOf(
        Situation(
            name = "Physical discomfort",
            trigger = "Any time",
            phraseIds = listOf(12, 13, 14, 11, 7, 8),
            notes = "Positioning and comfort requests — the most frequent daily need"
        ),
        Situation(
            name = "Personal care",
            trigger = "As needed throughout day",
            phraseIds = listOf(1, 5, 4, 6, 27),
            notes = "Dignity and personal care — HIGH priority"
        ),
        Situation(
            name = "Nurse/aide check-in",
            trigger = "Staff enters room",
            phraseIds = listOf(31, 32, 30, 22, 1),
            notes = "Shift changes are key moments for communication"
        ),
        Situation(
            name = "Medication request",
            trigger = "Scheduled med times or as needed",
            phraseIds = listOf(22, 23, 24, 25, 26),
            notes = "Specific meds matter — Voltarin (pain), Zofran (nausea)"
        ),
        Situation(
            name = "Morning",
            trigger = "7–10 AM",
            phraseIds = listOf(2, 3, 22, 23, 21, 37, 36, 40, 41, 45),
            notes = "Merged from separate 'Morning routine' (care) and 'Morning " +
                    "greeting' (social) situations — both can come up in the same window",
            timeWindow = 7..10
        ),
        Situation(
            name = "Therapy session",
            trigger = "Before/after therapy",
            phraseIds = listOf(28, 29, 43, 44),
            notes = "PT/OT/speech therapy — Gabe very engaged with updates"
        ),
        Situation(
            name = "Family / visitor interaction",
            trigger = "When Lauren or family visits",
            phraseIds = listOf(33, 38, 40, 34, 35),
            notes = "Lauren is #1 contact. Emotional/social phrases very important here."
        ),
        Situation(
            name = "Health update sharing",
            trigger = "After medical event or visit",
            phraseIds = listOf(47, 42, 43, 41),
            notes = "Gabe proactively shares health updates with family"
        ),
    )

    val urgentSituation: Situation = Situation(
        name = "Equipment issue",
        trigger = "Any time — HIGH urgency",
        phraseIds = listOf(16, 33),
        notes = "Computer = communication lifeline. If stuck, Gabe cannot communicate at all.",
        urgent = true
    )

    /**
     * The situation list for display, given the current hour: any
     * situation whose timeWindow matches [hourOfDay] is moved to the
     * front (stable order preserved otherwise). With only "Morning"
     * carrying a timeWindow today, this simply floats it to the top
     * during 7-10 AM and leaves the rest in frequency order otherwise.
     */
    fun orderedSituations(hourOfDay: Int): List<Situation> {
        val (boosted, rest) = situations.partition { it.timeWindow?.contains(hourOfDay) == true }
        return boosted + rest
    }

    fun phrasesFor(situation: Situation): List<Phrase> =
        situation.phraseIds.mapNotNull { id -> PhraseBank.all.firstOrNull { it.id == id } }
}