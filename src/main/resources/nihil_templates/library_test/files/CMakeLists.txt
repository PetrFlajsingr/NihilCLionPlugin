nihil_project(
        NAME
        {{Name}}
        VERSION
        0.0.1
        TEST)

set(SOURCES
        {{Name}}Tests.cpp
        ../LoggerStartup.cpp
)

nihil_test_executable(
        NAME
        {{Name}}
        SOURCES
        ${SOURCES}
        DEPENDENCIES
        {{dependencies}}
)
