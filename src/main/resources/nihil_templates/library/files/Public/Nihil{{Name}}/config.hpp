/// @file config.hpp
/// @brief Include utility for Nihil{{Name}} config file
/// @author {{author}}

#pragma once

#include <NihilMacros/nihil.hpp>

#if NIHIL_IS_BUILD_TYPE(NIHIL_BUILD_TYPE_DEBUG)
#    include "../../config/config_debug.hpp"
#elif NIHIL_IS_BUILD_TYPE(NIHIL_BUILD_TYPE_DEVELOPMENT)
#    include "../../config/config_development.hpp"
#elif NIHIL_IS_BUILD_TYPE(NIHIL_BUILD_TYPE_PROFILING)
#    include "../../config/config_profiling.hpp"
#elif NIHIL_IS_BUILD_TYPE(NIHIL_BUILD_TYPE_RELEASE)
#    include "../../config/config_release.hpp"
#elif NIHIL_IS_BUILD_TYPE(NIHIL_BUILD_TYPE_TEST)
#    include "../../config/config_test.hpp"
#endif
