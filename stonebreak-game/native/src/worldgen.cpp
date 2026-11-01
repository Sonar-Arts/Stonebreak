// Stonebreak worldgen natives — ABI handshake (see include/stonebreak/worldgen.h).
#include "stonebreak/worldgen.h"

extern "C" {

int32_t sbw_abi_version(void) {
    return SBW_ABI_VERSION;
}

} // extern "C"
