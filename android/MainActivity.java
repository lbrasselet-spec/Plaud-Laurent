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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * PLAUD Laurent — application Android.
 *
 * L'interface est la page index.html (la même que la version web), affichée dans une WebView.
 * La transcription tourne dans un service de premier plan (Rec) : elle continue téléphone
 * verrouillé, et les sons système (le bip) sont coupés pendant l'enregistrement.
 * La page dialogue avec ce code par l'objet JavaScript « PlaudNative ».
 * Les phrases reconnues sont conservées dans un fichier (Store) : rien n'est perdu si la page
 * est fermée ou l'écran éteint ; elle les récupère dès qu'elle est de nouveau affichée.
 */
public class MainActivity extends Activity {

    static final String ORIGIN = "https://appassets.androidplatform.net/";
    private static final int REQ_PERMS = 1, REQ_FILE = 2;

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
        web.loadUrl(ORIGIN + "index.html");
        if (!askPermissions()) askBatteryOnce();
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
    }

    /**
     * Service d'enregistrement : reconnaissance vocale de Google, relancée automatiquement,
     * sons système coupés, processeur maintenu éveillé écran éteint.
     */
    public static class Rec extends Service {
        static final String START = "fr.brasselet.plaud.START";
        static volatile Rec instance;
        private static final String CHANNEL = "enregistrement";

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ArrayList<Integer> muted = new ArrayList<>();
        private final Runnable listenTask = this::listen;
        private final Runnable finishTask = this::finish;
        private SpeechRecognizer recognizer;
        private PowerManager.WakeLock wakeLock;
        private String lang = "fr-FR";
        private boolean active, listening;
        private int errors, lastStartId;

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
            if (intent == null || !START.equals(intent.getAction())) {
                requestStop();
                return START_NOT_STICKY;
            }
            String l = intent.getStringExtra("lang");
            if (l != null && !l.isEmpty()) lang = l;
            try {
                goForeground();
            } catch (Exception e) {
                Store.log("premier plan refusé : " + e);
                Store.fail("service");
                finish();
                return START_NOT_STICKY;
            }
            active = true;
            handler.removeCallbacks(finishTask);
            mute();
            lock();
            if (!listening) {
                handler.removeCallbacks(listenTask);
                handler.post(listenTask);
            }
            return START_NOT_STICKY;
        }

        private void goForeground() {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Enregistrement en cours", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("PLAUD Laurent")
                    .setContentText("Transcription en cours — touchez pour ouvrir")
                    .setContentIntent(open)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(1, n);
            Store.log("premier plan : OK");
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

        /** Même choix que Chrome : « Speech Services by Google » (Android 12+), sinon l'appli Google. */
        private ComponentName provider() {
            String[] order = Build.VERSION.SDK_INT >= 31
                    ? new String[]{"com.google.android.tts", "com.google.android.googlequicksearchbox"}
                    : new String[]{"com.google.android.googlequicksearchbox", "com.google.android.tts"};
            List<ResolveInfo> list = getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            StringBuilder seen = new StringBuilder();
            for (ResolveInfo r : list) if (r.serviceInfo != null) seen.append(r.serviceInfo.packageName).append(' ');
            Store.log("services disponibles : " + seen);
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
            handler.removeCallbacks(listenTask);
            handler.removeCallbacks(finishTask);
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
        private static File file;
        private static String session = "";
        private static final ArrayList<String> segs = new ArrayList<>();
        private static String interim = "", err = "", fail = "", provider = "pas encore utilisé";
        private static boolean running;
        private static long lastAdd;
        private static final ArrayDeque<String> logs = new ArrayDeque<>();

        static synchronized void init(Context c) {
            if (file != null) return;
            file = new File(c.getApplicationContext().getFilesDir(), "transcription.txt");
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
