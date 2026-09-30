@file:OptIn(ExperimentalMaterial3Api::class)

package com.tallermusica.loopera.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LooperScreen(vm: LooperViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Reloj de la pantalla: en cada cuadro se lee el frame que está sonando.
    val clock = remember { mutableStateOf(vm.clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            clock.value = vm.clock()
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    // Pantalla encendida mientras suena
    val view = LocalView.current
    DisposableEffect(state.playing) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.enableMic() else vm.onMicPermissionDenied() }

    val onEnableMic = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.enableMic() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val onOpenSettings = {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Loopera") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "pulso") { PulseDisplay(clock) }
            item(key = "controles") {
                PulseControls(
                    state = state,
                    onTogglePlay = vm::togglePlay,
                    onBpmChange = vm::setBpm,
                    onBpmStep = vm::changeBpm,
                    onTap = vm::tapTempo,
                    onBarsChange = vm::setBars,
                )
            }
            item(key = "div1") { HorizontalDivider() }
            item(key = "mic") {
                MicControls(
                    state = state,
                    clock = clock,
                    onEnableMic = onEnableMic,
                    onDisableMic = vm::disableMic,
                    onOpenSettings = onOpenSettings,
                )
            }
            item(key = "grabar") {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    RecordButton(
                        recording = state.recording,
                        enabled = state.micActive && !state.processing,
                        onPress = vm::onRecordPress,
                        onRelease = vm::onRecordRelease,
                    )
                }
            }
            item(key = "auriculares") { HeadphonesNotice() }
            item(key = "sincronia") { SyncAdjustSection(state, vm::setSyncAdjust) }
            item(key = "div2") { HorizontalDivider() }
            item(key = "capas-titulo") { LayersHeader(state.layers.size, vm::deleteAllLayers) }
            if (state.layers.isEmpty()) {
                item(key = "capas-vacio") { EmptyLayersHint() }
            }
            items(state.layers, key = { it.id }) { layer ->
                LayerCard(
                    layer = layer,
                    bars = state.bars,
                    clock = clock,
                    onEnabledChange = { vm.setLayerEnabled(layer.id, it) },
                    onVolumeChange = { vm.setLayerVolume(layer.id, it) },
                    onDelete = { vm.deleteLayer(layer.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}
