nihil_project(
        NAME
        {{Name}}
        VERSION
        0.0.1
        LIBRARY
)

nihil_interface_library(
        NAME
        {{Name}}
{{#if has_dependencies}}
        DEPENDENCIES
        {{dependencies}}
{{/if}}
)
