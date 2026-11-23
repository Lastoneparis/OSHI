//
//  OSHI-Bridging-Header.h
//  OSHI
//
//  Bridges llama.cpp C API into Swift
//

#include "llama.h"
#include "oshi_codec.h"
// __OPUS_CODEC_2026_09_23__ libopus 1.5.2 built from Vendor/opus (see Vendor/README-OSHI.md);
// only the six-function OSHI facade crosses into Swift.
#include "../Vendor/opus_oshi/oshi_opus.h"
