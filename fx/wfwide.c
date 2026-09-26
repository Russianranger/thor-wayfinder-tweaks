/*
 * Wayfinder stereo widener — an Android audio effect library (libwfwide.so).
 *
 * Why native: widening mixes the channels (mid/side), and the effects an app can attach
 * (EQ, dynamics, limiter) are per-channel. So this library is loaded by the audio HAL
 * (registered in audio_effects.xml by Wayfinder's root installer, see AudioFx.kt) and
 * applied to music/game streams as a post-processing effect.
 *
 * The math is the classic mid/side width control (musicdsp.org "stereo width control
 * obtained via transformation matrix"): with width w,
 *     m = (L + R) / 2,  s = (R - L) * w / 2,   L' = m - s,  R' = m + s
 * w = 1 leaves the sound unchanged; the community Thor speaker preset uses w = 2
 * (L' = 1.5 L - 0.5 R). Only on the built-in speaker; bypassed on anything else.
 *
 * Wayfinder's change to that preset — MONO BASS: the side signal is split at ~300 Hz
 * (two cascaded one-pole low-passes, and its exact complement) and only the part above
 * is widened. The Thor's speakers are centimetres apart: widened low frequencies mostly
 * make the two cones cancel and spend their tiny excursion on bass they can't play.
 * Complementary split → width 1 is still bit-for-bit neutral.
 *
 * Live control (no restart): the property `persist.wayfinder.wide` = width, read every
 * ~0.25 s ("0" or "1" = bypass). Written from scratch against the public AOSP effect
 * API (hardware/audio_effect.h); the structs below mirror that header's layout.
 */
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <errno.h>
#include <sys/system_properties.h>
#include <android/log.h>

#define TAG "WayfinderFx"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* ── AOSP effect API (hardware/audio_effect.h, system/audio_effect.h) ─────────── */
typedef struct { uint32_t timeLow; uint16_t timeMid; uint16_t timeHiAndVersion; uint16_t clockSeq; uint8_t node[6]; } effect_uuid_t;
typedef struct {
    effect_uuid_t type, uuid;
    uint32_t apiVersion, flags;
    uint16_t cpuLoad, memoryUsage;
    char name[64], implementor[64];
} effect_descriptor_t;
typedef struct { size_t frameCount; union { void *raw; float *f32; int32_t *s32; int16_t *s16; uint8_t *u8; }; } audio_buffer_t;
typedef int32_t (*buffer_function_t)(void *cookie, audio_buffer_t *buffer);
typedef struct { buffer_function_t getBuffer, releaseBuffer; void *cookie; } buffer_provider_t;
typedef struct {
    audio_buffer_t buffer;
    uint32_t samplingRate, channels;
    buffer_provider_t bufferProvider;
    uint8_t format, accessMode;
    uint16_t mask;
} buffer_config_t;
typedef struct { buffer_config_t inputCfg, outputCfg; } effect_config_t;
struct effect_interface_s;
typedef struct effect_interface_s **effect_handle_t;
struct effect_interface_s {
    int32_t (*process)(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out);
    int32_t (*command)(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize, void *pCmdData, uint32_t *replySize, void *pReplyData);
    int32_t (*get_descriptor)(effect_handle_t self, effect_descriptor_t *pDescriptor);
    int32_t (*process_reverse)(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out);
};
typedef struct {
    uint32_t tag, version;
    const char *name, *implementor;
    int32_t (*create_effect)(const effect_uuid_t *uuid, int32_t sessionId, int32_t ioId, effect_handle_t *pHandle);
    int32_t (*release_effect)(effect_handle_t handle);
    int32_t (*get_descriptor)(const effect_uuid_t *uuid, effect_descriptor_t *pDescriptor);
} audio_effect_library_t;

