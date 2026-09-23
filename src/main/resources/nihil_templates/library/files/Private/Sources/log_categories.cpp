// Copyright (c) {{year}}.

/// @file log_categories.cpp
/// @brief
/// @author {{author}}

module;

module nihil.{{module}}:log_categories.impl;

import std;
import nihil.common;
{{#if profiling}}
import nihil.profiling;
{{/if}}
import :log_categories;
{{#if profiling}}
import :prof_categories;
{{/if}}

namespace {{namespace}} {
log::Category Log{{Name}}{"{{Name}}", log::Level::Info};
}
{{#if profiling}}

namespace nihil::prof {
Category {{Name}}{"{{Name}}", VerbosityLevel::Verbose, ColorRGBf{0.60f, 0.60f, 0.60f}};
}
{{/if}}
