@file:OptIn(ExperimentalMaterial3Api::class)

package com.tallermusica.loopera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LooperScreen(vm: LooperViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
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
        }
    }
}
