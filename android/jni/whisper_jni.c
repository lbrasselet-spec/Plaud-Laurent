/*
 * PLAUD Laurent v1.5 — pont entre l'application (Whisper.java) et whisper.cpp.
 *
 * Lit le fichier WAV enregistré pendant l'entretien (16 kHz, mono, 16 bits), le transcrit
 * avec le modèle Whisper téléchargé sur le téléphone, et renvoie chaque phrase à Java
 * dès qu'elle est prête (Whisper.Listener.onSegment), avec l'avancement en pourcentage.
 * La transcription peut reprendre en cours de fichier (fromMs) après une interruption.
 */
#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "PLAUD", __VA_ARGS__)
#else
#define LOG(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

#define RATE 16000

/* Codes renvoyés à Java (voir Whisper.java) */
#define OK 0
#define ABORTED 1
#define ERR_MODEL (-1)
#define ERR_AUDIO (-2)
#define ERR_RUN (-3)
#define ERR_MEMORY (-4)

static atomic_int stop_requested;

typedef struct {
    JNIEnv *env;
    jobject listener;
    jmethodID on_segment, on_progress;
    int64_t base_cs;   /* début de la portion transcrite, en centièmes de seconde */
} callbacks;

static void on_log(enum ggml_log_level level, const char *text, void *data) {
    (void) data;
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) LOG("whisper : %s", text);
}

static void on_new_segment(struct whisper_context *ctx, struct whisper_state *state, int n_new, void *data) {
    (void) state;
    callbacks *c = data;
    JNIEnv *env = c->env;
    int n = whisper_full_n_segments(ctx);
    for (int i = n - n_new; i < n; i++) {
        /* Le texte passe en octets UTF-8 : Java le décode sans risque de caractère invalide. */
        const char *t = whisper_full_get_segment_text(ctx, i);
        jsize len = (jsize) strlen(t);
        jbyteArray bytes = (*env)->NewByteArray(env, len);
        if (bytes == NULL) return;
        (*env)->SetByteArrayRegion(env, bytes, 0, len, (const jbyte *) t);
        jlong t0 = (whisper_full_get_segment_t0(ctx, i) + c->base_cs) * 10;
        jlong t1 = (whisper_full_get_segment_t1(ctx, i) + c->base_cs) * 10;
        (*env)->CallVoidMethod(env, c->listener, c->on_segment, bytes, t0, t1);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, bytes);
    }
}

static void on_progress(struct whisper_context *ctx, struct whisper_state *state, int progress, void *data) {
    (void) ctx;
    (void) state;
    callbacks *c = data;
    (*c->env)->CallVoidMethod(c->env, c->listener, c->on_progress, (jint) progress);
    if ((*c->env)->ExceptionCheck(c->env)) (*c->env)->ExceptionClear(c->env);
}

static bool should_abort(void *data) {
    (void) data;
    return atomic_load(&stop_requested) != 0;
}

static bool before_encoder(struct whisper_context *ctx, struct whisper_state *state, void *data) {
    (void) ctx;
    (void) state;
    (void) data;
    return atomic_load(&stop_requested) == 0;
}

static uint32_t u32(const unsigned char *p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t) p[3] << 24; }
static uint16_t u16(const unsigned char *p) { return (uint16_t) (p[0] | p[1] << 8); }

/*
 * Lit un WAV PCM 16 bits à 16 kHz à partir de from_ms et le convertit en flottants.
 * Si l'en-tête n'a pas été finalisé (application fermée brutalement), la taille réelle
 * du fichier fait foi.
 */
static float *read_wav(const char *path, int64_t from_ms, int *n_out, int *err) {
    *n_out = 0;
    *err = ERR_AUDIO;
    FILE *f = fopen(path, "rb");
    if (f == NULL) return NULL;
    fseek(f, 0, SEEK_END);
    long file_len = ftell(f);
    fseek(f, 0, SEEK_SET);

    unsigned char h[12];
    if (fread(h, 1, 12, f) != 12 || memcmp(h, "RIFF", 4) != 0 || memcmp(h + 8, "WAVE", 4) != 0) {
        fclose(f);
        return NULL;
    }
    int channels = 0, bits = 0;
    uint32_t rate = 0;
    long data_pos = -1, data_len = 0;
    unsigned char ch[8];
    while (fread(ch, 1, 8, f) == 8) {
        uint32_t size = u32(ch + 4);
        long pos = ftell(f);
        if (memcmp(ch, "fmt ", 4) == 0) {
            unsigned char fmt[16];
            if (size < 16 || fread(fmt, 1, 16, f) != 16) break;
            if (u16(fmt) != 1) break;   /* PCM uniquement */
            channels = u16(fmt + 2);
            rate = u32(fmt + 4);
            bits = u16(fmt + 14);
        } else if (memcmp(ch, "data", 4) == 0) {
            data_pos = pos;
            data_len = (size == 0 || size == 0xFFFFFFFFu || pos + (long) size > file_len) ? file_len - pos : (long) size;
            break;
        }
        if (fseek(f, pos + (long) size + (size & 1), SEEK_SET) != 0) break;
    }
    if (data_pos < 0 || bits != 16 || rate != RATE || channels < 1 || channels > 2) {
        LOG("audio non reconnu : %d canal(aux), %u Hz, %d bits", channels, rate, bits);
        fclose(f);
        return NULL;
    }

    long frame = 2L * channels;
    long total = data_len / frame;
    long skip = (long) (from_ms * RATE / 1000);
    if (skip > total) skip = total;
    long n = total - skip;
    float *pcm = malloc(sizeof(float) * (size_t) (n > 0 ? n : 1));
    if (pcm == NULL) {
        *err = ERR_MEMORY;
        fclose(f);
        return NULL;
    }
    fseek(f, data_pos + skip * frame, SEEK_SET);

    enum { CHUNK = 16384 };
    int16_t buf[CHUNK * 2];
    long done = 0;
    while (done < n) {
        long want = n - done < CHUNK ? n - done : CHUNK;
        size_t got = fread(buf, (size_t) frame, (size_t) want, f);
        if (got == 0) break;
        for (size_t i = 0; i < got; i++) {
            /* Les octets sont en petit-boutiste, comme sur les processeurs ARM et x86. */
            float s = channels == 2 ? (buf[2 * i] + buf[2 * i + 1]) / 2.0f : buf[i];
            pcm[done + (long) i] = s / 32768.0f;
        }
        done += (long) got;
    }
    fclose(f);
    *n_out = (int) done;
    *err = OK;
    return pcm;
}

