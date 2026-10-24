package fr.brasselet.plaud;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * PLAUD Laurent — application Android.
 *
 * L'interface est la page index.html (la même que la version web), affichée dans une WebView.
 * La transcription tourne dans un service de premier plan (Rec) : elle continue téléphone
 * verrouillé, et les sons système (le bip) sont coupés pendant l'enregistrement.
 * La page dialogue avec ce code par l'objet JavaScript « PlaudNative ».
 * Les phrases reconnues sont conservées dans un fichier (Store) : rien n'est perdu si la page
 * est fermée ou l'écran éteint ; elle les récupère dès qu'elle est de nouveau affichée.
 *
 * v1.4 — Moteur de reconnaissance au choix (Réglages) parmi ceux installés sur le téléphone,
 * avec retour automatique à Google si le moteur choisi ne répond pas. L'application reçoit
 * aussi le texte « partagé » par une autre application (ex. une transcription Voice Inc).
 *
 * v1.5 — Moteur « Whisper sur le téléphone » : le son est enregistré pendant l'entretien,
 * puis transcrit sur le téléphone à l'arrêt, sans internet (voir Whisper.java et android/jni).
 * Un moteur choisi qui reste muet 30 s bascule aussi sur Google (cas de FUTO Voice Input).
 */
public class MainActivity extends Activity {

