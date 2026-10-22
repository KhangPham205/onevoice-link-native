package com.onevoice.link.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.onevoice.link.R
import com.onevoice.link.core.EngineBackend
import com.onevoice.link.headset.AuracastCapabilityProbe
import com.onevoice.link.headset.ConnectedHeadsetRepository
import com.onevoice.link.headset.HeadsetInventoryState
import com.onevoice.link.ui.screens.DashboardScreen
import com.onevoice.link.ui.screens.EngineController
import com.onevoice.link.ui.screens.SettingsScreen
import com.onevoice.link.ui.theme.Spacing
import kotlinx.coroutines.launch

private enum class AppDestination { DASHBOARD, SETTINGS }

/** Activity-level UI surface for navigation, Hybrid state, and headset inventory. */
@Composable
fun OneVoiceApp(
    micGranted: Boolean,
    micPermanentlyDenied: Boolean,
    bluetoothConnectGranted: Boolean,
    engine: EngineBackend,
    engineController: EngineController,
    onRequestMic: () -> Unit,
    onRequestBluetoothConnect: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val headsetRepository = remember(appContext) { ConnectedHeadsetRepository(appContext) }
    val auracastCapability = remember(bluetoothConnectGranted) {
        AuracastCapabilityProbe.read(appContext)
    }
    var headsetState by remember {
        mutableStateOf<HeadsetInventoryState>(HeadsetInventoryState.Loading)
    }
    var destination by rememberSaveable { mutableStateOf(AppDestination.DASHBOARD) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    DisposableEffect(destination, bluetoothConnectGranted, headsetRepository) {
        if (destination == AppDestination.SETTINGS) {
            headsetRepository.start(bluetoothConnectGranted) { headsetState = it }
        } else {
            headsetRepository.stop()
        }
        onDispose { headsetRepository.stop() }
    }

    BackHandler(enabled = destination == AppDestination.SETTINGS) {
        destination = AppDestination.DASHBOARD
    }

    fun selectDestination(next: AppDestination) {
        if (next == AppDestination.SETTINGS) {
            // Settings has no translation controls; never leave an invisible
            // microphone session running behind it.
            engineController.pauseLiveTranslation()
        }
        destination = next
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(Spacing.xl),
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.navigation_dashboard)) },
                    selected = destination == AppDestination.DASHBOARD,
                    onClick = { selectDestination(AppDestination.DASHBOARD) },
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.navigation_settings)) },
                    selected = destination == AppDestination.SETTINGS,
                    onClick = { selectDestination(AppDestination.SETTINGS) },
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
                Spacer(Modifier.height(Spacing.lg))
                Text(
                    text = stringResource(R.string.navigation_hybrid_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xl),
                )
            }
        },
        modifier = modifier,
    ) {
        when (destination) {
            AppDestination.DASHBOARD -> DashboardScreen(
                micGranted = micGranted,
                micPermanentlyDenied = micPermanentlyDenied,
                engine = engine,
                controller = engineController,
                onRequestMic = onRequestMic,
                onOpenMenu = { scope.launch { drawerState.open() } },
            )

            AppDestination.SETTINGS -> SettingsScreen(
                state = headsetState,
                onBack = { destination = AppDestination.DASHBOARD },
                onRequestBluetoothConnect = onRequestBluetoothConnect,
                onRefresh = headsetRepository::refresh,
                onLanguageChanged = headsetRepository::setLanguage,
                auracastCapability = auracastCapability,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
            )
        }
    }
}