#define API_VERSION(maj, min) (((maj) << 16) | (min))
#define AUDIO_EFFECT_LIBRARY_TAG ((('A') << 24) | (('E') << 16) | (('L') << 8) | ('T'))
enum { CMD_INIT = 0, CMD_SET_CONFIG, CMD_RESET, CMD_ENABLE, CMD_DISABLE, CMD_SET_PARAM, CMD_SET_PARAM_DEFERRED,
       CMD_SET_PARAM_COMMIT, CMD_GET_PARAM, CMD_SET_DEVICE, CMD_SET_VOLUME, CMD_SET_AUDIO_MODE,
       CMD_SET_CONFIG_REVERSE, CMD_SET_INPUT_DEVICE, CMD_GET_CONFIG };
#define FLAG_TYPE_INSERT   0u
#define FLAG_INSERT_FIRST  (1u << 3)   /* before the output mix's EQ / limiter */
#define FLAG_DEVICE_IND    (1u << 9)
#define FMT_PCM_16  1
#define FMT_PCM_FLOAT 5
#define CH_STEREO 0x3u
#define DEV_OUT_SPEAKER 0x2u
#define ACCESS_ACCUMULATE 2

/* Our identity. type = impl: nobody else implements this effect. */
static const effect_descriptor_t kDesc = {
    .type = { 0x7f3a1c2e, 0x51b4, 0x4e8f, 0x9a21, { 0x57, 0x61, 0x79, 0x66, 0x6e, 0x64 } },
    .uuid = { 0x7f3a1c2f, 0x51b4, 0x4e8f, 0x9a21, { 0x57, 0x61, 0x79, 0x66, 0x6e, 0x64 } },
    .apiVersion = API_VERSION(2, 0),
    .flags = FLAG_TYPE_INSERT | FLAG_INSERT_FIRST | FLAG_DEVICE_IND,
    .cpuLoad = 1, .memoryUsage = 1,
    .name = "Wayfinder stereo widener",
    .implementor = "Thor Wayfinder",
};

typedef struct {
    const struct effect_interface_s *itfe;   /* must be first: effect_handle_t points here */
    effect_config_t cfg;
    int enabled;
    int speaker;          /* current output is the built-in speaker */
    float width;
    uint32_t sinceRead;   /* frames since the property was last read */
    float lp1, lp2;       /* side-signal low-pass state (mono bass) */
    int debug;            /* persist.wayfinder.wide.debug = 1: log levels in/out every ~2 s */
    double inL, inR, outL, outR; uint32_t sinceLog;
} ctx_t;

static int uuidEq(const effect_uuid_t *a, const effect_uuid_t *b) { return memcmp(a, b, sizeof(effect_uuid_t)) == 0; }

static void readWidth(ctx_t *c) {
    char v[PROP_VALUE_MAX] = {0};
    float w = 1.0f;   /* no setting = neutral (Wayfinder sets the width while its speaker fix is on) */
    if (__system_property_get("persist.wayfinder.wide", v) > 0) w = strtof(v, NULL);
    if (!(w >= 0.0f && w <= 6.0f)) w = 1.0f;
    c->width = w;
    char d[PROP_VALUE_MAX] = {0};
    c->debug = __system_property_get("persist.wayfinder.wide.debug", d) > 0 && d[0] == '1';
}