    static final String ORIGIN = "https://appassets.androidplatform.net/";
    private static final int REQ_PERMS = 1, REQ_FILE = 2;
    private static String shared;   // v1.4 — texte reçu par « Partager », en attente pour la page

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private boolean batteryAsked;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Store.init(this);
        getWindow().setStatusBarColor(0xFF1F3A5F);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        web.addJavascriptInterface(new Bridge(), "PlaudNative");
        final boolean autotest = getIntent().getBooleanExtra("autotest", false);
        final boolean whisperTest = getIntent().getBooleanExtra("whisper_test", false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return asset(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (u.toString().startsWith(ORIGIN)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { Store.log("lien : " + e); }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                Store.log("page chargée");
                // Test automatique (compilation GitHub) : démarre un enregistrement sans intervention.
                if (autotest) v.evaluateJavascript("document.getElementById('bStart').click()", null);
                if (whisperTest) new Thread(MainActivity.this::whisperSelfTest, "plaud-test").start();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Choisir le fichier"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        if (state == null) receive(getIntent());
        web.loadUrl(ORIGIN + "index.html");
        if (!askPermissions()) askBatteryOnce();
    }

    /** v1.4 — Application déjà ouverte : le texte partagé est transmis tout de suite à la page. */
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (receive(i) && web != null) web.evaluateJavascript("window.plaudShared && plaudShared()", null);
    }

    /** v1.4 — Garde le texte (ou le fichier .txt) envoyé par « Partager » depuis une autre application. */
    private boolean receive(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return false;
        CharSequence cs = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String t = cs == null ? null : cs.toString();
        if (t == null || t.trim().isEmpty()) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) t = read(u);
        }
        if (t == null || t.trim().isEmpty()) {
            Store.log("partage reçu sans texte");
            return false;
        }
        synchronized (MainActivity.class) { shared = t.trim(); }
        Store.log("texte reçu par partage : " + t.length() + " caractères");
        return true;
    }

    private String read(Uri u) {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 2_000_000) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } catch (Exception e) {
            Store.log("fichier partagé illisible : " + e);
            return null;
        }
    }

    /** Sert les fichiers de la page (assets/www) sous une adresse https locale. */
    private WebResourceResponse asset(Uri u) {
        if (!u.toString().startsWith(ORIGIN)) return null;
        String path = u.getPath();
        if (path == null || path.length() < 2) path = "/index.html";
        String mime = path.endsWith(".html") ? "text/html"
                : path.endsWith(".js") ? "text/javascript"
                : path.endsWith(".json") ? "application/json"
                : path.endsWith(".png") ? "image/png" : "application/octet-stream";
        try {
            return new WebResourceResponse(mime, "UTF-8", getAssets().open("www" + path));
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
        }
    }

    /** Demande le micro et les notifications si besoin. Renvoie true si une demande est affichée. */
    boolean askPermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (need.isEmpty()) return false;
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        return true;
    }

    /** Demande à Android de ne pas mettre l'application en veille (indispensable pour les longs entretiens). */
    void askBatteryOnce() {
        if (batteryAsked) return;
        batteryAsked = true;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()))
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Store.log("batterie : " + e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_PERMS) askBatteryOnce();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // garde la page ouverte en arrière-plan
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /**
     * v1.5 — Test automatique (compilation GitHub) : télécharge le petit modèle d'essai et une
     * phrase d'exemple, puis la transcrit. Le résultat est écrit dans le journal (« test whisper »).
     */
    private void whisperSelfTest() {
        try {
            if (!Whisper.available()) {
                Store.log("test whisper : bibliothèque absente — " + Whisper.LOAD_ERROR);
                return;
            }
            Store.log("test whisper : processeur " + Whisper.info());
            SharedPreferences p = getSharedPreferences(Rec.PREFS, MODE_PRIVATE);
            if (Whisper.Models.ready(this, "essai") == null) Whisper.Models.download(this, "essai");
            long end = System.currentTimeMillis() + 240_000;
            while (Whisper.Models.ready(this, "essai") == null && p.getLong("dl_essai", -1) >= 0 && System.currentTimeMillis() < end)
                Thread.sleep(2000);
            String model = Whisper.Models.ready(this, "essai");
            if (model == null) {
                Store.log("test whisper : modèle d'essai non téléchargé — " + Whisper.Models.state(this));
                return;
            }
            File wav = new File(getFilesDir(), "jfk.wav");
            HttpURLConnection c = (HttpURLConnection) new URL("https://raw.githubusercontent.com/ggml-org/whisper.cpp/v1.9.5/samples/jfk.wav").openConnection();
            try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(wav)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            final StringBuilder text = new StringBuilder();
            long t0 = System.currentTimeMillis();
            int rc = Whisper.run(model, Whisper.vadModel(this), wav.getAbsolutePath(), "en", "", 4, 0, new Whisper.Listener() {
                @Override public void onSegment(byte[] utf8, long a, long b) { text.append(new String(utf8, StandardCharsets.UTF_8)); }
                @Override public void onProgress(int percent) { }
            });
            Store.log("test whisper : code " + rc + " en " + (System.currentTimeMillis() - t0) + " ms — " + text.toString().trim());
        } catch (Exception e) {
            Store.log("test whisper : " + e);
        } finally {
            Store.log("test whisper : fin");
        }
    }

    /** Fonctions appelées par la page (window.PlaudNative). */
    final class Bridge {

        @JavascriptInterface
        public String start(String lang) {
            try {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(MainActivity.this::askPermissions);
                    return "{\"ok\":false,\"error\":\"not-allowed\"}";
                }
                String session = Store.open();
                Intent i = new Intent(MainActivity.this, Rec.class).setAction(Rec.START)
                        .putExtra("lang", lang == null || lang.isEmpty() ? "fr-FR" : lang);
                startForegroundService(i);
                return new JSONObject().put("ok", true).put("session", session).toString();
            } catch (Exception e) {
                Store.fail("service");
                Store.log("démarrage : " + e);
                return "{\"ok\":false,\"error\":\"service\"}";
            }
        }

        @JavascriptInterface
        public void stop() {
            Store.close();
            final Rec r = Rec.instance;
            if (r != null) runOnUiThread(r::requestStop);
        }

        @JavascriptInterface
        public String poll(String session, int from) {
            return Store.poll(session, from);
        }

        @JavascriptInterface
        public void share(String title, String text) {
            final Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text);
            runOnUiThread(() -> {
                try { startActivity(Intent.createChooser(s, "Envoyer à Claude")); }
                catch (Exception e) { Store.log("partage : " + e); }
            });
        }

        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("PLAUD", text));
            });
        }

        @JavascriptInterface
        public String saveFile(String name, String content) {
            try {
                byte[] data = content.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, name.endsWith(".json") ? "application/json" : "text/plain");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PLAUD");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new Exception("stockage indisponible");
                    try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                        if (o == null) throw new Exception("stockage indisponible");
                        o.write(data);
                    }
                    return "Enregistré dans Téléchargements/PLAUD : " + name;
                }
                File f = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                return "Enregistré : " + f.getAbsolutePath();
            } catch (Exception e) {
                Store.log("fichier : " + e);
                return "Enregistrement impossible : " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String log() {
            return Store.dump();
        }

        /** v1.4 — Moteurs de reconnaissance vocale installés et moteur choisi ("" = automatique). */
        @JavascriptInterface
        public String engines() {
            try {
                PackageManager pm = getPackageManager();
                JSONArray a = new JSONArray();
                for (ResolveInfo r : pm.queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0)) {
                    if (r.serviceInfo == null) continue;
                    String app = String.valueOf(r.serviceInfo.applicationInfo.loadLabel(pm));
                    String svc = String.valueOf(r.loadLabel(pm));
                    a.put(new JSONObject()
                            .put("id", new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name).flattenToShortString())
                            .put("label", app.equals(svc) ? app : app + " — " + svc)
                            .put("pkg", r.serviceInfo.packageName));
                }
                return new JSONObject().put("chosen", Rec.chosen(MainActivity.this)).put("list", a).toString();
            } catch (Exception e) {
                Store.log("liste des moteurs : " + e);
                return "{\"chosen\":\"\",\"list\":[]}";
            }
        }

        @JavascriptInterface
        public void setEngine(String id) {
            if (id == null) id = "";
            getSharedPreferences(Rec.PREFS, MODE_PRIVATE).edit().putString(Rec.ENGINE, id).apply();
            Store.log("moteur choisi : " + (id.isEmpty() ? "automatique (Google)" : id));
        }

        /** v1.5 — État des modèles Whisper (bibliothèque présente, téléchargements, modèle choisi). */
        @JavascriptInterface
        public String models() {
            return Whisper.Models.state(MainActivity.this);
        }

        @JavascriptInterface
        public void setModel(String id) {
            if (Whisper.Models.get(id) == null) return;
            getSharedPreferences(Rec.PREFS, MODE_PRIVATE).edit().putString(Whisper.Models.CHOSEN, id).apply();
            Store.log("modèle Whisper choisi : " + id);
        }

        @JavascriptInterface
        public void download(String id) {
            try { Whisper.Models.download(MainActivity.this, id); } catch (Exception e) { Store.log("téléchargement : " + e); }
        }

        @JavascriptInterface
        public void cancelDownload(String id) {
            Whisper.Models.cancel(MainActivity.this, id);
        }

        @JavascriptInterface
        public void deleteModel(String id) {
            Whisper.Models.delete(MainActivity.this, id);
        }

        /** v1.5 — Démarre l'enregistrement audio (mode Whisper). prompt : vocabulaire à reconnaître. */
        @JavascriptInterface
        public String wstart(String lang, String prompt) {
            try {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(MainActivity.this::askPermissions);
                    return "{\"ok\":false,\"error\":\"not-allowed\"}";
                }
                Rec r = Rec.instance;
                if (r != null && r.busy()) return "{\"ok\":false,\"error\":\"busy\"}";
                if (r != null && r.recording()) return new JSONObject().put("ok", true).put("session", Store.open()).toString();
                if (Whisper.Models.ready(MainActivity.this, Whisper.Models.chosen(MainActivity.this)) == null)
                    return "{\"ok\":false,\"error\":\"w-model\"}";
                if (getFilesDir().getUsableSpace() < 300_000_000L) return "{\"ok\":false,\"error\":\"space\"}";
                String session = Store.open();
                Store.mode("whisper");
                getSharedPreferences(Rec.PREFS, MODE_PRIVATE).edit()
                        .putString(Rec.W_LANG, lang == null || lang.isEmpty() ? "fr-FR" : lang)
                        .putString(Rec.W_PROMPT, prompt == null ? "" : prompt)
                        .putBoolean(Rec.W_PENDING, false).putLong(Rec.W_DONE, 0).apply();
                startForegroundService(new Intent(MainActivity.this, Rec.class).setAction(Rec.START).putExtra("whisper", true));
                return new JSONObject().put("ok", true).put("session", session).toString();
            } catch (Exception e) {
                Store.fail("service");
                Store.log("démarrage Whisper : " + e);
                return "{\"ok\":false,\"error\":\"service\"}";
            }
        }

        @JavascriptInterface
        public void wpause(boolean on) {
            final Rec r = Rec.instance;
            if (r != null) runOnUiThread(() -> r.pauseAudio(on));
        }

        /** v1.5 — Arrête l'enregistrement audio : la transcription démarre aussitôt. */
        @JavascriptInterface
        public void wstop() {
            final Rec r = Rec.instance;
            if (r != null) runOnUiThread(r::stopAudio);
            else Store.close();
        }

        @JavascriptInterface
        public void wcancel() {
            if (Whisper.available()) Whisper.abort();
        }

        /** v1.5 — Reprend une transcription interrompue là où elle s'était arrêtée. */
        @JavascriptInterface
        public String wresume() {
            try {
                Rec r = Rec.instance;
                if (r != null && (r.busy() || r.recording())) return "{\"ok\":false,\"error\":\"busy\"}";
                if (Whisper.Models.ready(MainActivity.this, Whisper.Models.chosen(MainActivity.this)) == null)
                    return "{\"ok\":false,\"error\":\"w-model\"}";
                String session = Store.open();
                Store.mode("whisper");
                startForegroundService(new Intent(MainActivity.this, Rec.class).setAction(Rec.TRANSCRIBE));
                return new JSONObject().put("ok", true).put("session", session).toString();
            } catch (Exception e) {
                Store.fail("service");
                Store.log("reprise Whisper : " + e);
                return "{\"ok\":false,\"error\":\"service\"}";
            }
        }

        /** v1.5 — Copie le dernier enregistrement audio dans Musique/PLAUD Laurent (pour l'écouter ou le garder). */
        @JavascriptInterface
        public String saveAudio(String name) {
            File wav = Rec.audio(MainActivity.this);
            Rec r = Rec.instance;
            if (r != null && r.recording()) return "Arrêtez d'abord l'enregistrement";
            if (wav.length() <= 44) return "Aucun enregistrement audio";
            try (InputStream in = new FileInputStream(wav)) {
                OutputStream out;
                String where;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/PLAUD Laurent");
                    Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new Exception("stockage indisponible");
                    out = getContentResolver().openOutputStream(uri);
                    where = "Musique/PLAUD Laurent : " + name;
                } else {
                    File f = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), name);
                    out = new FileOutputStream(f);
                    where = f.getAbsolutePath();
                }
                if (out == null) throw new Exception("stockage indisponible");
                try (OutputStream o = out) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                return "Audio enregistré dans " + where;
            } catch (Exception e) {
                Store.log("audio : " + e);
                return "Enregistrement de l'audio impossible : " + e.getMessage();
            }
        }

        /** v1.4 — Texte reçu par « Partager » (vide s'il n'y en a pas) ; il n'est remis qu'une fois. */
        @JavascriptInterface
        public String takeShared() {
            synchronized (MainActivity.class) {
                String t = shared;
                shared = null;
                return t == null ? "" : t;
            }
        }
    }

    /**
     * Service d'enregistrement : reconnaissance vocale (Google, ou le moteur choisi dans les
     * Réglages), relancée automatiquement, sons système coupés, processeur maintenu éveillé écran éteint.
     */
    public static class Rec extends Service {
        static final String START = "fr.brasselet.plaud.START";
        static final String TRANSCRIBE = "fr.brasselet.plaud.TRANSCRIBE";   // v1.5 — reprise d'une transcription Whisper
        static final String PREFS = "plaud", ENGINE = "engine", WHISPER = "whisper";
        // v1.5 — réglages de la transcription Whisper en cours, gardés pour pouvoir la reprendre
        static final String W_LANG = "w_lang", W_PROMPT = "w_prompt", W_DONE = "w_done", W_PENDING = "w_pending";
        static volatile Rec instance;
        private static final String CHANNEL = "enregistrement";
        // Phrases que Whisper invente parfois sur du bruit (sous-titres de vidéos vues à l'entraînement).
        private static final Pattern JUNK = Pattern.compile("(?i)sous-titr|amara\\.org|merci d'avoir regard|abonnez-vous|radio-canada");

        static String chosen(Context c) {
            return c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(ENGINE, "");
        }

        /** v1.5 — Moteur de la transcription en direct : Whisper n'en est pas un, Google le remplace alors. */
        static String liveEngine(Context c) {
            String e = chosen(c);
            return WHISPER.equals(e) ? "" : e;
        }

        /** v1.5 — Dernier enregistrement audio du mode Whisper. */
        static File audio(Context c) {
            return new File(c.getFilesDir(), "entretien.wav");
        }

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ArrayList<Integer> muted = new ArrayList<>();
        private final Runnable listenTask = this::listen;
        private final Runnable finishTask = this::finish;
        private final Runnable engineCheck = this::checkEngine;
        private SpeechRecognizer recognizer;
        private PowerManager.WakeLock wakeLock;
        private String lang = "fr-FR";
        private boolean active, listening;
        private boolean heard, fallback;   // v1.4 — le moteur choisi a-t-il déjà rendu du texte / abandonné pour Google
        private int errors, lastStartId;
        // v1.5 — mode Whisper ; lus aussi par la page (Bridge), d'où volatile
        private volatile Whisper.Recorder recorder;   // enregistrement audio en cours
        private volatile Thread transcriber;          // transcription en cours
        private int shownPct = -1;

        boolean recording() {
            return recorder != null;
        }

        boolean busy() {
            return transcriber != null;
        }

        private final BroadcastReceiver screen = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Store.log(Intent.ACTION_SCREEN_OFF.equals(i.getAction()) ? "écran éteint" : "écran allumé");
            }
        };

        @Override
        public void onCreate() {
            super.onCreate();
            instance = this;
            Store.init(this);
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            try {
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
                else registerReceiver(screen, f);
            } catch (Exception e) {
                Store.log("écran : " + e);
            }
        }

        @Override
        public IBinder onBind(Intent i) {
            return null;
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            lastStartId = startId;
            String action = intent == null ? null : intent.getAction();
            if (TRANSCRIBE.equals(action)) {   // v1.5
                if (!foreground("Transcription Whisper en cours — touchez pour ouvrir")) return START_NOT_STICKY;
                handler.removeCallbacks(finishTask);
                lock();
                transcribe();
                return START_NOT_STICKY;
            }
            if (!START.equals(action)) {
                requestStop();
                return START_NOT_STICKY;
            }
            String l = intent.getStringExtra("lang");
            if (l != null && !l.isEmpty()) lang = l;
            boolean whisper = intent.getBooleanExtra("whisper", false);
            if (!foreground(whisper ? "Enregistrement audio en cours — touchez pour ouvrir" : "Transcription en cours — touchez pour ouvrir"))
                return START_NOT_STICKY;
            active = true;
            handler.removeCallbacks(finishTask);
            lock();
            if (whisper) {   // v1.5 — pas de reconnaissance en direct, donc pas de bip à couper
                startAudio();
                return START_NOT_STICKY;
            }
            mute();
            // v1.5 — un moteur choisi qui ne rend aucun texte en 30 s est remplacé par Google
            handler.removeCallbacks(engineCheck);
            if (!heard && !fallback && !liveEngine(this).isEmpty()) handler.postDelayed(engineCheck, 30000);
            if (!listening) {
                handler.removeCallbacks(listenTask);
                handler.post(listenTask);
            }
            return START_NOT_STICKY;
        }

        private boolean foreground(String text) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Enregistrement en cours", NotificationManager.IMPORTANCE_LOW));
                if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                else startForeground(1, notification(text));
                Store.log("premier plan : OK");
                return true;
            } catch (Exception e) {
                Store.log("premier plan refusé : " + e);
                Store.fail("service");
                finish();
                return false;
            }
        }

        private Notification notification(String text) {
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            return new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("PLAUD Laurent")
                    .setContentText(text)
                    .setContentIntent(open)
                    .setOngoing(true)
                    .build();
        }

        private void notifyText(String text) {
            try {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notification(text));
            } catch (Exception e) { /* rien */ }
        }

        /** v1.5 — Le moteur choisi n'a rien rendu depuis 30 s : on passe sur Google pour ne pas perdre l'entretien. */
        private void checkEngine() {
            if (!active || heard || fallback || liveEngine(this).isEmpty()) return;
            fallback = true;
            Store.log("le moteur choisi ne rend aucun texte — retour au moteur Google");
            Store.error("engine");
            schedule(100, true);
        }

        /* ---------- v1.5 — Mode Whisper : enregistrement audio puis transcription ---------- */

        private void startAudio() {
            if (recorder != null) return;   // déjà en cours (page rouverte)
            try {
                recorder = new Whisper.Recorder(audio(this));
                Store.phase("rec", 0);
                Store.provider("Whisper sur le téléphone (modèle " + Whisper.Models.chosen(this) + ")");
            } catch (Exception e) {
                Store.log("enregistrement audio : " + e);
                Store.fail("audio-capture");
                finish();
            }
        }

        void pauseAudio(boolean on) {
            if (recorder == null) return;
            recorder.paused = on;
            Store.phase(on ? "paused" : "rec", 0);
        }

        void stopAudio() {
            active = false;
            if (recorder == null) {
                if (transcriber == null) {
                    Store.close();
                    finish();
                }
                return;
            }
            Whisper.Recorder r = recorder;
            recorder = null;
            r.finish();
            Store.log("enregistrement audio arrêté : " + r.samples / Whisper.Recorder.RATE + " s" + (r.error == null ? "" : " — " + r.error));
            if (r.samples < Whisper.Recorder.RATE) {   // moins d'une seconde : rien à transcrire
                Store.close();
                finish();
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(W_PENDING, true).putLong(W_DONE, 0).apply();
            transcribe();
        }

        static String clean(String t) {
            t = t.replace('\n', ' ').trim();
            if (t.matches("[\\[(][^\\])]*[\\])]")) return "";   // « [Musique] », « (rires) » seuls
            if (t.length() < 120 && JUNK.matcher(t).find()) return "";
            return t;
        }

        private void transcribe() {
            if (transcriber != null) return;
            final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            final File wav = audio(this);
            final String model = Whisper.available() ? Whisper.Models.ready(this, Whisper.Models.chosen(this)) : null;
            if (model == null || wav.length() <= 44) {
                Store.log("transcription impossible : " + (!Whisper.available() ? Whisper.LOAD_ERROR : model == null ? "modèle absent" : "audio absent"));
                Store.fail(model == null ? "w-model" : "w-error");
                finish();
                return;
            }
            String l = p.getString(W_LANG, "fr-FR");
            final String language = l.length() >= 2 ? l.substring(0, 2) : "fr";
            final String prompt = p.getString(W_PROMPT, "");
            final long from = p.getLong(W_DONE, 0), total = Math.max(1, Whisper.durationMs(wav));
            final String vad = Whisper.vadModel(this);
            final int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
            shownPct = -1;
            Store.phase("transcribing", (int) (from * 100 / total));
            Store.log("transcription Whisper : " + new File(model).getName() + ", " + threads + " threads, de " + from / 1000 + " s à " + total / 1000 + " s");
            Store.log("processeur : " + Whisper.info());
            notifyText("Transcription Whisper en cours — touchez pour ouvrir");
            final long started = System.currentTimeMillis();
            transcriber = new Thread(() -> {
                Whisper.Listener listener = new Whisper.Listener() {
                    long prevEnd = from;
                    String prev = "";
                    int repeats;

                    @Override
                    public void onSegment(byte[] utf8, long t0, long t1) {
                        String t = clean(new String(utf8, StandardCharsets.UTF_8));
                        if (!t.isEmpty()) {
                            if (t.equals(prev)) repeats++;
                            else { prev = t; repeats = 0; }
                            // Plus de 4 s de silence avant la phrase : nouveau paragraphe.
                            if (repeats < 2) Store.add((t0 - prevEnd > 4000 ? Store.PARA : "") + t);
                        }
                        prevEnd = t1;
                        p.edit().putLong(W_DONE, t1).apply();
                    }

                    @Override
                    public void onProgress(int pct) {
                        final int all = (int) Math.min(100, (from + (total - from) * pct / 100) * 100 / total);
                        Store.phase("transcribing", all);
                        handler.post(() -> {
                            if (all / 5 == shownPct / 5) return;
                            shownPct = all;
                            notifyText("Transcription Whisper : " + all + " % — touchez pour ouvrir");
                        });
                    }
                };
                int code = Whisper.run(model, vad, wav.getAbsolutePath(), language, prompt, threads, from, listener);
                if (code == Whisper.ERR_RUN && vad != null) {   // repli : sans détection de la parole
                    Store.log("nouvel essai sans détection de la parole");
                    code = Whisper.run(model, null, wav.getAbsolutePath(), language, prompt, threads, p.getLong(W_DONE, from), listener);
                }
                final int rc = code;
                handler.post(() -> transcribed(rc, System.currentTimeMillis() - started));
            }, "plaud-whisper");
            transcriber.start();
        }

        private void transcribed(int code, long ms) {
            transcriber = null;
            if (code == Whisper.OK) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(W_PENDING, false).putLong(W_DONE, 0).apply();
                Store.log("transcription terminée en " + ms / 1000 + " s");
                Store.phase("done", 100);
                Store.close();
            } else if (code == Whisper.ABORTED) {
                Store.log("transcription interrompue à la demande");
                Store.phase("stopped", 0);
                Store.close();
            } else {
                Store.log("transcription : " + Whisper.message(code));
                Store.fail(code == Whisper.ERR_MODEL ? "w-model" : "w-error");
            }
            finish();
        }

        /** Coupe les sons multimédia, système et notifications (le bip de la reconnaissance). */
        private void mute() {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            for (int s : new int[]{AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION}) {
                if (muted.contains(s)) continue;
                try {
                    if (!am.isStreamMute(s)) {
                        am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0);
                        muted.add(s);
                    }
                } catch (Exception e) {
                    Store.log("son " + s + " non coupé : " + e.getClass().getSimpleName());
                }
            }
        }

        private void unmute() {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            for (int s : muted) {
                try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0); } catch (Exception e) { /* rien */ }
            }
            muted.clear();
        }

        private void lock() {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "plaud:transcription");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(6 * 3600 * 1000L);
        }

        private void unlock() {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        }

        private void listen() {
            if (!active) return;
            if (recognizer == null) {
                recognizer = create();
                if (recognizer == null) {
                    Store.fail("service");
                    finish();
                    return;
                }
                recognizer.setRecognitionListener(listener);
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            // Écoute continue, réglages identiques à Chrome 157 : session ouverte jusqu'à 5 minutes,
            // phrases livrées une à une (Android 13+) ou en résultats partiels « finals » avant.
            i.putExtra("android.speech.extra.DICTATION_MODE", true);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5 * 60 * 1000);
            if (Build.VERSION.SDK_INT >= 33)
                i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS);
            try {
                recognizer.startListening(i);
                listening = true;
            } catch (Exception e) {
                Store.log("écoute : " + e);
                retry(true);
            }
        }

        private SpeechRecognizer create() {
            ComponentName c = provider();
            Store.provider(c == null ? "service par défaut du téléphone" : c.flattenToShortString());
            try {
                if (c != null) return SpeechRecognizer.createSpeechRecognizer(this, c);
                if (SpeechRecognizer.isRecognitionAvailable(this)) return SpeechRecognizer.createSpeechRecognizer(this);
                Store.log("aucun service de reconnaissance vocale sur ce téléphone");
            } catch (Exception e) {
                Store.log("création : " + e);
            }
            return null;
        }

        /**
         * v1.4 — Le moteur choisi dans les Réglages s'il est installé ; sinon même choix que Chrome :
         * « Speech Services by Google » (Android 12+), sinon l'appli Google.
         */
        private ComponentName provider() {
            String[] order = Build.VERSION.SDK_INT >= 31
                    ? new String[]{"com.google.android.tts", "com.google.android.googlequicksearchbox"}
                    : new String[]{"com.google.android.googlequicksearchbox", "com.google.android.tts"};
            List<ResolveInfo> list = getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            StringBuilder seen = new StringBuilder();
            for (ResolveInfo r : list) if (r.serviceInfo != null) seen.append(r.serviceInfo.packageName).append(' ');
            Store.log("services disponibles : " + seen);
            String want = fallback ? "" : liveEngine(this);
            ComponentName c = want.isEmpty() ? null : ComponentName.unflattenFromString(want);
            if (c != null) {
                for (ResolveInfo r : list)
                    if (r.serviceInfo != null && c.getPackageName().equals(r.serviceInfo.packageName)
                            && c.getClassName().equals(r.serviceInfo.name))
                        return c;
                Store.log("moteur choisi absent du téléphone : " + want + " — Google utilisé");
            }
            for (String p : order)
                for (ResolveInfo r : list)
                    if (r.serviceInfo != null && p.equals(r.serviceInfo.packageName))
                        return new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
            return null;
        }

        private final RecognitionListener listener = new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { Store.log("écoute ouverte"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float db) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int type, Bundle b) { }

            @Override
            public void onPartialResults(Bundle b) {
                if (b != null && b.getBoolean("final_result", false)) add(text(b, false));
                else Store.interim(text(b, true));
            }

            @Override
            public void onSegmentResults(Bundle b) {
                add(text(b, false));
            }

            @Override
            public void onResults(Bundle b) {
                listening = false;
                add(text(b, false));
                next();
            }

            @Override
            public void onEndOfSegmentedSession() {
                listening = false;
                next();
            }

            @Override
            public void onError(int error) {
                listening = false;
                Store.interim("");
                Store.log("code " + error);
                if (!active) {
                    finishSoon();
                    return;
                }
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        Store.error("");
                        schedule(200, false);
                        break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    case SpeechRecognizer.ERROR_SERVER:
                        Store.error("network");
                        retry(false);
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            Store.fail("not-allowed");
                            finish();
                            return;
                        }
                        Store.error("audio-capture");
                        retry(true);
                        break;
                    case SpeechRecognizer.ERROR_AUDIO:
                        Store.error("audio-capture");
                        retry(true);
                        break;
                    default:   // service occupé, déconnecté, etc. : on recrée la reconnaissance
                        retry(true);
                }
            }
        };

        private static String text(Bundle b, boolean withUnstable) {
            if (b == null) return "";
            ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String t = (l == null || l.isEmpty() || l.get(0) == null) ? "" : l.get(0);
            if (withUnstable) {
                ArrayList<String> u = b.getStringArrayList("android.speech.extra.UNSTABLE_TEXT");
                if (u != null && !u.isEmpty() && u.get(0) != null) t += u.get(0);
            }
            return t.trim();
        }

        private void add(String t) {
            if (t.isEmpty()) return;
            Store.add(t);
            Store.interim("");
            Store.error("");
            errors = 0;
            heard = true;
        }

        private void next() {
            if (active) schedule(100, false);
            else finishSoon();
        }

        private void schedule(long delay, boolean recreate) {
            handler.removeCallbacks(listenTask);
            if (recreate) destroyRecognizer();
            handler.postDelayed(listenTask, delay);
        }

        private void retry(boolean recreate) {
            errors++;
            // v1.4 — Le moteur choisi échoue 5 fois sans rien rendre : on bascule sur Google
            // pour ne pas perdre l'entretien (la page affiche un avertissement).
            if (errors >= 5 && !heard && !fallback && !liveEngine(this).isEmpty()) {
                fallback = true;
                recreate = true;
                Store.log("le moteur choisi ne répond pas — retour au moteur Google");
                Store.error("engine");
            }
            schedule(Math.min(10000L, 500L * errors), recreate);
        }

        private void finishSoon() {
            handler.removeCallbacks(finishTask);
            handler.postDelayed(finishTask, 300);
        }

        /** Arrêt demandé : on laisse 2,5 s à la reconnaissance pour livrer la dernière phrase. */
        void requestStop() {
            active = false;
            handler.removeCallbacks(listenTask);
            if (recognizer != null && listening) {
                try { recognizer.stopListening(); } catch (Exception e) { /* rien */ }
                handler.removeCallbacks(finishTask);
                handler.postDelayed(finishTask, 2500);
            } else {
                finish();
            }
        }

        private void finish() {
            active = false;
            heard = fallback = false;   // v1.4 — le prochain enregistrement réessaie le moteur choisi
            handler.removeCallbacks(listenTask);
            handler.removeCallbacks(finishTask);
            handler.removeCallbacks(engineCheck);
            if (recorder != null) {   // v1.5
                recorder.finish();
                recorder = null;
            }
            destroyRecognizer();
            Store.interim("");
            unmute();
            unlock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(lastStartId);
        }

        private void destroyRecognizer() {
            listening = false;
            if (recognizer != null) {
                try { recognizer.cancel(); } catch (Exception e) { /* rien */ }
                try { recognizer.destroy(); } catch (Exception e) { /* rien */ }
                recognizer = null;
            }
        }

        @Override
        public void onDestroy() {
            handler.removeCallbacksAndMessages(null);
            if (recorder != null) recorder.finish();          // v1.5
            if (transcriber != null) Whisper.abort();          // v1.5 — la reprise reste possible
            destroyRecognizer();
            unmute();
            unlock();
            try { unregisterReceiver(screen); } catch (Exception e) { /* rien */ }
            Store.serviceGone();
            instance = null;
            super.onDestroy();
        }
    }

    /**
     * Mémoire de la transcription en cours, partagée entre la page et le service,
     * recopiée dans un fichier à chaque phrase (protection contre une fermeture brutale).
     */
    static final class Store {
        static final String PARA = "\u00b6";   // v1.5 — en tête d'une phrase : nouveau paragraphe
        private static File file;
        private static Context app;
        private static String mode = "live", phase = "";   // v1.5 — live | whisper ; rec | paused | transcribing | done | stopped
        private static int pct;
        private static String session = "";
        private static final ArrayList<String> segs = new ArrayList<>();
        private static String interim = "", err = "", fail = "", provider = "pas encore utilisé";
        private static boolean running;
        private static long lastAdd;
        private static final ArrayDeque<String> logs = new ArrayDeque<>();

        static synchronized void init(Context c) {
            if (file != null) return;
            app = c.getApplicationContext();
            file = new File(app.getFilesDir(), "transcription.txt");
            if (file.exists()) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    if (line != null) {
                        session = line.trim();
                        while ((line = r.readLine()) != null) if (!line.isEmpty()) segs.add(line);
                    }
                } catch (Exception e) {
                    log("lecture : " + e);
                }
            }
            log("ouverture — " + segs.size() + " phrase(s) en mémoire");
        }

        /** Démarre une nouvelle session (ou reprend celle en cours) et renvoie son identifiant. */
        static synchronized String open() {
            fail = "";
            err = "";
            interim = "";
            if (running) return session;
            running = true;
            mode = "live";
            phase = "";
            pct = 0;
            session = String.valueOf(System.currentTimeMillis());
            segs.clear();
            write(session + "\n", false);
            log("nouvelle session " + session);
            return session;
        }

        static synchronized void close() {
            if (running) log("arrêt demandé — " + segs.size() + " phrase(s)");
            running = false;
        }

        static synchronized void fail(String code) {
            fail = code;
            running = false;
            log("échec : " + code);
        }

        static synchronized void serviceGone() {
            if (running) {
                running = false;
                if (fail.isEmpty()) fail = "service";
                log("service arrêté sans demande");
            }
        }

        static synchronized void add(String t) {
            t = t.replace('\n', ' ').trim();
            if (t.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (!segs.isEmpty() && t.equals(segs.get(segs.size() - 1)) && now - lastAdd < 1500) return;
            lastAdd = now;
            segs.add(t);
            write(t + "\n", true);
        }

        static synchronized void mode(String m) {
            mode = m;
        }

        static synchronized void phase(String p, int percent) {
            if (!p.equals(phase)) log("étape : " + p);
            phase = p;
            pct = percent;
        }

        static synchronized void interim(String t) {
            interim = t == null ? "" : t;
        }

        static synchronized void error(String e) {
            if (!e.equals(err)) log("état : " + (e.isEmpty() ? "normal" : e));
            err = e;
        }

        static synchronized void provider(String p) {
            provider = p;
            log("moteur : " + p);
        }

        static synchronized String poll(String sess, int from) {
            try {
                JSONObject o = new JSONObject();
                o.put("session", session);
                JSONArray a = new JSONArray();
                if (session.equals(sess)) for (int i = Math.max(0, from); i < segs.size(); i++) a.put(segs.get(i));
                o.put("segs", a);
                o.put("interim", interim);
                o.put("err", err);
                o.put("fail", fail);
                o.put("running", running);
                // v1.5 — mode Whisper : étape, avancement, reprise possible, audio disponible
                o.put("mode", mode);
                o.put("phase", phase);
                o.put("pct", pct);
                boolean audio = app != null && Rec.audio(app).length() > 44;
                o.put("audio", audio && !running);
                o.put("resume", audio && !running && app.getSharedPreferences(Rec.PREFS, MODE_PRIVATE).getBoolean(Rec.W_PENDING, false));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        static synchronized void log(String m) {
            Log.i("PLAUD", m);
            logs.addLast(new SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(new Date()) + " " + m);
            while (logs.size() > 300) logs.removeFirst();
        }

        static synchronized String dump() {
            StringBuilder b = new StringBuilder("PLAUD Laurent — journal technique\n");
            b.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" — Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            b.append("Moteur : ").append(provider).append('\n');
            for (String l : logs) b.append(l).append('\n');
            return b.toString();
        }

        private static void write(String s, boolean append) {
            if (file == null) return;
            try (FileOutputStream o = new FileOutputStream(file, append)) {
                o.write(s.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log("écriture : " + e);
            }
        }
    }
}
