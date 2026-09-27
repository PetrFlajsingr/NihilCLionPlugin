package cz.nihil_engine.nihil_utils_plugin.cvars

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CVarScannerTest {

    private fun names(text: String) = CVarScanner.scan(text).map { it.name to it.kind }

    @Test
    fun `typed, deduced and next-line declarations`() {
        val text = """
            cvar::Auto CVarCaptureResourceStacks{
                "r.rhi.resource_registry.capture_stacks", "Capture a creation stacktrace for every RHI resource", false};
            class RenderContext {
                cvar::Auto<bool> CVarVSync{"r.vsync", "Enable vertical synchronization", true, cvar::Property::Persist};
                cvar::Auto<std::chrono::microseconds> CVarBackgroundPeriod{
                    "prof.showcase.thread.period", "Background thread tick period", std::chrono::microseconds{2'000}, cvar::Property::Default,
                    cvar::RangeLimiter<std::chrono::microseconds>::Create(std::chrono::microseconds{100}, std::chrono::microseconds{100'000})};
                AutoConsoleVariable<i32> autoVar{"test.auto", "Auto value", 3};
            };
        """.trimIndent()
        val decls = CVarScanner.scan(text)
        assertEquals(
            listOf("r.rhi.resource_registry.capture_stacks", "r.vsync", "prof.showcase.thread.period", "test.auto"),
            decls.map { it.name },
        )
        assertTrue(decls.all { it.kind == CVarKind.VARIABLE })
        val first = decls.first()
        assertEquals(0, first.start)
        assertEquals(text.indexOf("r.rhi.resource_registry"), first.nameOffset)
        assertEquals("Capture a creation stacktrace for every RHI resource", first.help)
        assertEquals("Enable vertical synchronization", decls[1].help)
    }

    @Test
    fun `leading memtrack tag and external storage are skipped`() {
        val text = """
            cvar::Auto<bool> tagged{memtrack::ConsoleTag, "r.tagged", "Tagged", true};
            cvar::Auto<bool> stored{ExternalStorage<bool>{.target = &open}, "ui.panel.open", "Open", cvar::Property::Persist};
            cvar::Auto<bool> runtime{ExternalStorage<bool>{.target = &windowOpen}, String{panelConfig.cvarName}, MakeHelp(panelConfig)};
        """.trimIndent()
        assertEquals(listOf("r.tagged" to CVarKind.VARIABLE, "ui.panel.open" to CVarKind.VARIABLE), names(text))
    }

    @Test
    fun `runtime names, templates and the console library's own code are not declarations`() {
        val text = """
            channels.emplace_back(
                MakeUnique<cvar::Auto<bool>>(
                    memtrack::DebugRendererTag, GetChannelCVarName(channel),
                    Format("Draw debug shapes recorded on the {} channel", meta::GetEnumName(channel)), true));
            Vector<UniqueRef<cvar::Auto<bool>>> channels{memtrack::DebugRendererTag};
            template<typename T> using Auto = AutoConsoleVariable<T>;
            template<typename T> AutoConsoleVariable(String, String, T) -> AutoConsoleVariable<T>;
            AutoConsoleVariable<prof::VerbosityLevel> variable;
            variable{Format("prof.level.{}", inCategory.getName()), Format("Minimum level"), level};
            ConsoleCommandPtr createConsoleCommand(const String &name, String help, F &&function);
            console.createConsoleCommand(name, "Print everything the job system is currently holding", fn);
            // cvar::Auto<bool> commented{"r.commented", "no", true};
        """.trimIndent()
        assertEquals(emptyList<Pair<String, CVarKind>>(), names(text))
    }

    @Test
    fun `console commands`() {
        val text = """
            ConsoleManager::GetInstance().createConsoleCommand(
                "r.dump_rdg", "Dump current render graph in the console", [this](auto, ConsoleOutput &os) {
                    os << "Dumping RDG to log after next compilation";
                });
            const AutoConsoleCommand CmdSubgroupMode{
                "r.dbg_draw.subgroup.mode", "Subgroup filter mode: all | mute | solo", [](const std::span<String> args, ConsoleOutput &output) {}};
            ConsoleManager::GetInstance().createConsoleCommand("ResetCamera", "Reset camera to its default location", fn);
        """.trimIndent()
        val decls = CVarScanner.scan(text)
        assertEquals(listOf("r.dump_rdg", "r.dbg_draw.subgroup.mode", "ResetCamera"), decls.map { it.name })
        assertTrue(decls.all { it.kind == CVarKind.COMMAND })
        assertEquals("Subgroup filter mode: all | mute | solo", decls[1].help)
    }

    @Test
    fun `annotated struct prefix, not member level names`() {
        val text = """
            struct[[= ${'$'}cvar{"r.demo"}, = ${'$'}layout{.categories = CategoryStyle::CollapsingHeader}]]
                DemoRenderSettings {
                [[= ${'$'}description{"Samples"}, = ${'$'}values(1u, 2u, 4u, 8u)]] u32 msaa = 1;
                [[= ${'$'}cvar{"explicit_member"}]] f32 bias = 0.005f;
            };
        """.trimIndent()
        val decl = CVarScanner.scan(text).single()
        assertEquals("r.demo" to CVarKind.PREFIX, decl.name to decl.kind)
        assertEquals(text.indexOf("r.demo"), decl.nameOffset)
    }

    @Test
    fun `the same name declared in several apps is reported every time`() {
        val one = """cvar::Auto<DebugMode> cvarDebugMode{"test.debug_mode", "Debug mode for vizbuffer visualization", DebugMode::None};"""
        assertEquals(listOf("test.debug_mode", "test.debug_mode"), CVarScanner.scan("$one\n$one").map { it.name })
    }
}
