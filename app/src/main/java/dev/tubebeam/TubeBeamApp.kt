package dev.tubebeam

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TubeBeamApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // RSA keygen takes a moment; do it off the main thread so the first connect is instant.
        appScope.launch(Dispatchers.IO) {
            runCatching { container.keyStore.ensureReady() }
                .onFailure { Log.e(TAG, "ADB key generation failed", it) }
        }
    }

    companion object {
        private const val TAG = "TubeBeam"
        fun container(context: Context): AppContainer =
            (context.applicationContext as TubeBeamApp).container
    }
}
