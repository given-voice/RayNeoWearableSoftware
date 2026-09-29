package com.givenvoice.wearable

/**
 * Gabe's 47-phrase bank, sourced from the Context Engineering workstream
 * (Context_Engineering.xlsx — "Phrase Bank" sheet).
 *
 * audioFile naming convention: phrase_001.wav ... phrase_047.wav
 * These should be pre-generated via the ElevenLabs API using the settings
 * confirmed in the Synthetic Voice Audit (Multilingual v2, stability 0.75,
 * speed 0.9, WAV 44.1kHz Lossless) and bundled into the app's assets folder —
 * per the project's recommendation to use a pre-recorded phrase bank rather
 * than real-time TTS until voice quality is improved.
 */

enum class Category { DAILY_CARE, MEDICAL, SCHEDULING, SOCIAL }
enum class Priority { HIGH, MED, LOW }

data class Phrase(
    val id: Int,
    val text: String,
    val category: Category,
    val subCategory: String,
    val priority: Priority,
    val audioFile: String = "phrase_%03d.wav".format(id)
)

object PhraseBank {
    val all: List<Phrase> = listOf(
        Phrase(1, "I need changed.", Category.DAILY_CARE, "Personal hygiene", Priority.HIGH),
        Phrase(2, "Can you wash my face?", Category.DAILY_CARE, "Personal hygiene", Priority.HIGH),
        Phrase(3, "Can you brush my teeth?", Category.DAILY_CARE, "Personal hygiene", Priority.HIGH),
        Phrase(4, "Can you apply deodorant?", Category.DAILY_CARE, "Personal hygiene", Priority.MED),
        Phrase(5, "Can I have a bed bath?", Category.DAILY_CARE, "Personal hygiene", Priority.MED),
        Phrase(6, "Can I have a new shirt?", Category.DAILY_CARE, "Personal hygiene", Priority.MED),
        Phrase(7, "Can you dry my eyes?", Category.DAILY_CARE, "Comfort", Priority.HIGH),
        Phrase(8, "Can you wipe my nose?", Category.DAILY_CARE, "Comfort", Priority.HIGH),
        Phrase(9, "Can I have eye drops?", Category.DAILY_CARE, "Comfort", Priority.HIGH),
        Phrase(10, "Can I have ear drops?", Category.DAILY_CARE, "Comfort", Priority.MED),
        Phrase(11, "Can I sit up?", Category.DAILY_CARE, "Positioning", Priority.HIGH),
        Phrase(12, "Can you adjust my head?", Category.DAILY_CARE, "Positioning", Priority.HIGH),
        Phrase(13, "Can you adjust my legs?", Category.DAILY_CARE, "Positioning", Priority.HIGH),
        Phrase(14, "Can you adjust my arms?", Category.DAILY_CARE, "Positioning", Priority.HIGH),
        Phrase(15, "Can you adjust my hand braces?", Category.DAILY_CARE, "Positioning", Priority.MED),
        Phrase(16, "Can you adjust my computer?", Category.DAILY_CARE, "Equipment", Priority.HIGH),
        Phrase(17, "Can you lower my head and the TV?", Category.DAILY_CARE, "Equipment", Priority.MED),
        Phrase(18, "Can you close the bathroom door?", Category.DAILY_CARE, "Environment", Priority.MED),
        Phrase(19, "Can you adjust the fan?", Category.DAILY_CARE, "Environment", Priority.MED),
        Phrase(20, "Can I have some food?", Category.MEDICAL, "Basic needs", Priority.HIGH),
        Phrase(21, "Can I have water?", Category.MEDICAL, "Basic needs", Priority.HIGH),
        Phrase(22, "When can I get my meds?", Category.MEDICAL, "Medication", Priority.HIGH),
        Phrase(23, "What meds am I getting?", Category.MEDICAL, "Medication", Priority.HIGH),
        Phrase(24, "Can I have Voltarin on my legs?", Category.MEDICAL, "Medication", Priority.MED),
        Phrase(25, "Can I have a Zofran?", Category.MEDICAL, "Medication", Priority.MED),
        Phrase(26, "Can I have a breathing treatment?", Category.MEDICAL, "Treatment", Priority.HIGH),
        Phrase(27, "Can you check and change my catheter?", Category.MEDICAL, "Personal care", Priority.HIGH),
        Phrase(28, "When do I have therapy?", Category.SCHEDULING, "Therapy", Priority.HIGH),
        Phrase(29, "Who do I have for therapy?", Category.SCHEDULING, "Therapy", Priority.HIGH),
        Phrase(30, "Who works next shift?", Category.SCHEDULING, "Staff info", Priority.MED),
        Phrase(31, "Who is the nurse?", Category.SCHEDULING, "Staff info", Priority.MED),
        Phrase(32, "Who is the aide?", Category.SCHEDULING, "Staff info", Priority.MED),
        Phrase(33, "Can I call Lauren?", Category.SOCIAL, "Family", Priority.HIGH),
        Phrase(34, "I have a message to play for you.", Category.SOCIAL, "Media sharing", Priority.MED),
        Phrase(35, "I have a video I would like to show you.", Category.SOCIAL, "Media sharing", Priority.MED),
        Phrase(36, "Have a great day!", Category.SOCIAL, "Greetings", Priority.MED),
        Phrase(37, "Good morning! I hope you had a great night.", Category.SOCIAL, "Greetings", Priority.MED),
        Phrase(38, "Thank you for everything, it means a lot.", Category.SOCIAL, "Gratitude", Priority.HIGH),
        Phrase(39, "Thank you for coming.", Category.SOCIAL, "Gratitude", Priority.MED),
        Phrase(40, "I love you!", Category.SOCIAL, "Gratitude", Priority.HIGH),
        Phrase(41, "I had a great night.", Category.MEDICAL, "Health update", Priority.MED),
        Phrase(42, "I've had a rough couple of days.", Category.MEDICAL, "Health update", Priority.MED),
        Phrase(43, "Therapy went well!", Category.SCHEDULING, "Therapy", Priority.MED),
        Phrase(44, "I'm back from therapy.", Category.SCHEDULING, "Therapy", Priority.MED),
        Phrase(45, "I slept really well.", Category.MEDICAL, "Health update", Priority.MED),
        Phrase(46, "Okay I am sorry if I fall asleep on you.", Category.SOCIAL, "Apology", Priority.LOW),
        Phrase(47, "I'm starting to feel better.", Category.MEDICAL, "Health update", Priority.MED)
    )

    /** Convenience: the "Physical discomfort" context group is the most
     * frequent daily need per the Context Map — a reasonable default screen. */
    val physicalDiscomfort: List<Phrase> = all.filter {
        it.id in setOf(7, 8, 11, 12, 13, 14)
    }

    fun byCategory(category: Category): List<Phrase> = all.filter { it.category == category }
    fun highPriority(): List<Phrase> = all.filter { it.priority == Priority.HIGH }
}
