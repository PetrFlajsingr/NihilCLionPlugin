package cz.nihil_engine.nihil_utils_plugin.args

import java.io.File

object NihilArgsConfigWriter {

    fun write(config: NihilArgsConfig, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(toToml(config))
    }

    fun toToml(config: NihilArgsConfig): String = buildString {
        for ((profileIndex, profile) in config.profiles.withIndex()) {
            if (profileIndex > 0) append('\n')

            append('[').append(profile.key).append(']').append('\n')
            append("label = ").append(quote(profile.label)).append('\n')
            append("filter = ").append(quote(profile.filter.pattern)).append('\n')

            for (arg in profile.args) {
                append('\n')
                append('[').append(profile.key).append(".args.").append(arg.key).append(']').append('\n')
                append("label = ").append(quote(arg.label)).append('\n')
                append("type = ").append(quote(typeName(arg.type))).append('\n')
                append("flag = ").append(quote(arg.flag)).append('\n')

                if (arg.default.isNotEmpty()) {
                    append("default = ").append(defaultLiteral(arg)).append('\n')
                }

                if (arg.options.isNotEmpty()) {
                    append("options = ").append(stringArray(arg.options)).append('\n')
                }

                if (arg.type == ArgType.DERIVED && arg.valueTemplate.isNotEmpty()) {
                    append("value = ").append(quote(arg.valueTemplate)).append('\n')
                }

                if (arg.type == ArgType.PATH) {
                    append("path_kind = ").append(quote(pathKindName(arg.pathKind))).append('\n')
                    append("path_direction = ").append(quote(pathDirectionName(arg.pathDirection))).append('\n')
                }

                if (arg.type == ArgType.INT) {
                    arg.min?.let { append("min = ").append(it).append('\n') }
                    arg.max?.let { append("max = ").append(it).append('\n') }
                }

                if (arg.type == ArgType.MULTI && arg.separator != ",") {
                    append("separator = ").append(quote(arg.separator)).append('\n')
                }
            }
        }
    }

    private fun defaultLiteral(arg: ArgDefinition): String = when (arg.type) {
        ArgType.BOOL -> if (arg.default.equals("true", ignoreCase = true)) "true" else "false"
        ArgType.INT -> arg.default.toIntOrNull()?.toString() ?: quote(arg.default)
        else -> quote(arg.default)
    }

    private fun stringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { quote(it) }

    private fun quote(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun typeName(t: ArgType): String = when (t) {
        ArgType.BOOL -> "bool"
        ArgType.SELECT -> "select"
        ArgType.TEXT -> "text"
        ArgType.PATH -> "path"
        ArgType.INT -> "int"
        ArgType.MULTI -> "multi"
        ArgType.DERIVED -> "derived"
    }

    private fun pathKindName(k: PathKind): String = when (k) {
        PathKind.FILE -> "file"
        PathKind.DIRECTORY -> "directory"
    }

    private fun pathDirectionName(d: PathDirection): String = when (d) {
        PathDirection.INPUT -> "input"
        PathDirection.OUTPUT -> "output"
    }
}