package com.tallermusica.loopera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.tallermusica.loopera.ui.LooperScreen
import com.tallermusica.loopera.ui.LooperViewModel
import com.tallermusica.loopera.ui.theme.LooperaTheme

class MainActivity : ComponentActivity() {

    private val vm: LooperViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LooperaTheme {
                LooperScreen(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    /** En segundo plano se detiene el pulso y se libera el micrófono. */
    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }
}
