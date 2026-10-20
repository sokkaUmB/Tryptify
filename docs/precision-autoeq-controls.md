# Precision AutoEQ's controls

`ui/eq/EqFilterControls.kt`, with `EqStudioControls.kt` (the scrub pill and
the value dialog) and `EqStudioMath.kt` (their arithmetic). They sit under
Precision AutoEQ's own graph (`FrequencyResponseGraph`, in `EqualizerScreen`):
the targets as pills, then a chip per filter, then the selected filter's
type, frequency, gain and Q.

They came from an EQ Studio screen modelled on peqdb.com's studio. The owner
moved its controls into Precision AutoEQ, kept that page's own graph, and
removed the Studio; the files keep their `Studio` names.

## Rules that are not obvious from the code

**The targets are pills under the graph** (`TargetStrip`), one tap each, with
Import at the end. An imported target is deleted by the × on its pill, offered
only on the one selected, so a stray tap on the strip never deletes one.

**A filter's colour is keyed on its id, never its position**
(`studioFilterColor`). Deleting another filter must not repaint the one being
tuned, and its chip, its dot and its pills stay one colour.

**Dragging a dot on the graph selects that filter below.** The graph reports
only drags, not taps, so a tap alone selects nothing; the chips select too.

**Scrub pills report the total drag distance, not deltas.** The caller
recomputes from the value at drag start, so clamping at an end of a range cannot
make the value drift. Scales: frequency 60 dp per octave (a tick every sixth of
an octave), gain 16 dp per dB (a tick per dB), Q 80 dp per octave of Q (a tick
per quarter octave). A tap on a pill types an exact value (`StudioFieldDialog`).

**Nothing glass goes in a dialog.** The value dialog is plain Material 3.

**Filter types stay in English** in every language (PEAK, LOW-S, HIGH-S), as on
the band editor and in every DAW (`app/l10n/GLOSSARY.md`).
