/*
 * __OPUS_CODEC_2026_09_23__ libopus 1.5.2 (Vendor/opus, unmodified upstream files) built
 * as two translation units so the Xcode target needs two file entries instead of 137.
 *
 * WHY TWO: celt_encoder.c and celt_decoder.c each #define CELT_ENCODER_C / CELT_DECODER_C
 * before including opus_custom.h, which then declares different things; a header include
 * guard means only the FIRST one to include it gets its view. So each opens its own unit
 * (this one: encoder first, then everything else; oshi_opus_b.c: decoder).
 *
 * MEASURED 2026-09-23: this unity build encodes bit-identically to upstream's own CMake
 * build (fr/en speech + music, 24/32/40 kbit/s, complexity 5/8/10, FEC on/off).
 *
 * Configuration = upstream defaults: float build, no custom modes, no DNN (DRED/LACE off),
 * no hand-written assembly or runtime CPU detection (plain C, identical on every CPU).
 * Header search paths come from the per-file COMPILER_FLAGS in the Xcode project:
 *   -I$(SRCROOT)/Vendor/opus/include -I$(SRCROOT)/Vendor/opus/celt
 *   -I$(SRCROOT)/Vendor/opus/silk -I$(SRCROOT)/Vendor/opus/silk/float -I$(SRCROOT)/Vendor/opus/src -w
 */
#define OPUS_BUILD 1
#define VAR_ARRAYS 1
#define HAVE_LRINTF 1
#define HAVE_LRINT 1

#include "../opus/celt/celt_encoder.c"
#include "../opus/celt/bands.c"
#include "../opus/celt/celt.c"
#include "../opus/celt/cwrs.c"
#include "../opus/celt/entcode.c"
#include "../opus/celt/entdec.c"
#include "../opus/celt/entenc.c"
#include "../opus/celt/kiss_fft.c"
#include "../opus/celt/laplace.c"
#include "../opus/celt/mathops.c"
#include "../opus/celt/mdct.c"
#include "../opus/celt/modes.c"
#include "../opus/celt/pitch.c"
#include "../opus/celt/celt_lpc.c"
#include "../opus/celt/quant_bands.c"
#include "../opus/celt/rate.c"
#include "../opus/celt/vq.c"
#include "../opus/silk/CNG.c"
#include "../opus/silk/code_signs.c"
#include "../opus/silk/init_decoder.c"
#include "../opus/silk/decode_core.c"
#include "../opus/silk/decode_frame.c"
#include "../opus/silk/decode_parameters.c"
#include "../opus/silk/decode_indices.c"
#include "../opus/silk/decode_pulses.c"
#include "../opus/silk/decoder_set_fs.c"
#include "../opus/silk/dec_API.c"
#include "../opus/silk/enc_API.c"
#include "../opus/silk/encode_indices.c"
#include "../opus/silk/encode_pulses.c"
#include "../opus/silk/gain_quant.c"
#include "../opus/silk/interpolate.c"
#include "../opus/silk/LP_variable_cutoff.c"
#include "../opus/silk/NLSF_decode.c"
#include "../opus/silk/NSQ.c"
#include "../opus/silk/NSQ_del_dec.c"
#include "../opus/silk/PLC.c"
#include "../opus/silk/shell_coder.c"
#include "../opus/silk/tables_gain.c"
#include "../opus/silk/tables_LTP.c"
#include "../opus/silk/tables_NLSF_CB_NB_MB.c"
#include "../opus/silk/tables_NLSF_CB_WB.c"
#include "../opus/silk/tables_other.c"
#include "../opus/silk/tables_pitch_lag.c"
#include "../opus/silk/tables_pulses_per_block.c"
#include "../opus/silk/VAD.c"
#include "../opus/silk/control_audio_bandwidth.c"
#include "../opus/silk/quant_LTP_gains.c"
#include "../opus/silk/VQ_WMat_EC.c"
#include "../opus/silk/HP_variable_cutoff.c"
#include "../opus/silk/NLSF_encode.c"
#include "../opus/silk/NLSF_VQ.c"
#include "../opus/silk/NLSF_unpack.c"
#include "../opus/silk/NLSF_del_dec_quant.c"
#include "../opus/silk/process_NLSFs.c"
#include "../opus/silk/stereo_LR_to_MS.c"
#include "../opus/silk/stereo_MS_to_LR.c"
#include "../opus/silk/check_control_input.c"
#include "../opus/silk/control_SNR.c"
#include "../opus/silk/init_encoder.c"
#include "../opus/silk/control_codec.c"
#include "../opus/silk/A2NLSF.c"
#include "../opus/silk/ana_filt_bank_1.c"
#include "../opus/silk/biquad_alt.c"
#include "../opus/silk/bwexpander_32.c"
#include "../opus/silk/bwexpander.c"
#include "../opus/silk/debug.c"
#include "../opus/silk/decode_pitch.c"
#include "../opus/silk/inner_prod_aligned.c"
#include "../opus/silk/lin2log.c"
#include "../opus/silk/log2lin.c"
#include "../opus/silk/LPC_analysis_filter.c"
#include "../opus/silk/LPC_inv_pred_gain.c"
#include "../opus/silk/table_LSF_cos.c"
#include "../opus/silk/NLSF2A.c"
#include "../opus/silk/NLSF_stabilize.c"
#include "../opus/silk/NLSF_VQ_weights_laroia.c"
#include "../opus/silk/pitch_est_tables.c"
#include "../opus/silk/resampler.c"
#include "../opus/silk/resampler_down2_3.c"
#include "../opus/silk/resampler_down2.c"
#include "../opus/silk/resampler_private_AR2.c"
#include "../opus/silk/resampler_private_down_FIR.c"
#include "../opus/silk/resampler_private_IIR_FIR.c"
#include "../opus/silk/resampler_private_up2_HQ.c"
#include "../opus/silk/resampler_rom.c"
#include "../opus/silk/sigm_Q15.c"
#include "../opus/silk/sort.c"
#include "../opus/silk/sum_sqr_shift.c"
#include "../opus/silk/stereo_decode_pred.c"
#include "../opus/silk/stereo_encode_pred.c"
#include "../opus/silk/stereo_find_predictor.c"
#include "../opus/silk/stereo_quant_pred.c"
#include "../opus/silk/LPC_fit.c"
#include "../opus/silk/float/apply_sine_window_FLP.c"
#include "../opus/silk/float/corrMatrix_FLP.c"
#include "../opus/silk/float/encode_frame_FLP.c"
#include "../opus/silk/float/find_LPC_FLP.c"
#include "../opus/silk/float/find_LTP_FLP.c"
#include "../opus/silk/float/find_pitch_lags_FLP.c"
#include "../opus/silk/float/find_pred_coefs_FLP.c"
#include "../opus/silk/float/LPC_analysis_filter_FLP.c"
#include "../opus/silk/float/LTP_analysis_filter_FLP.c"
#include "../opus/silk/float/LTP_scale_ctrl_FLP.c"
#include "../opus/silk/float/noise_shape_analysis_FLP.c"
#include "../opus/silk/float/process_gains_FLP.c"
#include "../opus/silk/float/regularize_correlations_FLP.c"
#include "../opus/silk/float/residual_energy_FLP.c"
#include "../opus/silk/float/warped_autocorrelation_FLP.c"
#include "../opus/silk/float/wrappers_FLP.c"
#include "../opus/silk/float/autocorrelation_FLP.c"
#include "../opus/silk/float/burg_modified_FLP.c"
#include "../opus/silk/float/bwexpander_FLP.c"
#include "../opus/silk/float/energy_FLP.c"
#include "../opus/silk/float/inner_product_FLP.c"
#include "../opus/silk/float/k2a_FLP.c"
#include "../opus/silk/float/LPC_inv_pred_gain_FLP.c"
#include "../opus/silk/float/pitch_analysis_core_FLP.c"
#include "../opus/silk/float/scale_copy_vector_FLP.c"
#include "../opus/silk/float/scale_vector_FLP.c"
#include "../opus/silk/float/schur_FLP.c"
#include "../opus/silk/float/sort_FLP.c"
#include "../opus/src/opus.c"
#include "../opus/src/opus_decoder.c"
#include "../opus/src/opus_encoder.c"
#include "../opus/src/extensions.c"
#include "../opus/src/opus_multistream.c"
#include "../opus/src/opus_multistream_encoder.c"
#include "../opus/src/opus_multistream_decoder.c"
#include "../opus/src/repacketizer.c"
#include "../opus/src/opus_projection_encoder.c"
#include "../opus/src/opus_projection_decoder.c"
#include "../opus/src/mapping_matrix.c"
#include "../opus/src/analysis.c"
#include "../opus/src/mlp.c"
#include "../opus/src/mlp_data.c"

