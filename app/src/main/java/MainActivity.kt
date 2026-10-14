package com.givenvoice.wearable

import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.core.make3DEffectForSide
import com.ffalcon.mercury.android.sdk.focus.reqFocus
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.util.FixPosFocusTracker
import com.ffalcon.mercury.android.sdk.ui.util.FocusHolder
import com.ffalcon.mercury.android.sdk.ui.util.FocusInfo
import com.givenvoice.wearable.databinding.ActivityMainBinding
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Temple-touch navigation, built on the pattern confirmed in RayNeo's
 * official sample (DemoHomeActivity.kt): FocusHolder + FocusInfo per
 * selectable element, FixPosFocusTracker to move between them, and a
 * collector on templeActionViewModel.state that feeds temple gestures
 * into the tracker.
 *
 * Audio (updated): selecting a phrase now speaks it in Gabe's voice via
 * the ElevenLabs API. Each phrase is generated once and cached on the
 * glasses, so repeat presses play instantly and work offline. Lookup
 * order in playPhrase(): bundled asset -> cached file -> ElevenLabs API.
 *
 * Switch box: the ESP32 three-switch box sends BTN:1/2/3 over USB serial;
 * SwitchInput turns those into temple gestures, so they navigate exactly
 * like the temple. In debug builds (emulator or glasses over adb) the same
 * lines also come from simulation/bridge.py over TCP.
 */
class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {

    private var player: MediaPlayer? = null
    private var fixPosFocusTracker: FixPosFocusTracker? = null
    private var currentSituation: Situation? = null
    private var isGenerating = false

    /** Only one ElevenLabs request at a time — shared by the startup
     * prefetch and on-demand presses, so they never write the same file
     * at once. Mutex is FIFO, so a press waits at most for the one phrase
     * currently being prefetched, then takes the next turn. */
    private val ttsMutex = Mutex()