static int32_t fxProcess(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out) {
    ctx_t *c = (ctx_t *)self;
    if (!c || !in || !out || in->frameCount != out->frameCount) return -EINVAL;
    size_t n = in->frameCount;
    c->sinceRead += (uint32_t)n;
    if (c->sinceRead >= c->cfg.inputCfg.samplingRate / 4) { c->sinceRead = 0; readWidth(c); }
    const int stereo = c->cfg.inputCfg.channels == CH_STEREO;
    const int accumulate = c->cfg.outputCfg.accessMode == ACCESS_ACCUMULATE;
    const float w = (c->enabled && c->speaker && stereo && c->width > 0.0f) ? c->width : 1.0f;
    /* One-pole coefficient for ~300 Hz at this rate (cascaded twice = 12 dB/oct). */
    const float a = 1.0f - expf(-2.0f * 3.14159265f * 300.0f / (float)(c->cfg.inputCfg.samplingRate ? c->cfg.inputCfg.samplingRate : 48000));
    if (c->cfg.inputCfg.format == FMT_PCM_FLOAT) {
        const float *src = in->f32; float *dst = out->f32;
        if (!stereo) {   /* not stereo: pass through untouched */
            size_t ch = __builtin_popcount(c->cfg.inputCfg.channels);
            size_t total = n * (ch ? ch : 2);
            if (accumulate) for (size_t i = 0; i < total; i++) dst[i] += src[i];
            else if (dst != src) memcpy(dst, src, total * sizeof(float));
            return 0;
        }
        for (size_t i = 0; i < n; i++) {
            float l = src[2 * i], r = src[2 * i + 1];
            float m = (l + r) * 0.5f, sd = (r - l) * 0.5f;
            c->lp1 += a * (sd - c->lp1); c->lp2 += a * (c->lp1 - c->lp2);   /* side below ~300 Hz */
            float s = c->lp2 + (sd - c->lp2) * w;                         /* widen only above it */
            float ol = m - s, or_ = m + s;
            if (c->debug) { c->inL += l * l; c->inR += r * r; c->outL += ol * ol; c->outR += or_ * or_; }
            if (accumulate) { dst[2 * i] += ol; dst[2 * i + 1] += or_; }
            else { dst[2 * i] = ol; dst[2 * i + 1] = or_; }
        }
        if (c->debug && (c->sinceLog += (uint32_t)n) >= c->cfg.inputCfg.samplingRate * 2) {
            double f = c->sinceLog;
            LOGI("width %.2f speaker %d  in L %.4f R %.4f  ->  out L %.4f R %.4f (rms)", w, c->speaker,
                 sqrt(c->inL / f), sqrt(c->inR / f), sqrt(c->outL / f), sqrt(c->outR / f));
            c->inL = c->inR = c->outL = c->outR = 0; c->sinceLog = 0;
        }
    } else if (c->cfg.inputCfg.format == FMT_PCM_16) {
        const int16_t *src = in->s16; int16_t *dst = out->s16;
        if (!stereo) {
            size_t ch = __builtin_popcount(c->cfg.inputCfg.channels);
            size_t total = n * (ch ? ch : 2);
            if (dst != src) memcpy(dst, src, total * sizeof(int16_t));
            return 0;
        }
        for (size_t i = 0; i < n; i++) {
            float l = src[2 * i], r = src[2 * i + 1];
            float m = (l + r) * 0.5f, sd = (r - l) * 0.5f;
            c->lp1 += a * (sd - c->lp1); c->lp2 += a * (c->lp1 - c->lp2);   /* side below ~300 Hz */
            float s = c->lp2 + (sd - c->lp2) * w;                         /* widen only above it */
            float ol = m - s, or_ = m + s;
            if (accumulate) { ol += dst[2 * i]; or_ += dst[2 * i + 1]; }
            dst[2 * i] = (int16_t)fmaxf(-32768.f, fminf(32767.f, ol));
            dst[2 * i + 1] = (int16_t)fmaxf(-32768.f, fminf(32767.f, or_));
        }
    } else {
        return -EINVAL;
    }
    return 0;
}