/* ---- OSHI facade (oshi_opus.h) ---- */
#include "oshi_opus.h"

void *oshi_opus_enc_create(int32_t bitrate, int32_t complexity) {
    int err = 0;
    OpusEncoder *e = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &err);
    if (!e || err != OPUS_OK) return NULL;
    opus_encoder_ctl(e, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(e, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(e, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(e, OPUS_SET_VBR(1));
    opus_encoder_ctl(e, OPUS_SET_VBR_CONSTRAINT(1));
    opus_encoder_ctl(e, OPUS_SET_INBAND_FEC(0));
    opus_encoder_ctl(e, OPUS_SET_DTX(0));
    return e;
}

void oshi_opus_enc_destroy(void *enc) { if (enc) opus_encoder_destroy((OpusEncoder *)enc); }

int32_t oshi_opus_encode(void *enc, const int16_t *pcm, int32_t frame_samples,
                         uint8_t *out, int32_t max_bytes) {
    if (!enc || !pcm || !out) return -1;
    return opus_encode((OpusEncoder *)enc, pcm, frame_samples, out, max_bytes);
}

void *oshi_opus_dec_create(void) {
    int err = 0;
    OpusDecoder *d = opus_decoder_create(48000, 1, &err);
    return (d && err == OPUS_OK) ? d : NULL;
}

void oshi_opus_dec_destroy(void *dec) { if (dec) opus_decoder_destroy((OpusDecoder *)dec); }

int32_t oshi_opus_decode(void *dec, const uint8_t *data, int32_t len,
                         int16_t *pcm, int32_t max_samples) {
    if (!dec || !pcm) return -1;
    if (!data || len <= 0) return opus_decode((OpusDecoder *)dec, NULL, 0, pcm, max_samples, 0);
    return opus_decode((OpusDecoder *)dec, data, len, pcm, max_samples, 0);
}
