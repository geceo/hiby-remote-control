package io.github.hibyremote

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.hibyremote.remote.RemoteController
import io.github.hibyremote.ui.LibraryModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RemoteApp : Application() {
    lateinit var controller: RemoteController
        private set

    lateinit var platform: AndroidPlatform
        private set
    lateinit var library: LibraryModel
        private set

    override fun onCreate() {
        super.onCreate()
        platform = AndroidPlatform(this)
        controller = RemoteController(platform, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        library = LibraryModel(controller)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                controller.foreground = true
                controller.refresh()
            }

            override fun onStop(owner: LifecycleOwner) {
                controller.foreground = false
            }
        })
    }
}
