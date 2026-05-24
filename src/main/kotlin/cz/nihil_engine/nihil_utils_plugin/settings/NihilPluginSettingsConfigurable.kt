package cz.nihil_engine.nihil_utils_plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class NihilPluginSettingsConfigurable : Configurable {

    private val component = panel {
        group("Tool Runner") {
            row {
                checkBox("Enable debug logging for tool actions")
                    .bindSelected(NihilPluginSettings.getInstance()::isDebugLoggingEnabled)
            }
        }
    }

    override fun getDisplayName(): String = "Nihil Utils"

    override fun createComponent(): JComponent = component

    override fun isModified(): Boolean = component.isModified()

    override fun apply() = component.apply()

    override fun reset() = component.reset()
}
