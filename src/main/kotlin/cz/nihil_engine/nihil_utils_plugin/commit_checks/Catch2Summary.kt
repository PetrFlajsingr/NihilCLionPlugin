package cz.nihil_engine.nihil_utils_plugin.commit_checks

/** Test case counts from a Catch2 run's summary line, as `src/tests/run_all_tests.py` reads them. */
data class Catch2Summary(val cases: Int, val failed: Int) {

    companion object {
        // Compact reporter: "All tests passed (171 assertions in 38 test cases)"
        //                   "Failed 2 test cases, failed 3 assertions."  / "Failed 1 test case, passed 12 test cases..."
        // Console reporter: "test cases:  12 |  11 passed | 1 failed"
        private val PASS = Regex("""All tests passed \((\d+) assertions? in (\d+) test cases?\)""")
        private val COMPACT_FAIL = Regex("""Failed (\d+) test cases?,\s*passed (\d+) test cases?""")
        private val CONSOLE_TOTAL = Regex("""test cases:\s*(\d+)\b""")
        private val FAILED_TOKEN = Regex("""(\d+)\s*failed""")

        /** Null when the output has no summary, e.g. the executable crashed. */
        fun parse(output: String): Catch2Summary? {
            PASS.find(output)?.let { return Catch2Summary(it.groupValues[2].toInt(), 0) }
            COMPACT_FAIL.find(output)?.let {
                val failed = it.groupValues[1].toInt()
                return Catch2Summary(failed + it.groupValues[2].toInt(), failed)
            }
            CONSOLE_TOTAL.find(output)?.let { total ->
                val line = output.substring(total.range.first).substringBefore('\n')
                val failed = FAILED_TOKEN.find(line)?.groupValues?.get(1)?.toInt() ?: 0
                return Catch2Summary(total.groupValues[1].toInt(), failed)
            }
            return null
        }
    }
}
