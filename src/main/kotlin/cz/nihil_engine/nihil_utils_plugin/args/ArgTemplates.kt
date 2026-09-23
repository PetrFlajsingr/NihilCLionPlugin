package cz.nihil_engine.nihil_utils_plugin.args

/* Resolver for templates in strings */
object ArgTemplates {

    private val REFERENCE = Regex("""\$\{([\w.:-]+)}""")
    private const val ENV_PREFIX = "env:"

    data class Resolved(val value: String, val problems: List<String> = emptyList())

    fun hasReferences(value: String): Boolean = REFERENCE.containsMatchIn(value)

    fun resolveAll(
        raw: Map<String, String>,
        builtins: Map<String, String>,
        env: (String) -> String? = System::getenv,
    ): Map<String, Resolved> {
        val done = HashMap<String, Resolved>()
        val inProgress = LinkedHashSet<String>()

        fun resolve(key: String): Resolved {
            done[key]?.let { return it }
            val template = raw.getValue(key)
            inProgress += key
            val problems = mutableListOf<String>()
            val value = REFERENCE.replace(template) { match ->
                val name = match.groupValues[1]
                when {
                    name.startsWith(ENV_PREFIX) -> {
                        val variable = name.removePrefix(ENV_PREFIX)
                        env(variable) ?: "".also { problems += "environment variable $variable is not set" }
                    }
                    name in builtins -> builtins.getValue(name)
                    name in inProgress -> match.value.also {
                        val cycle = inProgress.toList().dropWhile { it != name } + name
                        problems += "reference cycle: ${cycle.joinToString(" -> ")}"
                    }
                    name in raw -> resolve(name).value
                    else -> match.value.also { problems += "unknown reference \${$name}" }
                }
            }
            inProgress -= key
            return Resolved(value, problems).also { done[key] = it }
        }

        for (key in raw.keys) resolve(key)
        return done
    }
}
