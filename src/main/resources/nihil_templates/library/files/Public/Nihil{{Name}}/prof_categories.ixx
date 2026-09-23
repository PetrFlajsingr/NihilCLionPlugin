{{#if profiling}}
// Copyright (c) {{year}}.

/// @file prof_categories.ixx
/// @brief
/// @author {{author}}

module;

#include <nihil_{{name_lower}}_export.h>

export module nihil.{{module}}:prof_categories;

import std;
import nihil.common;
export import nihil.profiling;

export namespace nihil::prof {
NIHIL{{NAME_UPPER}}_EXPORT extern Category {{Name}};
}
{{/if}}
