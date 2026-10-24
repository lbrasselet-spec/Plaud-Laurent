package fr.brasselet.plaud;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * v1.5 — Transcription Whisper sur le téléphone, sans internet ni compte.
 *
 * Whisper : appel de la bibliothèque native (whisper.cpp, voir android/jni).
 * Models : modèles Whisper téléchargés une fois (Hugging Face) dans le stockage de l'application.
 * Recorder : enregistrement du micro en WAV 16 kHz mono, le format attendu par Whisper.
 */
final class Whisper {

    static final int OK = 0, ABORTED = 1, ERR_MODEL = -1, ERR_AUDIO = -2, ERR_RUN = -3, ERR_MEMORY = -4;

    /** Reçoit les phrases (texte UTF-8, début et fin en millisecondes) et l'avancement (0 à 100). */
    interface Listener {
        void onSegment(byte[] utf8, long t0ms, long t1ms);

        void onProgress(int percent);
    }

    static final String LOAD_ERROR;

    static {
        String e = null;
        try {
            System.loadLibrary("plaudwhisper");
        } catch (Throwable t) {
            e = t.toString();
        }
        LOAD_ERROR = e;
    }

    static boolean available() {
        return LOAD_ERROR == null;
    }

    /** Transcrit wav à partir de fromMs. vad et prompt peuvent être null. Renvoie un des codes ci-dessus. */
    static native int run(String model, String vad, String wav, String lang, String prompt, int threads, long fromMs, Listener l);

    /** Interrompt la transcription en cours (run renvoie alors ABORTED). */
    static native void abort();

    /** Instructions du processeur utilisées (affichées dans le journal technique). */
    static native String info();

    static String message(int code) {
        switch (code) {
            case ERR_MODEL: return "modèle Whisper illisible : supprimez-le puis téléchargez-le de nouveau";
            case ERR_AUDIO: return "fichier audio illisible";
            case ERR_MEMORY: return "mémoire du téléphone insuffisante";
            default: return "échec de la transcription (code " + code + ")";
        }
    }

    /** En-tête WAV (44 octets) pour dataBytes octets de son 16 kHz, mono, 16 bits. */
    static byte[] wavHeader(long dataBytes) {
        long riff = Math.min(0xFFFFFFFFL, dataBytes + 36), data = Math.min(0xFFFFFFFFL, dataBytes);
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        le(h, 4, riff, 4);
        put(h, 8, "WAVEfmt ");
        le(h, 16, 16, 4);                       // taille du bloc fmt
        le(h, 20, 1, 2);                        // PCM
        le(h, 22, 1, 2);                        // mono
        le(h, 24, Recorder.RATE, 4);
        le(h, 28, Recorder.RATE * 2L, 4);       // octets par seconde
        le(h, 32, 2, 2);                        // octets par échantillon
        le(h, 34, 16, 2);                       // bits par échantillon
        put(h, 36, "data");
        le(h, 40, data, 4);
        return h;
    }

    private static void put(byte[] b, int at, String s) {
        for (int i = 0; i < s.length(); i++) b[at + i] = (byte) s.charAt(i);
    }

    private static void le(byte[] b, int at, long v, int n) {
        for (int i = 0; i < n; i++) b[at + i] = (byte) (v >> (8 * i));
    }

    /** Durée en millisecondes d'un WAV écrit par Recorder (d'après la taille du fichier). */
    static long durationMs(File wav) {
        long bytes = wav.length() - 44;
        return bytes <= 0 ? 0 : bytes * 1000 / (Recorder.RATE * 2L);
    }

    /** Modèle de détection de la parole (inclus dans l'application), recopié une fois dans ses fichiers. */
    static String vadModel(Context c) {
        File f = new File(c.getFilesDir(), "ggml-silero-v6.2.0.bin");
        if (f.length() > 100_000) return f.getAbsolutePath();
        try (InputStream in = c.getAssets().open("ggml-silero-v6.2.0.bin"); FileOutputStream o = new FileOutputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } catch (Exception e) {
            f.delete();
            MainActivity.Store.log("détection de la parole indisponible : " + e);
            return null;
        }
        return f.getAbsolutePath();
    }

    /** Modèles Whisper proposés, téléchargés par le gestionnaire de téléchargements d'Android. */
    static final class Models {
        static final String BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";
        // identifiant, fichier, taille exacte en octets, libellé
        static final String[][] LIST = {
                {"precis", "ggml-large-v3-turbo-q5_0.bin", "574041195", "Précis (recommandé) — 574 Mo"},
                {"rapide", "ggml-small-q5_1.bin", "190085487", "Rapide — 190 Mo"},
                {"essai", "ggml-tiny.bin", "77691713", "Essai — 78 Mo"},   // test automatique uniquement
        };
        static final String PREFS = "plaud", CHOSEN = "w_model";

        static String[] get(String id) {
            for (String[] m : LIST) if (m[0].equals(id)) return m;
            return null;
        }

        static String chosen(Context c) {
            String id = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHOSEN, "precis");
            return get(id) == null ? "precis" : id;
        }

        static File dir(Context c) {
            File d = c.getExternalFilesDir("models");
            return d != null ? d : new File(c.getFilesDir(), "models");
        }

        static File file(Context c, String[] m) {
            return new File(dir(c), m[1]);
        }

        /** Chemin du modèle id s'il est complètement téléchargé, sinon null. */
        static String ready(Context c, String id) {
            String[] m = get(id);
            if (m == null) return null;
            refresh(c, m);
            File f = file(c, m);
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            boolean whole = f.length() == Long.parseLong(m[2]) || (f.length() > 0 && f.length() == p.getLong("ok_" + id, -1));
            return whole && p.getLong("dl_" + id, -1) < 0 ? f.getAbsolutePath() : null;
        }