static int32_t fxCommand(effect_handle_t self, uint32_t cmd, uint32_t cmdSize, void *cmdData, uint32_t *replySize, void *reply) {
    ctx_t *c = (ctx_t *)self;
    if (!c) return -EINVAL;
    switch (cmd) {
    case CMD_INIT:
    case CMD_RESET:
        if (reply && replySize && *replySize >= sizeof(int32_t)) *(int32_t *)reply = 0;
        return 0;
    case CMD_SET_CONFIG:
        if (!cmdData || cmdSize < sizeof(effect_config_t)) return -EINVAL;
        memcpy(&c->cfg, cmdData, sizeof(effect_config_t));
        {
            int ok = (c->cfg.inputCfg.format == FMT_PCM_FLOAT || c->cfg.inputCfg.format == FMT_PCM_16) &&
                     c->cfg.inputCfg.format == c->cfg.outputCfg.format && c->cfg.inputCfg.channels == c->cfg.outputCfg.channels;
            if (reply && replySize && *replySize >= sizeof(int32_t)) *(int32_t *)reply = ok ? 0 : -EINVAL;
        }
        return 0;
    case CMD_GET_CONFIG:
        if (!reply || !replySize || *replySize < sizeof(effect_config_t)) return -EINVAL;
        memcpy(reply, &c->cfg, sizeof(effect_config_t));
        return 0;
    case CMD_ENABLE:
    case CMD_DISABLE:
        c->enabled = cmd == CMD_ENABLE;
        if (reply && replySize && *replySize >= sizeof(int32_t)) *(int32_t *)reply = 0;
        return 0;
    case CMD_SET_DEVICE:
        /* One or more audio_devices_t words: widen only while the speaker is among them. */
        if (cmdData && cmdSize >= sizeof(uint32_t)) {
            const uint32_t *d = (const uint32_t *)cmdData; int spk = 0;
            for (uint32_t i = 0; i < cmdSize / sizeof(uint32_t); i++) if (d[i] & DEV_OUT_SPEAKER) spk = 1;
            if (spk != c->speaker) LOGI("output %s the speaker", spk ? "is" : "is not");
            c->speaker = spk;
        }
        return 0;
    case CMD_SET_PARAM:
    case CMD_SET_PARAM_DEFERRED:
    case CMD_SET_PARAM_COMMIT:
    case CMD_GET_PARAM:
        if (reply && replySize && *replySize >= sizeof(int32_t)) *(int32_t *)reply = -EINVAL;
        return 0;
    default:
        /* Volume, audio mode, offload…: nothing to do. */
        return 0;
    }
}

static int32_t fxGetDescriptor(effect_handle_t self, effect_descriptor_t *d) {
    if (!self || !d) return -EINVAL;
    *d = kDesc;
    return 0;
}

static const struct effect_interface_s kItfe = { fxProcess, fxCommand, fxGetDescriptor, NULL };

static int32_t libCreate(const effect_uuid_t *uuid, int32_t sessionId, int32_t ioId, effect_handle_t *handle) {
    (void)sessionId; (void)ioId;
    if (!uuid || !handle || !uuidEq(uuid, &kDesc.uuid)) return -EINVAL;
    ctx_t *c = calloc(1, sizeof(ctx_t));
    if (!c) return -ENOMEM;
    c->itfe = &kItfe;
    c->speaker = 1;   /* until told otherwise: the Thor's usual output */
    c->cfg.inputCfg.samplingRate = c->cfg.outputCfg.samplingRate = 48000;
    c->cfg.inputCfg.channels = c->cfg.outputCfg.channels = CH_STEREO;
    c->cfg.inputCfg.format = c->cfg.outputCfg.format = FMT_PCM_FLOAT;
    readWidth(c);
    *handle = (effect_handle_t)c;
    LOGI("created (session %d, io %d, width %.2f)", sessionId, ioId, c->width);
    return 0;
}

static int32_t libRelease(effect_handle_t handle) {
    if (!handle) return -EINVAL;
    free(handle);
    return 0;
}

static int32_t libGetDescriptor(const effect_uuid_t *uuid, effect_descriptor_t *d) {
    if (!uuid || !d || !uuidEq(uuid, &kDesc.uuid)) return -EINVAL;
    *d = kDesc;
    return 0;
}

__attribute__((visibility("default")))
/* AUDIO_EFFECT_LIBRARY_INFO_SYM in the AOSP header: the loader dlsym()s "AELI". */
audio_effect_library_t AELI = {
    .tag = AUDIO_EFFECT_LIBRARY_TAG,
    .version = API_VERSION(3, 0),
    .name = "Wayfinder effects",
    .implementor = "Thor Wayfinder",
    .create_effect = libCreate,
    .release_effect = libRelease,
    .get_descriptor = libGetDescriptor,
};
