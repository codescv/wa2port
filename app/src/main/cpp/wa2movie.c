// libwa2movie: minimal FFmpeg-backed decoder for the original WA2 movies (mvNN0.pak = raw ASF,
// WMV3 Main video + WMA v2 audio). Exposed to Kotlin as com.wa2port.android.movie.NativeMovieDecoder.
//
// Threading model: one thread pulls video (nextVideoFrame), another pulls audio (readAudio).
// Both share a single demuxer guarded by `lock`; packets of the other stream are parked in a
// per-stream FIFO. Each decoder / scaler / resampler is touched only by its own thread.
// open/close must not overlap with any other call (the Kotlin side guarantees this).

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/fifo.h>
#include <libavutil/log.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>

#define TAG "wa2movie"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Safety cap on parked packets per stream (if one consumer stalls, drop its oldest packets).
#define MAX_QUEUED_PACKETS 4096

typedef struct Movie {
    AVFormatContext *fmt;
    int fd;  // dup'ed fd when opened via openFd, else -1
    pthread_mutex_t lock;  // guards fmt reads, queues, demux_eof
    int demux_eof;

    // video
    int vidx;
    AVCodecContext *vdec;
    AVFifo *vq;
    AVFrame *vframe;
    struct SwsContext *sws;
    AVRational vtb;
    int64_t last_vpts_ms;
    int64_t frame_ms;  // nominal frame duration (for missing timestamps)
    int vflushed;

    // audio
    int aidx;
    AVCodecContext *adec;
    AVFifo *aq;
    AVFrame *aframe;
    SwrContext *swr;
    int out_rate;
    int16_t *abuf;  // interleaved stereo s16
    int abuf_cap;   // samples per channel
    int abuf_len;   // shorts
    int abuf_pos;   // shorts
    int aflushed;
    int adone;
} Movie;

static void log_cb(void *avcl, int level, const char *fmt, va_list vl) {
    (void)avcl;
    if (level > AV_LOG_WARNING) return;
    __android_log_vprint(level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "ffmpeg", fmt, vl);
}

static void free_queue(AVFifo **q) {
    if (!*q) return;
    AVPacket *p;
    while (av_fifo_read(*q, &p, 1) >= 0) av_packet_free(&p);
    av_fifo_freep2(q);
}

static void movie_free(Movie *m) {
    if (!m) return;
    free_queue(&m->vq);
    free_queue(&m->aq);
    avcodec_free_context(&m->vdec);
    avcodec_free_context(&m->adec);
    av_frame_free(&m->vframe);
    av_frame_free(&m->aframe);
    sws_freeContext(m->sws);
    swr_free(&m->swr);
    av_free(m->abuf);
    avformat_close_input(&m->fmt);
    if (m->fd >= 0) close(m->fd);
    pthread_mutex_destroy(&m->lock);
    free(m);
}

static AVCodecContext *open_decoder(AVStream *st, int threads) {
    const AVCodec *codec = avcodec_find_decoder(st->codecpar->codec_id);
    if (!codec) {
        LOGW("no decoder for %s", avcodec_get_name(st->codecpar->codec_id));
        return NULL;
    }
    AVCodecContext *c = avcodec_alloc_context3(codec);
    if (!c) return NULL;
    if (avcodec_parameters_to_context(c, st->codecpar) < 0) goto fail;
    c->pkt_timebase = st->time_base;
    c->thread_count = threads;
    if (avcodec_open2(c, codec, NULL) < 0) goto fail;
    return c;
fail:
    avcodec_free_context(&c);
    return NULL;
}

