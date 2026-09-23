// Copyright (c) {{year}}.

/// @file log_categories.ixx
/// @brief
/// @author {{author}}

module;

#include <nihil_{{name_lower}}_export.h>

export module nihil.{{module}}:log_categories;

import std;
import nihil.common;

export namespace {{namespace}} {
NIHIL{{NAME_UPPER}}_EXPORT extern log::Category Log{{Name}};
} // namespace {{namespace}}

export namespace nihil::memtrack {
NIHIL{{NAME_UPPER}}_EXPORT constexpr Tag {{Name}}Tag{"{{Name}}"};
}
