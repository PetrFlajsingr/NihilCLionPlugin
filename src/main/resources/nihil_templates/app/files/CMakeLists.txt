nihil_project(NAME {{Name}} VERSION 0.0.1)

nihil_application(
        NAME
        {{Name}}

        SOURCES
        main.cpp
{{#if has_dependencies}}

        DEPENDENCIES
        {{dependencies}}
{{/if}}

        STAGE
        paths.toml
        config
)
