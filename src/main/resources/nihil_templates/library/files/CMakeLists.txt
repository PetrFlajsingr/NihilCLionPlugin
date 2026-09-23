nihil_project(
        NAME
        {{Name}}
        VERSION
        0.0.1
        LIBRARY)

set(SOURCES
)

set(MODULES
        Private/Sources/log_categories.cpp
        Public/Nihil{{Name}}/{{module_file}}.ixx
        Public/Nihil{{Name}}/log_categories.ixx
{{#if profiling}}
        Public/Nihil{{Name}}/prof_categories.ixx
{{/if}}
)

set(HEADERS
        Public/Nihil{{Name}}/config.hpp
        config/config_debug.hpp
        config/config_development.hpp
        config/config_profiling.hpp
        config/config_release.hpp
        config/config_test.hpp
)

nihil_library(
        NAME
        {{Name}}

        SOURCES
        ${SOURCES}

        HEADERS
        ${HEADERS}

        MODULES
        ${MODULES}
{{#if has_public_dependencies}}

        PUBLIC_DEPENDENCIES
        {{public_dependencies}}
{{/if}}
{{#if has_private_dependencies}}

        PRIVATE_DEPENDENCIES
        {{private_dependencies}}
{{/if}}
)
