package cz.nihil_engine.nihil_utils_plugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@State(name = "NihilPluginSettings", storages = [Storage("nihil_plugin_settings.xml")])
@Service(Service.Level.APP)
class NihilPluginSettings : PersistentStateComponent<NihilPluginSettings.State> {

    data class State(var debugLogging: Boolean = false)

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var isDebugLoggingEnabled: Boolean
        get() = myState.debugLogging
        set(value) { myState.debugLogging = value }

    companion object {
        fun getInstance(): NihilPluginSettings = service()
    }
}