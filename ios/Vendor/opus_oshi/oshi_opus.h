/*
 * __OPUS_CODEC_2026_09_23__ OSHI facade over the vendored libopus (Vendor/opus, 1.5.2).
 *
 * Swift cannot call the variadic opus_encoder_ctl/opus_decoder_ctl, so the few settings
 * the call codec needs are fixed here, and only these six functions cross into Swift
 * (OSHI/OSHI-Bridging-Header.h). No opus.h in the bridging header, so the app target
 * needs no extra header search path — the two .c files below carry their own flags.
 *
 * The codec has NO I/O of any kind: PCM in, bytes out, bytes in, PCM out. Encryption
 * (AES-GCM, VoiceCallManager) is applied to the bytes AFTER encoding.
 */
#ifndef OSHI_OPUS_H
#define OSHI_OPUS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** 48 kHz mono VOIP encoder, VBR (constrained), no in-band FEC, no DTX. NULL on failure. */
void *oshi_opus_enc_create(int32_t bitrate, int32_t complexity);
void oshi_opus_enc_destroy(void *enc);
/** One 20 ms frame (960 samples) → Opus packet. Returns bytes written, < 0 on error. */
int32_t oshi_opus_encode(void *enc, const int16_t *pcm, int32_t frame_samples,
                         uint8_t *out, int32_t max_bytes);

/** 48 kHz mono decoder. NULL on failure. */
void *oshi_opus_dec_create(void);
void oshi_opus_dec_destroy(void *dec);
/** Decode one packet; data == NULL or len == 0 ⇒ packet-loss concealment of
 *  `max_samples` (pass 960). Returns samples written, < 0 on error. */
int32_t oshi_opus_decode(void *dec, const uint8_t *data, int32_t len,
                         int16_t *pcm, int32_t max_samples);

#ifdef __cplusplus
}
#endif

#endif /* OSHI_OPUS_H */
