package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.intellij.util.xmlb.annotations.XMap

data class PresetChoice(val preset: ArgPreset, val personal: Boolean)

@Service(Service.Level.PROJECT)
@State(name = "NihilArgsPresets", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class NihilArgsPresetStore : PersistentStateComponent<NihilArgsPresetStore.StoreState> {

    class StoreState {
        @XCollection(style = XCollection.Style.v2)
        var presets: MutableList<Entry> = mutableListOf()
    }

    @Tag("preset")
    class Entry {
        @Attribute var profile: String = ""
        @Attribute var name: String = ""
        @XMap var values: MutableMap<String, String> = mutableMapOf()
    }

    private var state = StoreState()

    override fun getState(): StoreState = state

    override fun loadState(loaded: StoreState) {
        state = loaded
    }

    @Synchronized
    fun presets(profileKey: String): List<ArgPreset> =
        state.presets.filter { it.profile == profileKey }.map { ArgPreset(it.name, it.name, it.values.toMap()) }

    /** Saves [values] as [name], replacing a personal preset of the same name. */
    @Synchronized
    fun save(profileKey: String, name: String, values: Map<String, String>) {
        state.presets.removeIf { it.profile == profileKey && it.name == name }
        state.presets += Entry().apply {
            profile = profileKey
            this.name = name
            this.values = values.toMutableMap()
        }
    }

    @Synchronized
    fun delete(profileKey: String, name: String) {
        state.presets.removeIf { it.profile == profileKey && it.name == name }
    }

    companion object {
        fun getInstance(project: Project): NihilArgsPresetStore = project.getService(NihilArgsPresetStore::class.java)
    }
}
