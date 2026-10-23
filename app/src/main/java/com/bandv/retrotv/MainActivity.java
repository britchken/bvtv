package com.bandv.retrotv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * B and V's TV: a retro 90s homescreen for Android TV.
 * The look lives in assets/index.html; this class gives that page access to
 * installed apps, HDMI inputs, system settings and the joke of the day.
 */
public class MainActivity extends Activity {

    private WebView web;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> iconCache = new HashMap<>();
    private volatile boolean fetchingJoke = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("retrotv", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000080);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        // index.html is a fixed 1920x1080 design: lay it out 1920 wide and zoom it to fill the screen,
        // whatever resolution and density the TV reports. Ignore the TV's text-size setting so it can't overflow.
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        web.setInitialScale(0);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) js("window.fit && fit()");
        });
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.addJavascriptInterface(new Bridge(), "TV");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // the homescreen never navigates away
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                js("window.fit && fit()");
                refreshJoke();
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            js("window.onResumeTV && onResumeTV()");
            refreshJoke();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Home button pressed while we're already showing: jump back to the home page.
        js("window.goPage && goPage('home')");
    }

    @Override
    public void onBackPressed() {
        // A homescreen has nowhere to go "back" to; the page decides what Back means.
        js("window.onBack && onBack()");
    }

    private void js(final String code) {
        main.post(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    private void toast(final String msg) {
        main.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    // ---------------------------------------------------------------- jokes

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private void pushJoke(String joke) {
        js("window.setJoke && setJoke(" + JSONObject.quote(joke) + ")");
    }

    /** Shows today's joke: cached if we already fetched one today, otherwise a built-in one while we fetch. */
    private void refreshJoke() {
        final String d = today();
        if (d.equals(prefs.getString("jokeDate", ""))) {
            pushJoke(prefs.getString("jokeText", Jokes.forToday()));
            return;
        }
        pushJoke(Jokes.forToday());
        if (fetchingJoke) return;
        fetchingJoke = true;
        new Thread(() -> {
            try {
                String joke = fetchJoke();
                if (joke != null) {
                    prefs.edit().putString("jokeDate", d).putString("jokeText", joke).apply();
                    pushJoke(joke);
                }
            } catch (Exception ignored) {
                // offline or the joke site is down: the built-in joke stays up
            } finally {
                fetchingJoke = false;
            }
        }).start();
    }

    private static String fetchJoke() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://icanhazdadjoke.com/").openConnection();
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "RetroTV homescreen (personal Android TV launcher)");
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            String joke = new JSONObject(sb.toString()).optString("joke", "").replaceAll("\\s+", " ").trim();
            return (joke.isEmpty() || joke.length() > 400) ? null : joke;
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- icons

    private String iconFor(ResolveInfo ri, PackageManager pm) {
        String pkg = ri.activityInfo.packageName;
        String cached = iconCache.get(pkg);
        if (cached != null) return cached;
        Drawable dr = null;
        try { dr = ri.activityInfo.loadBanner(pm); } catch (Exception ignored) { }
        if (dr == null) {
            try { dr = pm.getApplicationBanner(pkg); } catch (Exception ignored) { }
        }
        if (dr == null) {
            try { dr = ri.loadIcon(pm); } catch (Exception ignored) { }
        }
        String out = "";
        if (dr != null) {
            try {
                int bw = 240, bh = 135;
                int iw = dr.getIntrinsicWidth() > 0 ? dr.getIntrinsicWidth() : bw;
                int ih = dr.getIntrinsicHeight() > 0 ? dr.getIntrinsicHeight() : bh;
                float scale = Math.min(bw / (float) iw, bh / (float) ih);
                int w = Math.max(1, Math.round(iw * scale));
                int h = Math.max(1, Math.round(ih * scale));
                Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                int left = (bw - w) / 2, top = (bh - h) / 2;
                dr.setBounds(left, top, left + w, top + h);
                dr.draw(canvas);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                bmp.recycle();
                out = "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            } catch (Exception ignored) {
                out = "";
            }
        }
        iconCache.put(pkg, out);
        return out;
    }

    // ---------------------------------------------------------------- bridge for index.html

    private class Bridge {

        /** Every launchable app on the TV, A-Z, as JSON [{pkg,label,icon}]. */
        @JavascriptInterface
        public String getApps() {
            PackageManager pm = getPackageManager();
            LinkedHashMap<String, ResolveInfo> found = new LinkedHashMap<>();
            List<ResolveInfo> tv = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
            List<ResolveInfo> phone = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            for (ResolveInfo ri : tv) found.put(ri.activityInfo.packageName, ri);
            for (ResolveInfo ri : phone) {
                if (!found.containsKey(ri.activityInfo.packageName)) found.put(ri.activityInfo.packageName, ri);
            }
            found.remove(getPackageName());

            List<JSONObject> list = new ArrayList<>();
            for (ResolveInfo ri : found.values()) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("pkg", ri.activityInfo.packageName);
                    o.put("label", String.valueOf(ri.loadLabel(pm)));
                    o.put("icon", iconFor(ri, pm));
                    list.add(o);
                } catch (Exception ignored) { }
            }
            Collections.sort(list, (a, b) -> a.optString("label").compareToIgnoreCase(b.optString("label")));
            JSONArray arr = new JSONArray();
            for (JSONObject o : list) arr.put(o);
            return arr.toString();
        }

        @JavascriptInterface
        public void launch(final String pkg) {
            main.post(() -> {
                PackageManager pm = getPackageManager();
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i == null) {
                    toast("That app won't open :(");
                    return;
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    toast("That app won't open :(");
                }
            });
        }

        /** HDMI inputs as JSON [{id,label,device}]; device is the name the plugged-in box reports, if any. */
        @JavascriptInterface
        public String getInputs() {
            JSONArray arr = new JSONArray();
            try {
                TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
                if (tim == null) return "[]";
                List<TvInputInfo> all = tim.getTvInputList();
                Map<String, String> childNames = new HashMap<>();
                for (TvInputInfo info : all) {
                    if (info.getParentId() != null) {
                        CharSequence l = info.loadLabel(MainActivity.this);
                        if (l != null) childNames.put(info.getParentId(), l.toString());
                    }
                }
                for (TvInputInfo info : all) {
                    if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
                    if (info.getParentId() != null) continue;
                    if (info.isHidden(MainActivity.this)) continue;
                    CharSequence custom = info.loadCustomLabel(MainActivity.this);
                    CharSequence label = (custom != null && custom.length() > 0) ? custom : info.loadLabel(MainActivity.this);
                    JSONObject o = new JSONObject();
                    o.put("id", info.getId());
                    o.put("label", label == null ? "HDMI" : label.toString());
                    String dev = childNames.get(info.getId());
                    o.put("device", dev == null ? "" : dev);
                    arr.put(o);
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        @JavascriptInterface
        public void openInput(final String id) {
            main.post(() -> {
                try {
                    Uri u = TvContract.buildChannelUriForPassthroughInput(id);
                    Intent i = new Intent(Intent.ACTION_VIEW, u);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    toast("Couldn't switch to that input. Try the Input button on the remote.");
                }
            });
        }

        @JavascriptInterface
        public void openSettings(final String which) {
            main.post(() -> {
                String action;
                switch (which == null ? "" : which) {
                    case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
                    case "picture": action = Settings.ACTION_DISPLAY_SETTINGS; break;
                    case "sound": action = Settings.ACTION_SOUND_SETTINGS; break;
                    case "apps": action = Settings.ACTION_APPLICATION_SETTINGS; break;
                    case "screensaver": action = Settings.ACTION_DREAM_SETTINGS; break;
                    case "remote": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
                    case "accounts": action = Settings.ACTION_SYNC_SETTINGS; break;
                    case "system": action = Settings.ACTION_DEVICE_INFO_SETTINGS; break;
                    default: action = Settings.ACTION_SETTINGS;
                }
                if (!tryStart(new Intent(action)) && !tryStart(new Intent(Settings.ACTION_SETTINGS))) {
                    toast("Couldn't open settings :(");
                }
            });
        }

        @JavascriptInterface
        public void openSearch() {
            main.post(() -> {
                if (!tryStart(new Intent(Intent.ACTION_ASSIST))
                        && !tryStart(new Intent("android.search.action.GLOBAL_SEARCH"))) {
                    toast("Search isn't available. Try the mic button on the remote!");
                }
            });
        }

        @JavascriptInterface
        public void toast(String msg) {
            MainActivity.this.toast(msg);
        }
    }

    private boolean tryStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- built-in jokes (used offline)

    static final class Jokes {
        static final String[] LIST = {
            "Why don't skeletons fight each other? They don't have the guts.",
            "I'm reading a book about anti-gravity. It's impossible to put down.",
            "Why did the scarecrow win an award? He was outstanding in his field.",
            "What do you call a fake noodle? An impasta.",
            "I used to hate facial hair, but then it grew on me.",
            "Why can't a bicycle stand up by itself? It's two tired.",
            "What do you call a bear with no teeth? A gummy bear.",
            "I only know 25 letters of the alphabet. I don't know y.",
            "What did the ocean say to the beach? Nothing, it just waved.",
            "Why did the coffee file a police report? It got mugged.",
            "How do you organize a space party? You planet.",
            "What do you call cheese that isn't yours? Nacho cheese.",
            "Why don't eggs tell jokes? They'd crack each other up.",
            "I would tell you a construction joke, but I'm still working on it.",
            "What do you call a fish wearing a bowtie? Sofishticated.",
            "Why did the math book look sad? It had too many problems.",
            "What do you call a dinosaur with an extensive vocabulary? A thesaurus.",
            "Did you hear about the claustrophobic astronaut? He just needed a little space.",
            "Why do cows wear bells? Because their horns don't work.",
            "What did one wall say to the other? I'll meet you at the corner.",
            "Why did the golfer bring two pairs of pants? In case he got a hole in one.",
            "What's orange and sounds like a parrot? A carrot.",
            "Why are elevator jokes so good? They work on so many levels.",
            "What do you call a sleeping bull? A bulldozer.",
            "I'm on a seafood diet. I see food and I eat it.",
            "Why did the tomato blush? It saw the salad dressing.",
            "What kind of shoes do ninjas wear? Sneakers.",
            "Why couldn't the leopard play hide and seek? He was always spotted.",
            "How does a penguin build its house? Igloos it together.",
            "What do you call a pile of cats? A meowntain.",
            "Why did the picture go to jail? It was framed.",
            "What do you call a belt made of watches? A waist of time.",
            "Why did the cookie go to the doctor? It felt crummy.",
            "What did the janitor say when he jumped out of the closet? Supplies!",
            "Why don't oysters share? Because they're shellfish.",
            "What do you call a cow with no legs? Ground beef.",
            "Why was the broom late? It over-swept.",
            "What do lawyers wear to court? Lawsuits.",
            "Why do bees have sticky hair? They use honeycombs.",
            "What did the grape do when it got stepped on? It let out a little wine.",
        };

        static String forToday() {
            int day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
            return LIST[day % LIST.length];
        }
    }
}