JNIEXPORT jint JNICALL
Java_fr_brasselet_plaud_Whisper_run(JNIEnv *env, jclass cls, jstring jmodel, jstring jvad, jstring jwav,
                                    jstring jlang, jstring jprompt, jint threads, jlong from_ms, jobject listener) {
    (void) cls;
    atomic_store(&stop_requested, 0);
    whisper_log_set(on_log, NULL);

    const char *model = (*env)->GetStringUTFChars(env, jmodel, NULL);
    const char *vad = jvad ? (*env)->GetStringUTFChars(env, jvad, NULL) : NULL;
    const char *wav = (*env)->GetStringUTFChars(env, jwav, NULL);
    const char *lang = (*env)->GetStringUTFChars(env, jlang, NULL);
    const char *prompt = jprompt ? (*env)->GetStringUTFChars(env, jprompt, NULL) : NULL;

    int result = OK, n = 0;
    struct whisper_context *ctx = NULL;
    float *pcm = read_wav(wav, from_ms, &n, &result);
    if (pcm == NULL) goto done;
    if (n < RATE / 2) goto done;   /* moins d'une demi-seconde : rien à transcrire */

    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    ctx = whisper_init_from_file_with_params(model, cp);
    if (ctx == NULL) {
        result = ERR_MODEL;
        goto done;
    }

    jclass lc = (*env)->GetObjectClass(env, listener);
    callbacks c = {
        .env = env,
        .listener = listener,
        .on_segment = (*env)->GetMethodID(env, lc, "onSegment", "([BJJ)V"),
        .on_progress = (*env)->GetMethodID(env, lc, "onProgress", "(I)V"),
        .base_cs = from_ms / 10,
    };

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : 4;
    p.language = lang;
    p.translate = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.no_context = true;
    p.suppress_blank = true;
    p.suppress_nst = true;
    /* Vocabulaire (marques, produits) répété à chaque fenêtre de 30 s pour garder la bonne orthographe. */
    if (prompt != NULL && prompt[0] != '\0') {
        p.initial_prompt = prompt;
        p.carry_initial_prompt = true;
    }
    /* Détection de la parole (Silero) : les silences sont sautés, ce qui évite les phrases inventées. */
    if (vad != NULL && vad[0] != '\0') {
        p.vad = true;
        p.vad_model_path = vad;
        p.vad_params = whisper_vad_default_params();
        p.vad_params.min_silence_duration_ms = 300;
        p.vad_params.speech_pad_ms = 200;
    }
    p.new_segment_callback = on_new_segment;
    p.new_segment_callback_user_data = &c;
    p.progress_callback = on_progress;
    p.progress_callback_user_data = &c;
    p.encoder_begin_callback = before_encoder;
    p.abort_callback = should_abort;

    LOG("whisper : %d s d'audio à partir de %lld ms, %d threads, vad %s",
        n / RATE, (long long) from_ms, p.n_threads, p.vad ? "oui" : "non");
    int rc = whisper_full(ctx, p, pcm, n);
    if (atomic_load(&stop_requested)) result = ABORTED;
    else if (rc != 0) result = ERR_RUN;

done:
    if (ctx) whisper_free(ctx);
    free(pcm);
    (*env)->ReleaseStringUTFChars(env, jmodel, model);
    if (vad) (*env)->ReleaseStringUTFChars(env, jvad, vad);
    (*env)->ReleaseStringUTFChars(env, jwav, wav);
    (*env)->ReleaseStringUTFChars(env, jlang, lang);
    if (prompt) (*env)->ReleaseStringUTFChars(env, jprompt, prompt);
    return result;
}

JNIEXPORT void JNICALL
Java_fr_brasselet_plaud_Whisper_abort(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    atomic_store(&stop_requested, 1);
}

JNIEXPORT jstring JNICALL
Java_fr_brasselet_plaud_Whisper_info(JNIEnv *env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
