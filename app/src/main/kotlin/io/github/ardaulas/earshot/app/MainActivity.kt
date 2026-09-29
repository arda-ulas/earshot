package io.github.ardaulas.earshot.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

/**
 * The single activity. Push-to-talk comes from the on-screen button or a hardware key that stands
 * in for a steering-wheel voice button (F2 on the emulator's keyboard, or the voice-assist key).
 */
class MainActivity : ComponentActivity() {
    private val viewModel: AssistantViewModel by viewModels()

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) viewModel.onPress(hasMicPermission = false)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EarshotTheme {
                AssistantScreen(
                    viewModel = viewModel,
                    onPress = ::press,
                    onRelease = viewModel::onRelease,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshClips()
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun press() {
        if (hasMic()) {
            viewModel.onPress(hasMicPermission = true)
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode in PTT_KEYS) {
            if (event.repeatCount == 0) press()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode in PTT_KEYS) {
            viewModel.onRelease()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private companion object {
        val PTT_KEYS = setOf(KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_VOICE_ASSIST)
    }
}
