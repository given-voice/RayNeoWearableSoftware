package com.givenvoice.wearable

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import com.ffalcon.mercury.android.sdk.touch.FlingArgs
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.touch.TempleActionViewModel

/** The three switches on the ESP32 box (hardware/switch_serial/switch_serial.ino). */
enum class SwitchButton { NEXT, SELECT, PREVIOUS }

/**
 * Turns "BTN:n" lines from the switch box into temple gestures. The actions
 * are sent into the SDK's own userTempleAction channel, which feeds the same
 * templeActionViewModel.state flow MainActivity already collects - so a
 * switch press is handled exactly like a temple swipe or tap.
 *
 * The list screens use a vertical FixPosFocusTracker, which moves focus on
 * SlideDownwards / SlideUpwards and passes anything else (a Click) to the
 * focused item:
 *
 *   BTN:1 / Tab    red    -> SlideDownwards  (highlight the next box / sentence)
 *   BTN:2 / Enter  green  -> Click           (select the highlighted one)
 *   BTN:3 / Esc    yellow -> SlideUpwards    (highlight the previous one)
 */
object SwitchInput {

    private val BUTTON_LINE = Regex("""BTN:([1-3])$""")

    /** Returns the button for one serial line, or null for anything else
     * (ESP32 boot messages, partial lines). Accepts "\r\n" or "\n" endings. */
    fun parse(line: String): SwitchButton? =
        when (BUTTON_LINE.find(line.trim())?.groupValues?.get(1)) {
            "1" -> SwitchButton.NEXT
            "2" -> SwitchButton.SELECT
            "3" -> SwitchButton.PREVIOUS
            else -> null
        }

    /** The keys the Bluetooth switch box sends (hardware/switch_hid/switch_hid.ino). */
    fun fromKeyCode(keyCode: Int): SwitchButton? =
        when (keyCode) {
            KeyEvent.KEYCODE_TAB -> SwitchButton.NEXT
            KeyEvent.KEYCODE_ENTER -> SwitchButton.SELECT
            KeyEvent.KEYCODE_ESCAPE -> SwitchButton.PREVIOUS
            else -> null
        }

    /** Parses [line] and, if it is a button, injects the matching gesture. */
    fun dispatch(line: String, viewModel: TempleActionViewModel) {
        dispatch(parse(line) ?: return, viewModel)
    }

    fun dispatch(button: SwitchButton, viewModel: TempleActionViewModel) {
        viewModel.userTempleAction.trySend(toTempleAction(button))
    }

    private fun toTempleAction(button: SwitchButton): TempleAction {
        val id = SystemClock.uptimeMillis()
        return when (button) {
            // Swapped after testing on the glasses: SlideDownwards moved the highlight UP the list.
            SwitchButton.NEXT -> TempleAction.SlideUpwards(syntheticFling(down = false), false, id)
            SwitchButton.SELECT -> TempleAction.Click()
            SwitchButton.PREVIOUS -> TempleAction.SlideDownwards(syntheticFling(down = true), false, id)
        }
    }

    /** The tracker only checks the action type, but the slide events still
     * need a FlingArgs, so build a small vertical swipe in the right direction. */
    private fun syntheticFling(down: Boolean): FlingArgs {
        val now = SystemClock.uptimeMillis()
        val startY = if (down) 0f else 100f
        val endY = if (down) 100f else 0f
        val start = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 0f, startY, 0)
        val end = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 0f, endY, 0)
        return FlingArgs(start, end, 0f, if (down) 1000f else -1000f)
    }
}
