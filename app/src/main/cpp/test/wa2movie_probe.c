// Desktop-built, device-run smoke test for wa2movie.c without JNI: decodes a whole movie
// (video converted to RGBA like the Bitmap path, audio resampled to s16) and reports speed.
// Build: see test_probe.sh. Not part of the APK.
#include "../wa2movie.c"

#include <time.h>

static double now_s(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s movie.pak [frameIndex out.ppm]\n", argv[0]);
        return 2;
    }
    av_log_set_level(AV_LOG_WARNING);
    Movie *m = movie_open(argv[1], -1);
    if (!m) {
        fprintf(stderr, "open failed\n");
        return 1;
    }
    AVStream *vs = m->fmt->streams[m->vidx];
    AVRational sar = av_guess_sample_aspect_ratio(m->fmt, vs, NULL);
    printf("video %dx%d sar %d:%d dur %lld ms audio rate %d\n", m->vdec->width, m->vdec->height, sar.num,
           sar.den, (long long)(m->fmt->duration / 1000), m->adec ? m->out_rate : 0);
    int w = m->vdec->width, h = m->vdec->height;
    uint8_t *rgba = malloc((size_t)w * h * 4);
    double t0 = now_s();
    long frames = 0, shorts = 0;
    int64_t last_pts = -1, first_pts = -1;
    uint64_t checksum = 0;
    int vdone = 0;
    while (!vdone || !m->adone) {
        if (!vdone) {
            if (decode_video(m) < 0) {
                vdone = 1;
            } else {
                AVFrame *f = m->vframe;
                int64_t pts = frame_pts_ms(m, f);
                if (first_pts < 0) first_pts = pts;
                if (pts < last_pts) printf("non-monotonic pts %lld after %lld\n", (long long)pts, (long long)last_pts);
                last_pts = m->last_vpts_ms = pts;
                m->sws = sws_getCachedContext(m->sws, f->width, f->height, f->format, w, h, AV_PIX_FMT_RGBA,
                                              SWS_BILINEAR, NULL, NULL, NULL);
                uint8_t *dst[4] = {rgba};
                int ls[4] = {w * 4};
                sws_scale(m->sws, (const uint8_t *const *)f->data, f->linesize, 0, f->height, dst, ls);
                checksum += rgba[(h / 2) * w * 4 + (w / 2) * 4 + 1];
                if (argc >= 4 && frames == atol(argv[2])) {  // dump frame N as binary PPM
                    FILE *o = fopen(argv[3], "wb");
                    if (o) {
                        fprintf(o, "P6\n%d %d\n255\n", w, h);
                        for (long i = 0; i < (long)w * h; i++) fwrite(rgba + i * 4, 1, 3, o);
                        fclose(o);
                    }
                }
                av_frame_unref(f);
                frames++;
            }
        }
        // Pull audio roughly in step (~1600 samples per video frame).
        for (int k = 0; k < 4 && !m->adone; k++) {
            if (m->abuf_pos >= m->abuf_len) {
                if (decode_audio(m) < 0) {
                    m->adone = 1;
                    break;
                }
            }
            shorts += m->abuf_len - m->abuf_pos;
            m->abuf_pos = m->abuf_len;
        }
    }
    double dt = now_s() - t0;
    printf("frames %ld pts %lld..%lld ms, audio %.2f s, decode %.2f s = %.1f fps (x%.2f realtime), mid-G sum %llu\n",
           frames, (long long)first_pts, (long long)last_pts, shorts / 2.0 / (m->out_rate ? m->out_rate : 1), dt,
           frames / dt, (last_pts / 1000.0) / dt, (unsigned long long)checksum);
    free(rgba);
    movie_free(m);
    return 0;
}