// Returns the next packet of the requested stream (caller frees), or NULL at end of input.
static AVPacket *next_packet(Movie *m, int video) {
    AVPacket *out = NULL;
    pthread_mutex_lock(&m->lock);
    for (;;) {
        AVFifo *q = video ? m->vq : m->aq;
        if (av_fifo_read(q, &out, 1) >= 0) break;
        out = NULL;
        if (m->demux_eof) break;
        AVPacket *pkt = av_packet_alloc();
        if (!pkt) break;
        int r = av_read_frame(m->fmt, pkt);
        if (r < 0) {
            av_packet_free(&pkt);
            if (r != AVERROR_EOF) LOGW("av_read_frame: %s", av_err2str(r));
            m->demux_eof = 1;
            continue;
        }
        AVFifo *dst = NULL;
        if (pkt->stream_index == m->vidx && m->vdec) dst = m->vq;
        else if (pkt->stream_index == m->aidx && m->adec) dst = m->aq;
        if (!dst) {
            av_packet_free(&pkt);
            continue;
        }
        if (av_fifo_can_read(dst) >= MAX_QUEUED_PACKETS) {
            AVPacket *old;
            if (av_fifo_read(dst, &old, 1) >= 0) av_packet_free(&old);
        }
        if (av_fifo_write(dst, &pkt, 1) < 0) av_packet_free(&pkt);
    }
    pthread_mutex_unlock(&m->lock);
    return out;
}

// Decodes the next video frame into m->vframe. Returns 0 on success, -1 at end of stream.
static int decode_video(Movie *m) {
    for (;;) {
        int r = avcodec_receive_frame(m->vdec, m->vframe);
        if (r == 0) return 0;
        if (r != AVERROR(EAGAIN)) return -1;  // EOF or fatal error
        if (m->vflushed) return -1;
        AVPacket *p = next_packet(m, 1);
        if (!p) m->vflushed = 1;
        r = avcodec_send_packet(m->vdec, p);  // NULL enters draining mode
        av_packet_free(&p);
        if (r < 0 && r != AVERROR(EAGAIN) && r != AVERROR_EOF) LOGW("video send_packet: %s", av_err2str(r));
    }
}

static int64_t frame_pts_ms(Movie *m, AVFrame *f) {
    int64_t ts = f->best_effort_timestamp;
    if (ts == AV_NOPTS_VALUE) ts = f->pts;
    if (ts == AV_NOPTS_VALUE) return m->last_vpts_ms < 0 ? 0 : m->last_vpts_ms + m->frame_ms;
    return av_rescale_q(ts, m->vtb, (AVRational){1, 1000});
}

// Decodes and resamples the next audio frame into abuf. Returns 0 on success, -1 at end.
static int decode_audio(Movie *m) {
    for (;;) {
        int r = avcodec_receive_frame(m->adec, m->aframe);
        if (r == 0) break;
        if (r == AVERROR(EAGAIN) && !m->aflushed) {
            AVPacket *p = next_packet(m, 0);
            if (!p) m->aflushed = 1;
            r = avcodec_send_packet(m->adec, p);
            av_packet_free(&p);
            if (r < 0 && r != AVERROR(EAGAIN) && r != AVERROR_EOF) LOGW("audio send_packet: %s", av_err2str(r));
            continue;
        }
        // End of stream: drain the resampler once.
        int need = swr_get_out_samples(m->swr, 0);
        if (need <= 0) return -1;
        if (need > m->abuf_cap) {
            av_free(m->abuf);
            m->abuf_cap = need;
            m->abuf = av_malloc((size_t)need * 2 * sizeof(int16_t));
            if (!m->abuf) { m->abuf_cap = 0; return -1; }
        }
        uint8_t *out = (uint8_t *)m->abuf;
        int got = swr_convert(m->swr, &out, need, NULL, 0);
        if (got <= 0) return -1;
        m->abuf_len = got * 2;
        m->abuf_pos = 0;
        return 0;
    }
    int need = swr_get_out_samples(m->swr, m->aframe->nb_samples);
    if (need > m->abuf_cap) {
        av_free(m->abuf);
        m->abuf_cap = need + 256;
        m->abuf = av_malloc((size_t)m->abuf_cap * 2 * sizeof(int16_t));
        if (!m->abuf) { m->abuf_cap = 0; av_frame_unref(m->aframe); return -1; }
    }
    uint8_t *out = (uint8_t *)m->abuf;
    int got = swr_convert(m->swr, &out, m->abuf_cap, (const uint8_t **)m->aframe->extended_data,
                          m->aframe->nb_samples);
    av_frame_unref(m->aframe);
    if (got < 0) return -1;
    m->abuf_len = got * 2;
    m->abuf_pos = 0;
    return 0;
}