    private val usbSwitches = UsbSerialSwitchSource(this, ::onSwitchLine) { status ->
        runOnUiThread { Toast.makeText(this, status, Toast.LENGTH_LONG).show() }
    }
    private val bleSwitches = BleSwitchSource(
        this,
        onButton = { button -> SwitchInput.dispatch(button, templeActionViewModel) },
        onStatus = { status -> runOnUiThread { Toast.makeText(this, status, Toast.LENGTH_LONG).show() } },
    )
    private val phraseSender by lazy { BlePhraseSender(this) }
    private val simulatedSwitches =
        if (BuildConfig.DEBUG) TcpSwitchSource("127.0.0.1", SIMULATION_PORT, ::onSwitchLine)
        else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSituationPicker()
        setUpUrgentButtonClick()
        collectTempleEvents()
        prefetchAllPhrases()
        requestBluetoothScanPermission()
    }

    /** The Bluetooth switch box needs the scan permission; ask once if it is missing. */
    private fun requestBluetoothScanPermission() {
        val wanted = if (android.os.Build.VERSION.SDK_INT >= 31)
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_ADVERTISE)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = wanted.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        bleSwitches.start()
    }

    override fun onResume() {
        super.onResume()
        bleSwitches.start()
        usbSwitches.start()
        simulatedSwitches?.start()
    }

    override fun onPause() {
        bleSwitches.stop()
        usbSwitches.stop()
        simulatedSwitches?.stop()
        super.onPause()
    }

    /** Bluetooth switch box keys. Both the press and the release are consumed so Android
     * never applies its own meaning (Tab/Enter focus moves, Esc as Back). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val button = SwitchInput.fromKeyCode(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.d(TAG, "Switch box key: $button")
            SwitchInput.dispatch(button, templeActionViewModel)
        }
        return true
    }

    private fun onSwitchLine(line: String) {
        Log.d(TAG, "Switch box: $line")
        SwitchInput.dispatch(line, templeActionViewModel)
    }

    // ------------------------------------------------------------------
    // Screens
    // ------------------------------------------------------------------

    private fun showSituationPicker() {
        currentSituation = null
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val items = ContextMap.orderedSituations(currentHour)
        renderList(
            title = "Choose a situation",
            labels = items.map { it.name },
            onSelect = { index -> showPhrasesFor(items[index]) }
        )
    }

    private fun showPhrasesFor(situation: Situation) {
        currentSituation = situation
        val phrases = ContextMap.phrasesFor(situation)
        renderList(
            title = situation.name,
            labels = listOf("← Back") + phrases.map { it.text },
            onSelect = { index ->
                if (index == 0) showSituationPicker() else playPhrase(phrases[index - 1])
            }
        )
    }

    // ------------------------------------------------------------------
    // Shared rendering + focus wiring for both screens
    // ------------------------------------------------------------------

    private fun renderList(title: String, labels: List<String>, onSelect: (Int) -> Unit) {
        mBindingPair.updateView {
            screenTitle.text = title
            phraseContainer.removeAllViews()
            labels.forEach { label ->
                val button = Button(this@MainActivity).apply {
                    text = label
                    textSize = 20f
                    setBackgroundColor(android.graphics.Color.parseColor("#333333"))
                    setTextColor(android.graphics.Color.WHITE)
                }
                phraseContainer.addView(button)
            }
        }

        mBindingPair.setLeft {
            val focusHolder = FocusHolder()

            val urgentInfo = FocusInfo(
                urgentButton,
                eventHandler = { action ->
                    if (action is TempleAction.Click) showPhrasesFor(ContextMap.urgentSituation)
                },
                focusChangeHandler = { hasFocus ->
                    mBindingPair.updateView {
                        triggerFocus(hasFocus, urgentButton, mBindingPair.checkIsLeft(this))
                    }
                }
            )

            val infos = labels.indices.map { index ->
                val button = phraseContainer.getChildAt(index) as Button
                button.setOnClickListener { onSelect(index) }
                FocusInfo(
                    button,
                    eventHandler = { action ->
                        when (action) {
                            is TempleAction.Click -> onSelect(index)
                            else -> Unit
                        }
                    },
                    focusChangeHandler = { hasFocus ->
                        mBindingPair.updateView {
                            val target = phraseContainer.getChildAt(index) as Button
                            triggerFocus(hasFocus, target, mBindingPair.checkIsLeft(this))
                            if (hasFocus) {
                                scrollContainer.post {
                                    val targetY = target.top - 24
                                    scrollContainer.smoothScrollTo(0, targetY.coerceAtLeast(0))
                                }
                            }
                        }
                    }
                )
            }

            focusHolder.addFocusTarget(urgentInfo, *infos.toTypedArray())
            if (infos.isNotEmpty()) {
                focusHolder.currentFocus(phraseContainer.getChildAt(0))
            }
            fixPosFocusTracker = FixPosFocusTracker(focusHolder).apply { focusObj.reqFocus() }
        }
    }

    private fun triggerFocus(hasFocus: Boolean, view: View, isLeft: Boolean) {
        val focusedColor = android.graphics.Color.parseColor("#CC8400")   // muted amber
        val unfocusedColor = android.graphics.Color.parseColor("#333333") // dark gray
        view.setBackgroundColor(if (hasFocus) focusedColor else unfocusedColor)
        make3DEffectForSide(view, isLeft, hasFocus)
    }

    private fun collectTempleEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when {
                        action is TempleAction.DoubleClick && currentSituation != null ->
                            showSituationPicker()
                        else -> fixPosFocusTracker?.handleFocusTargetEvent(action)
                    }
                }
            }
        }
    }

    private fun setUpUrgentButtonClick() {
        mBindingPair.updateView {
            urgentButton.setOnClickListener {
                showPhrasesFor(ContextMap.urgentSituation)
            }
        }
    }

    // ------------------------------------------------------------------
    // Audio
    // ------------------------------------------------------------------

    /**
     * Speaks [phrase] in Gabe's voice. Lookup order:
     *  1. Bundled asset (assets/audio/phrase_XXX.wav) — if pre-recorded
     *     files are ever added to the app, they always win.
     *  2. Cached file from an earlier ElevenLabs call — instant, offline.
     *  3. ElevenLabs API — first press only; needs Wi-Fi. The result is
     *     cached, so the phrase bank effectively builds itself on use.
     *
     * The cache filename includes a hash of the phrase text, so editing
     * a phrase in PhraseBank automatically triggers a fresh generation.
     */
    private fun playPhrase(phrase: Phrase) {
        // Ask the ESP32 box to speak it on the Bluetooth speaker.
        val sentToBox = phraseSender.send(phrase.id)
        if (sentToBox && SPEAK_ON_BOX_ONLY) return
        val assetPath = "audio/${phrase.audioFile}"
        if (assetExists(assetPath)) {
            startPlayer {
                val afd = assets.openFd(assetPath)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            }
            return
        }

        val cached = cacheFileFor(phrase)
        if (cached.exists()) {
            startPlayer { setDataSource(cached.absolutePath) }
            return
        }

        // Ignore repeat presses while a phrase is still being generated,
        // so a double-tap doesn't fire two paid API calls.
        if (isGenerating) return
        isGenerating = true
        lifecycleScope.launch {
            try {
                ttsMutex.withLock {
                    // The prefetch may have finished this exact phrase
                    // while we were waiting for our turn.
                    if (!cached.exists()) ElevenLabsTts.synthesize(phrase.text, cached)
                }
                startPlayer { setDataSource(cached.absolutePath) }
            } catch (e: Exception) {
                Log.e(TAG, "TTS failed for phrase ${phrase.id}", e)
                Toast.makeText(this@MainActivity, "Voice unavailable — check Wi-Fi", Toast.LENGTH_SHORT).show()
            } finally {
                isGenerating = false
            }
        }
    }

    /** Cache filename includes a hash of the text, so editing a phrase in
     * PhraseBank automatically regenerates just that phrase. */
    private fun cacheFileFor(phrase: Phrase): File =
        File(filesDir, "tts/phrase_%03d_%08x.mp3".format(phrase.id, phrase.text.hashCode()))

    /**
     * On every launch, generates any of the 47 phrases that aren't cached
     * yet, one at a time in the background. After the first full run
     * there is nothing missing, so later launches make zero API calls.
     * Phrases with a bundled asset are skipped. A failure on one phrase
     * (e.g. Wi-Fi drop) is logged and the rest continue; anything missed
     * is simply retried on the next launch.
     */
    private fun prefetchAllPhrases() {
        lifecycleScope.launch {
            val missing = PhraseBank.all.filter {
                !assetExists("audio/${it.audioFile}") && !cacheFileFor(it).exists()
            }
            if (missing.isEmpty()) {
                Log.i(TAG, "Prefetch: all ${PhraseBank.all.size} phrases already cached")
                return@launch
            }
            Log.i(TAG, "Prefetch: generating ${missing.size} missing phrases")

            var done = 0
            for (phrase in missing) {
                try {
                    ttsMutex.withLock {
                        val file = cacheFileFor(phrase)
                        if (!file.exists()) ElevenLabsTts.synthesize(phrase.text, file)
                    }
                    done++
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Prefetch stopped: ${e.message}")   // e.g. API key missing
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch failed for phrase ${phrase.id}, will retry next launch", e)
                }
            }
            Log.i(TAG, "Prefetch finished: $done/${missing.size} generated")
            if (done > 0) {
                Toast.makeText(this@MainActivity, "Voice ready", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPlayer(setSource: MediaPlayer.() -> Unit) {
        player?.release()
        try {
            player = MediaPlayer().apply {
                setSource()
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed", e)
        }
    }

    private fun assetExists(path: String): Boolean =
        try {
            assets.open(path).close()
            true
        } catch (e: Exception) {
            false
        }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GivenVoice"
        /** TCP port simulation/bridge.py listens on. */
        private const val SIMULATION_PORT = 5000
        /** true: a selected phrase is spoken only by the ESP32's Bluetooth speaker, not the glasses.
         *  (If the Bluetooth advertiser is unavailable the glasses still speak it themselves.) */
        private const val SPEAK_ON_BOX_ONLY = true
    }
}