        static void download(Context c, String id) {
            String[] m = get(id);
            if (m == null) return;
            cancel(c, id);
            File f = file(c, m);
            f.delete();
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(BASE + m[1]))
                    .setTitle("PLAUD Laurent — modèle Whisper")
                    .setDescription(m[3])
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(c, "models", m[1]);
            long dl = dm.enqueue(r);
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("dl_" + id, dl).remove("ok_" + id).remove("err_" + id).apply();
            MainActivity.Store.log("téléchargement du modèle " + id + " lancé");
        }

        static void cancel(Context c, String id) {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long dl = p.getLong("dl_" + id, -1);
            if (dl >= 0) {
                ((DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE)).remove(dl);
                p.edit().remove("dl_" + id).apply();
            }
        }

        static void delete(Context c, String id) {
            String[] m = get(id);
            if (m == null) return;
            cancel(c, id);
            file(c, m).delete();
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("ok_" + id).remove("err_" + id).apply();
            MainActivity.Store.log("modèle " + id + " supprimé");
        }

        /** Met à jour l'état d'un téléchargement terminé ou échoué. Renvoie le pourcentage en cours, ou -1. */
        private static int refresh(Context c, String[] m) {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long dl = p.getLong("dl_" + m[0], -1);
            if (dl < 0) return -1;
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            try (Cursor q = dm.query(new DownloadManager.Query().setFilterById(dl))) {
                if (q == null || !q.moveToFirst()) {
                    p.edit().remove("dl_" + m[0]).apply();
                    return -1;
                }
                int status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long done = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    long len = file(c, m).length();
                    p.edit().remove("dl_" + m[0]).putLong("ok_" + m[0], len).apply();
                    MainActivity.Store.log("modèle " + m[0] + " téléchargé : " + len + " octets");
                    return -1;
                }
                if (status == DownloadManager.STATUS_FAILED) {
                    int reason = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    p.edit().remove("dl_" + m[0]).putString("err_" + m[0], "échec du téléchargement (code " + reason + ")").apply();
                    dm.remove(dl);
                    file(c, m).delete();
                    MainActivity.Store.log("téléchargement du modèle " + m[0] + " : échec " + reason);
                    return -1;
                }
                if (total <= 0) total = Long.parseLong(m[2]);
                int pct = (int) Math.max(0, Math.min(99, done * 100 / total));
                if (status == DownloadManager.STATUS_PAUSED) p.edit().putString("err_" + m[0], "en attente du réseau (Wi-Fi conseillé)").apply();
                else p.edit().remove("err_" + m[0]).apply();
                return pct;
            } catch (Exception e) {
                return -1;
            }
        }

        /** État de tous les modèles, pour la page Réglages. */
        static String state(Context c) {
            try {
                JSONArray a = new JSONArray();
                SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                for (String[] m : LIST) {
                    int pct = refresh(c, m);
                    String st = pct >= 0 ? "downloading" : ready(c, m[0]) != null ? "ready" : "absent";
                    a.put(new JSONObject().put("id", m[0]).put("label", m[3]).put("state", st).put("pct", pct)
                            .put("note", p.getString("err_" + m[0], "")));
                }
                return new JSONObject().put("lib", available()).put("libError", LOAD_ERROR == null ? "" : LOAD_ERROR)
                        .put("chosen", chosen(c)).put("free", dir(c).getUsableSpace()).put("list", a).toString();
            } catch (Exception e) {
                return "{\"lib\":false,\"list\":[]}";
            }
        }
    }

    /** Enregistre le micro dans un fichier WAV (16 kHz, mono, 16 bits), avec pause possible. */
    static final class Recorder implements Runnable {
        static final int RATE = 16000;

        final File file;
        private final AudioRecord rec;
        private final Thread thread;
        private volatile boolean running = true;
        volatile boolean paused;
        volatile long samples;    // échantillons écrits dans le fichier
        volatile String error;

        Recorder(File f) throws Exception {
            file = f;
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2) * 2);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                throw new Exception("micro indisponible");
            }
            rec.startRecording();
            thread = new Thread(this, "plaud-audio");
            thread.start();
        }

        @Override
        public void run() {
            byte[] buf = new byte[RATE / 5 * 2];   // 200 ms
            try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                out.setLength(0);
                out.write(wavHeader(0));
                long patched = 0;
                while (running) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n < 0) {
                        error = "lecture du micro impossible (" + n + ")";
                        break;
                    }
                    if (n == 0 || paused) continue;
                    out.write(buf, 0, n);
                    samples += n / 2;
                    // En-tête mis à jour toutes les 10 s : le fichier reste lisible si l'application est fermée brutalement.
                    if (samples - patched > RATE * 10L) {
                        patch(out);
                        patched = samples;
                    }
                }
                patch(out);
            } catch (Exception e) {
                error = e.toString();
            }
        }

        private void patch(RandomAccessFile out) throws Exception {
            long end = out.getFilePointer();
            out.seek(0);
            out.write(wavHeader(end - 44));
            out.seek(end);
        }

        /** Arrête l'enregistrement et finalise le fichier. */
        void finish() {
            running = false;
            try { rec.stop(); } catch (Exception e) { /* rien */ }
            try { thread.join(3000); } catch (InterruptedException e) { /* rien */ }
            rec.release();
        }
    }
}
