// Copyright (c) {{year}}.

/// @file main.cpp
/// @brief {{description}}
/// @author {{author}}

#include <NihilApp/AppMain.hpp>
#include <NihilLog/log.hpp>

import nihil.app;
import nihil.common;
import nihil.cli;
import nihil.log;

namespace nihil {

log::Category Log{{Name}}{"{{Name}}", log::Level::Trace};

class {{Name}} final : public app::Application {
  public:
    void configureArgs([[maybe_unused]] cli::ArgParser &parser) override {}

    void configure(app::AppConfig &config) override {
        config.windowTitle = String{"Nihil {{Name}}", memtrack::AppTag};
        config.windowSize = platform::Window::Size{1'280u, 720u};
    }

    void onStartup(app::AppContext &context) override {
        NIHIL_LOG_INFO(Log{{Name}}, "Started on the '{}' backend", context.getRenderBackend().name);
    }

    void onFixedUpdate([[maybe_unused]] const app::Duration step) override {}

    [[nodiscard]] bool onUpdate([[maybe_unused]] const app::FrameContext &frame) override { return true; }

    void onShutdown() override { NIHIL_LOG_INFO(Log{{Name}}, "Shutting down"); }
};

} // namespace nihil

NIHIL_APP_MAIN(nihil::{{Name}}, "Nihil {{Name}}")
