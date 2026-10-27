package app.irremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.irremote.ui.*

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            vm.handleVolumeKey(keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)
        ) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val volume = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        if (volume && vm.volumeKeys && vm.current is Screen.Remote) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("remote_id")?.let { vm.openRemote(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra("remote_id")?.let { vm.openRemote(it) }
        setContent {
            IrTheme {
                BackHandler(enabled = vm.stack.size > 1) { vm.pop() }
                when (val s = vm.current) {
                    Screen.Home -> HomeScreen(vm)
                    Screen.Categories -> CategoriesScreen(vm)
                    Screen.Search -> SearchScreen(vm)
                    Screen.Scan -> ScanScreen(vm)
                    Screen.AutoScan -> AutoScanScreen(vm)
                    Screen.Discover -> DiscoverScreen(vm)
                    is Screen.DeviceMatch -> DeviceMatchScreen(vm, s.device)
                    is Screen.Brands -> BrandsScreen(vm, s.category)
                    is Screen.Wizard -> WizardScreen(vm, s.category, s.brand, s.hint)
                    is Screen.Remote -> RemoteScreen(vm, s.id)
                }
            }
        }
    }
}

@Composable
fun IrTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        background = Color(0xFF000000), surface = Color(0xFF000000),
        surfaceContainerHigh = Color(0xFF242424), primary = Color(0xFF3F8CEB),
    ) else lightColorScheme(primary = Color(0xFF2F7BE0), surfaceContainerHigh = Color(0xFFEDEDED))
    MaterialTheme(colorScheme = scheme, content = content)
}
