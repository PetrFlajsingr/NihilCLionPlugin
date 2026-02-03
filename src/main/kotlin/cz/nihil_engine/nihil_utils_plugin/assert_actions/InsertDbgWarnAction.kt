package cz.nihil_engine.nihil_utils_plugin.assert_actions

class InsertDbgWarnAction : InsertErrorActionBase() {
    override val errorName get() = "NIHIL_DBG_WARN"
    override val actionName get() = "InsertDbgWarn"
}