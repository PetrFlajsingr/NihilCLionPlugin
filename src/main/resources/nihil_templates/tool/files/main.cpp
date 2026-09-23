// Copyright (c) {{year}}.

/// @file main.cpp
/// @brief {{description}}
/// @author {{author}}

#include <NihilLog/log.hpp>
#include <NihilToolsBase/ToolMain.hpp>

import std;
import nihil.common;
import nihil.tools_base;
import nihil.cli;
import nihil.log;

namespace nihil::tools {

constinit log::Category Log{{Name}}{"{{Name}}", log::Level::Trace};

class {{Name}}Exe final : public CLIToolExeBase {
  public:
    [[nodiscard]] int run([[maybe_unused]] runtime::Runtime &appRuntime) override {
        NIHIL_LOG_INFO(Log{{Name}}, "Input: '{}'", input->getValue().string<char>());
        return 0;
    }

  protected:
    [[nodiscard]] cli::ArgParser &getArgParser() override {
        input = &argParser.addArgument<std::filesystem::path>(
            {.shortName = "i", .longName = "input", .desc = "Input file", .defaultValue = std::filesystem::path{}});
        return argParser;
    }

  private:
    cli::ArgParser argParser{"Nihil{{Name}}", "{{description}}"};
    cli::ArgParser::Argument<std::filesystem::path> *input{nullptr};
};

} // namespace nihil::tools

NIHIL_TOOL_MAIN(nihil::tools::{{Name}}Exe, "Nihil{{Name}}")
