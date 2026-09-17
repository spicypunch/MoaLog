package kr.jm.moalog.appshell

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kr.jm.moalog.household.HouseholdSessionStatus
import kr.jm.moalog.household.MoaLogHouseholdSessionManager
import kr.jm.moalog.sync.MoaLogSyncManager
import kotlinx.coroutines.launch

@Composable
internal fun SyncLifecycleEffect(
    enabled: Boolean,
    syncManager: MoaLogSyncManager,
    householdManager: MoaLogHouseholdSessionManager,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val activeManager = rememberUpdatedState(syncManager)
    val activeHouseholdManager = rememberUpdatedState(householdManager)

    DisposableEffect(enabled, context, lifecycleOwner) {
        if (!enabled) return@DisposableEffect onDispose { }

        fun synchronize() {
            scope.launch {
                activeHouseholdManager.value.refresh()
                if (activeHouseholdManager.value.state.value.status == HouseholdSessionStatus.READY) {
                    activeManager.value.synchronize()
                }
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) synchronize()
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val connectivity = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = synchronize()
        }
        val callbackRegistered = runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            true
        }.getOrDefault(false)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (callbackRegistered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }
}
