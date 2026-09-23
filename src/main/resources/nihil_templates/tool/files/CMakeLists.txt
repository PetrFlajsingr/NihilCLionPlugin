nihil_project(
        NAME
        {{Name}}
        VERSION
        0.0.1
        TOOL)

nihil_tool_executable(
        NAME
        {{Name}}

        SOURCES
        main.cpp

        DEPENDENCIES
        {{dependencies}}
)
