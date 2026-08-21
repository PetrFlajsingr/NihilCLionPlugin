package cz.nihil_engine.nihil_utils_plugin.renderdoc

import cz.nihil_engine.nihil_utils_plugin.config.RunConfigData
import java.io.File
import java.nio.file.Paths

object RenderDocCapGenerator {

    fun generate(data: RunConfigData): File {
        val json = """
            {
                "rdocCaptureSettings": 1,
                "settings": {
                    "autoStart": false,
                    "commandLine": "${jsonEscape(data.args)}",
                    "environment": [
                    ],
                    "executable": "${jsonEscapePath(data.exePath)}",
                    "inject": false,
                    "numQueuedFrames": 0,
                    "options": {
                        "allowFullscreen": true,
                        "allowVSync": true,
                        "apiValidation": false,
                        "captureAllCmdLists": false,
                        "captureCallstacks": false,
                        "captureCallstacksOnlyDraws": false,
                        "debugOutputMute": true,
                        "delayForDebugger": 0,
                        "hookIntoChildren": false,
                        "refAllResources": false,
                        "softMemoryLimit": 0,
                        "verifyBufferAccess": false
                    },
                    "queuedFrameCap": 0,
                    "workingDir": "${jsonEscapePath(data.workingDir ?: Paths.get(data.exePath).parent.toString())}"
                }
            }
        """.trimIndent()

        val file = File.createTempFile("renderdoc-", ".cap")
        file.writeText(json)
        return file
    }

    private fun jsonEscape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun jsonEscapePath(path: String): String = jsonEscape(path.replace("\\", "/"))
}