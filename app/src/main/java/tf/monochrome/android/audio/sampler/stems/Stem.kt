// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

/**
 * The parts a source can be pulled apart into: what a separation server's
 * stems are called, and what the chart's layers are made of ([StemLayer]).
 *
 * [GUITAR] and [PIANO] only exist for six-stem models; a four-stem model
 * leaves them out.
 *
 * Note the ordering: the six-stem split does not add two new sources so much as
 * carve them out of [OTHER], so a six-stem `other` is what is left after guitar
 * and piano have been taken, not the same signal a four-stem model calls
 * `other`.
 */
enum class Stem(val id: String, val label: String) {
    VOCALS("VOCALS", "Vocals"),
    DRUMS("DRUMS", "Drums"),
    BASS("BASS", "Bass"),
    GUITAR("GUITAR", "Guitar"),
    PIANO("PIANO", "Piano"),
    OTHER("OTHER", "Other");

    companion object {
        fun fromId(id: String?): Stem? = entries.firstOrNull { it.id.equals(id, true) }

        /** The classic split every separator can do. */
        val FOUR: Set<Stem> = setOf(VOCALS, DRUMS, BASS, OTHER)

        /** The six-stem split, for models trained for it. */
        val SIX: Set<Stem> = setOf(VOCALS, DRUMS, BASS, GUITAR, PIANO, OTHER)

        /** Stems only a six-stem model produces. */
        val EXTENDED: Set<Stem> = setOf(GUITAR, PIANO)
    }
}