static Movie *movie_open(const char *url, int fd) {
    Movie *m = calloc(1, sizeof(Movie));
    if (!m) return NULL;
    m->fd = fd;
    m->vidx = m->aidx = -1;
    m->last_vpts_ms = -1;
    pthread_mutex_init(&m->lock, NULL);

    int r = avformat_open_input(&m->fmt, url, NULL, NULL);
    if (r < 0) {
        LOGE("open %s: %s", url, av_err2str(r));
        goto fail;
    }
    if ((r = avformat_find_stream_info(m->fmt, NULL)) < 0) LOGW("find_stream_info: %s", av_err2str(r));

    long cpus = sysconf(_SC_NPROCESSORS_ONLN);
    int threads = cpus > 1 ? (int)(cpus > 4 ? 4 : cpus) : 1;
    m->vidx = av_find_best_stream(m->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (m->vidx >= 0) m->vdec = open_decoder(m->fmt->streams[m->vidx], threads);
    m->aidx = av_find_best_stream(m->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (m->aidx >= 0) m->adec = open_decoder(m->fmt->streams[m->aidx], 1);
    if (!m->vdec) {
        LOGE("no decodable video stream in %s", url);
        goto fail;
    }
    AVStream *vs = m->fmt->streams[m->vidx];
    m->vtb = vs->time_base;
    AVRational fr = av_guess_frame_rate(m->fmt, vs, NULL);
    m->frame_ms = (fr.num > 0 && fr.den > 0 && fr.num / fr.den < 120) ? (int64_t)(1000LL * fr.den / fr.num) : 33;

    if (m->adec) {
        AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
        m->out_rate = m->adec->sample_rate > 0 ? m->adec->sample_rate : 48000;
        if (swr_alloc_set_opts2(&m->swr, &stereo, AV_SAMPLE_FMT_S16, m->out_rate, &m->adec->ch_layout,
                                m->adec->sample_fmt, m->adec->sample_rate, 0, NULL) < 0 ||
            swr_init(m->swr) < 0) {
            LOGW("swresample init failed; movie plays without audio");
            swr_free(&m->swr);
            avcodec_free_context(&m->adec);
        }
    }
    m->vq = av_fifo_alloc2(64, sizeof(AVPacket *), AV_FIFO_FLAG_AUTO_GROW);
    m->aq = av_fifo_alloc2(64, sizeof(AVPacket *), AV_FIFO_FLAG_AUTO_GROW);
    m->vframe = av_frame_alloc();
    m->aframe = av_frame_alloc();
    if (!m->vq || !m->aq || !m->vframe || !m->aframe) goto fail;
    if (!m->adec) m->adone = 1;
    return m;
fail:
    m->fd = -1;  // the caller still owns fd on failure
    movie_free(m);
    return NULL;
}

// ---- JNI --------------------------------------------------------------------------------------

#define JNI_FN(name) Java_com_wa2port_android_movie_NativeMovieDecoder_##name

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    av_log_set_level(AV_LOG_WARNING);
    av_log_set_callback(log_cb);
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeOpen)(JNIEnv *env, jclass clazz, jstring jpath) {
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return 0;
    Movie *m = movie_open(path, -1);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return (jlong)(intptr_t)m;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeOpenFd)(JNIEnv *env, jclass clazz, jint fd) {
    (void)env;
    (void)clazz;
    int own = dup(fd);
    if (own < 0) return 0;
    char url[64];
    snprintf(url, sizeof(url), "file:/proc/self/fd/%d", own);
    Movie *m = movie_open(url, own);
    if (!m) close(own);
    return (jlong)(intptr_t)m;
}

JNIEXPORT void JNICALL JNI_FN(nativeClose)(JNIEnv *env, jclass clazz, jlong h) {
    (void)env;
    (void)clazz;
    movie_free((Movie *)(intptr_t)h);
}

// info[0]=width info[1]=height info[2]=sarNum info[3]=sarDen info[4]=durationMs info[5]=sampleRate(0=no audio)
JNIEXPORT void JNICALL JNI_FN(nativeInfo)(JNIEnv *env, jclass clazz, jlong h, jlongArray out) {
    (void)clazz;
    Movie *m = (Movie *)(intptr_t)h;
    AVStream *vs = m->fmt->streams[m->vidx];
    AVRational sar = av_guess_sample_aspect_ratio(m->fmt, vs, NULL);
    if (sar.num <= 0 || sar.den <= 0) sar = (AVRational){1, 1};
    jlong info[6];
    info[0] = m->vdec->width;
    info[1] = m->vdec->height;
    info[2] = sar.num;
    info[3] = sar.den;
    info[4] = m->fmt->duration != AV_NOPTS_VALUE ? m->fmt->duration / 1000 : -1;
    info[5] = m->adec ? m->out_rate : 0;
    (*env)->SetLongArrayRegion(env, out, 0, 6, info);
}

// Decodes frames until one has pts >= dropBeforeMs, converts it to RGBA into the bitmap (scaled to
// the bitmap's size) and returns its pts in ms; returns -1 at end of stream, -2 on bitmap error.
JNIEXPORT jlong JNICALL JNI_FN(nativeNextVideoFrame)(JNIEnv *env, jclass clazz, jlong h, jobject bitmap,
                                                     jlong dropBeforeMs) {
    (void)clazz;
    Movie *m = (Movie *)(intptr_t)h;
    int64_t pts;
    for (;;) {
        if (decode_video(m) < 0) {
            m->vdec->skip_frame = AVDISCARD_DEFAULT;
            return -1;
        }
        pts = frame_pts_ms(m, m->vframe);
        m->last_vpts_ms = pts;
        if (pts + m->frame_ms > dropBeforeMs) break;
        // Late: discard this frame and decode only reference frames until we catch up.
        m->vdec->skip_frame = AVDISCARD_NONREF;
        av_frame_unref(m->vframe);
    }
    m->vdec->skip_frame = AVDISCARD_DEFAULT;

    AndroidBitmapInfo bi;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bitmap, &bi) < 0 || bi.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        av_frame_unref(m->vframe);
        return -2;
    }
    AVFrame *f = m->vframe;
    m->sws = sws_getCachedContext(m->sws, f->width, f->height, (enum AVPixelFormat)f->format, (int)bi.width,
                                  (int)bi.height, AV_PIX_FMT_RGBA, SWS_BILINEAR, NULL, NULL, NULL);
    if (m->sws) {
        uint8_t *dst[4] = {pixels, NULL, NULL, NULL};
        int lines[4] = {(int)bi.stride, 0, 0, 0};
        sws_scale(m->sws, (const uint8_t *const *)f->data, f->linesize, 0, f->height, dst, lines);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    av_frame_unref(f);
    return m->sws ? pts : -2;
}

// Fills `out` with interleaved stereo s16 at the native sample rate. Returns the number of shorts
// written (> 0), or -1 at end of audio. Blocks only for decoding (never on playback).
JNIEXPORT jint JNICALL JNI_FN(nativeReadAudio)(JNIEnv *env, jclass clazz, jlong h, jshortArray out) {
    (void)clazz;
    Movie *m = (Movie *)(intptr_t)h;
    jsize cap = (*env)->GetArrayLength(env, out) & ~1;
    jsize filled = 0;
    while (filled < cap) {
        if (m->abuf_pos < m->abuf_len) {
            jsize n = m->abuf_len - m->abuf_pos;
            if (n > cap - filled) n = cap - filled;
            (*env)->SetShortArrayRegion(env, out, filled, n, m->abuf + m->abuf_pos);
            m->abuf_pos += n;
            filled += n;
            continue;
        }
        if (m->adone) break;
        if (decode_audio(m) < 0) m->adone = 1;
    }
    return filled > 0 ? filled : -1;
}
