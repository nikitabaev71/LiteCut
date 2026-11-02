package com.lite.clipcut;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.app.AlertDialog;
import android.media.MediaPlayer;
import org.json.JSONArray;
import org.json.JSONObject;
import android.widget.*;
import com.arthenica.ffmpegkit.*;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    static final String[] FILTERS = {"None","B&W","Sepia","Vivid","Warm","Cool","Vignette","Blur","Negative"};
    static final String[] FILTER_VF = {"","hue=s=0","colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131",
        "eq=saturation=1.5:contrast=1.1","eq=gamma_r=1.12:gamma_b=0.88","eq=gamma_r=0.9:gamma_b=1.12","vignette","boxblur=3:1","negate"};
    static final String[] SPEEDS = {"0.25x","0.5x","1x (normal)","1.5x","2x","3x","4x"};
    static final double[] SPEED_V = {.25,.5,1,1.5,2,3,4};
    static final String[] XFORM = {"None","Rotate 90° right","Rotate 180°","Rotate 90° left","Flip horizontal","Flip vertical"};
    static final String[] XFORM_VF = {"","transpose=1","hflip,vflip","transpose=2","hflip","vflip"};
    static final String[] ASPECT = {"Original","9:16","1:1","16:9","4:5"};
    static final double[] ASPECT_V = {0, 9/16.0, 1, 16/9.0, 0.8};
    static final String[] QUALITY = {"720p · 60 fps (max)","720p · 30 fps","480p · 30 fps"};
    static final int[] Q_H = {720,720,480}, Q_FPS = {60,30,30};

    VideoView vv; TextView info; ProgressBar bar; EditText txt; CheckBox cbFade, cbMute;
    Spinner spFilter, spSpeed, spXform, spAspect, spQuality; SeekBar sbBright, sbContrast, sbSat;
    String inPath, pid; JSONObject proj; int durMs, startMs, endMs;
    final ArrayList<JSONObject> clips = new ArrayList<>(); JSONObject curClip; boolean ready; LinearLayout clipRow; SeekBar sbVol; TextView tvVol; MediaPlayer mpl;

    // ---------- UI (dark, CapCut-style) ----------
    static final int BG = 0xFF0E0E10, PANEL = 0xFF1A1A1E, ACCENT = 0xFF19E3E3, TXT = 0xFFEDEDED, DIM = 0xFF8A8A92;
    static final String[] TOOLS = {"Speed", "Filter", "Adjust", "Text", "Ratio", "Audio", "Quality"};
    TimelineView tl; PlayView playBtn; TextView timeTxt;
    final View[] panels = new View[TOOLS.length]; final TextView[] toolBtns = new TextView[TOOLS.length];
    final Handler h = new Handler(); boolean playing; long lastSeek; float dp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pid = getIntent().getStringExtra("id"); proj = Projects.load(this, pid);
        if (proj == null) { finish(); return; }
        JSONArray ja = proj.optJSONArray("clips");
        if (ja == null) { // project made before clips existed: one clip
            JSONObject c0 = new JSONObject();
            try { c0.put("path", proj.optString("path")).put("dur", proj.optInt("dur")).put("start", proj.optInt("start")).put("end", proj.optInt("end")); } catch (Exception e) { }
            clips.add(c0);
        } else for (int i = 0; i < ja.length(); i++) clips.add(ja.optJSONObject(i));
        if (Build.VERSION.SDK_INT >= 23)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(px(10), px(8), px(10), px(8));
        top.addView(pill("Back", 0xFF2A2A30, TXT, v -> finish()));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView ex = pill("Export", ACCENT, 0xFF001417, v -> export());
        ex.setOnLongClickListener(v -> { FFmpegKit.cancel(); toast("Cancelling…"); return true; });   // long-press = cancel
        top.addView(ex);
        root.addView(top);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(100);
        root.addView(bar, new LinearLayout.LayoutParams(-1, px(4)));

        FrameLayout pv = new FrameLayout(this);
        vv = new VideoView(this);
        vv.setOnPreparedListener(mp -> {
            mpl = mp; applyVol(); durMs = vv.getDuration();
            try { curClip.put("dur", durMs); } catch (Exception e) { }
            startMs = curClip.optInt("start"); endMs = curClip.optInt("end");
            if (endMs <= startMs || endMs > durMs) { startMs = 0; endMs = durMs; }
            tl.setClip(durMs); tl.start = startMs; tl.end = endMs; ready = true; vv.seekTo(100); loadThumbs(); refreshClips(); refresh(); });
        vv.setOnCompletionListener(mp -> { stopPlay(); vv.seekTo(startMs); });
        pv.addView(vv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(pv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout ctl = new LinearLayout(this); ctl.setGravity(Gravity.CENTER_VERTICAL); ctl.setPadding(px(14), 0, px(14), 0);
        timeTxt = tv("00:00 / 00:00", TXT); ctl.addView(timeTxt, new LinearLayout.LayoutParams(0, -2, 1));
        playBtn = new PlayView(this); playBtn.setOnClickListener(v -> togglePlay()); ctl.addView(playBtn, new LinearLayout.LayoutParams(px(48), px(48)));
        info = tv("", DIM); info.setGravity(Gravity.RIGHT); ctl.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(ctl);

        tl = new TimelineView(this);
        tl.listener = new TimelineView.Listener() {
            public void onSeek(int ms, boolean fin) {
                if (inPath == null) return;
                if (playing) stopPlay();
                long n = System.currentTimeMillis();
                if (fin || n - lastSeek > 120) { vv.seekTo(ms); lastSeek = n; }
                refresh();
                if (fin) { commitClip(); refreshClips(); }
            }
            public void onTrim(int s, int e) { startMs = s; endMs = e; }
        };
        root.addView(tl, new LinearLayout.LayoutParams(-1, px(104)));
        HorizontalScrollView cs = new HorizontalScrollView(this); cs.setHorizontalScrollBarEnabled(false);
        clipRow = new LinearLayout(this); cs.addView(clipRow); root.addView(cs);

        FrameLayout pf = new FrameLayout(this); pf.setBackgroundColor(PANEL);
        spSpeed = sp(SPEEDS); spSpeed.setSelection(2); panels[0] = panel(spSpeed);
        spFilter = sp(FILTERS); panels[1] = panel(spFilter);
        sbBright = sb(); sbContrast = sb(); sbSat = sb();
        panels[2] = panel(labeled("Brightness", sbBright), labeled("Contrast", sbContrast), labeled("Saturation", sbSat));
        txt = new EditText(this); txt.setHint("Text overlay (optional)"); txt.setTextColor(TXT); txt.setHintTextColor(DIM); panels[3] = panel(txt);
        spAspect = sp(ASPECT); spXform = sp(XFORM); panels[4] = panel(labeled("Crop to ratio", spAspect), labeled("Rotate / flip", spXform));
        cbFade = new CheckBox(this); cbFade.setText("Fade in / out"); cbFade.setTextColor(TXT);
        cbMute = new CheckBox(this); cbMute.setText("Mute audio"); cbMute.setTextColor(TXT);
        tvVol = tv("Volume 100%", DIM); sbVol = new SeekBar(this); sbVol.setMax(200); sbVol.setProgress(100);
        sbVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sk, int p, boolean user) { tvVol.setText("Volume " + p + "%"); applyVol(); }
            public void onStartTrackingTouch(SeekBar sk) { }
            public void onStopTrackingTouch(SeekBar sk) { }
        });
        cbMute.setOnCheckedChangeListener((cb, on) -> applyVol());
        panels[5] = panel(tvVol, sbVol, cbMute, cbFade);
        spQuality = sp(QUALITY); panels[6] = panel(labeled("Export quality", spQuality));
        for (View p : panels) pf.addView(p);
        root.addView(pf, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tb = new LinearLayout(this);
        for (int i = 0; i < TOOLS.length; i++) {
            final int k = i; TextView t = tv(TOOLS[i], TXT); t.setPadding(px(18), px(14), px(18), px(14));
            t.setOnClickListener(v -> showPanel(k)); toolBtns[i] = t; tb.addView(t);
        }
        hs.addView(tb); root.addView(hs);
        spSpeed.setSelection(proj.optInt("speed", 2)); spFilter.setSelection(proj.optInt("filter")); spXform.setSelection(proj.optInt("xform"));
        spAspect.setSelection(proj.optInt("aspect")); spQuality.setSelection(proj.optInt("quality"));
        sbBright.setProgress(proj.optInt("bright", 50)); sbContrast.setProgress(proj.optInt("contrast", 50)); sbSat.setProgress(proj.optInt("sat", 50));
        txt.setText(proj.optString("text")); cbFade.setChecked(proj.optBoolean("fade")); cbMute.setChecked(proj.optBoolean("mute"));
        sbVol.setProgress(proj.optInt("volume", 100)); selectClip(clips.get(0));
        setContentView(root); refresh();
    }

    // ---------- Project autosave ----------
    void saveProject() {
        if (proj == null || !ready) return;
        commitClip();
        try {
            JSONArray ja = new JSONArray(); for (JSONObject c : clips) ja.put(c);
            proj.put("clips", ja).put("path", clips.get(0).optString("path")).put("dur", total())
                .put("speed", spSpeed.getSelectedItemPosition()).put("filter", spFilter.getSelectedItemPosition())
                .put("xform", spXform.getSelectedItemPosition()).put("aspect", spAspect.getSelectedItemPosition()).put("quality", spQuality.getSelectedItemPosition())
                .put("bright", sbBright.getProgress()).put("contrast", sbContrast.getProgress()).put("sat", sbSat.getProgress()).put("volume", sbVol.getProgress())
                .put("text", txt.getText().toString()).put("fade", cbFade.isChecked()).put("mute", cbMute.isChecked());
            Projects.save(this, proj);
        } catch (Exception e) { }
    }
    @Override protected void onPause() { super.onPause(); if (playing) stopPlay(); saveProject(); }

    int px(float v) { return (int) (v * dp + .5f); }
    void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
    TextView tv(String s, int c) { TextView t = new TextView(this); t.setText(s); t.setTextColor(c); t.setTextSize(13); return t; }
    TextView pill(String s, int bg, int fg, View.OnClickListener l) {
        TextView t = tv(s, fg); t.setTypeface(null, Typeface.BOLD); t.setGravity(Gravity.CENTER); t.setPadding(px(18), px(8), px(18), px(8));
        GradientDrawable g = new GradientDrawable(); g.setColor(bg); g.setCornerRadius(px(20)); t.setBackground(g); t.setOnClickListener(l); return t;
    }
    Spinner sp(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); return s;
    }
    SeekBar sb() { SeekBar s = new SeekBar(this); s.setMax(100); s.setProgress(50); return s; }
    LinearLayout panel(View... vs) {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setPadding(px(14), px(8), px(14), px(10));
        for (View v : vs) p.addView(v); p.setVisibility(View.GONE); return p;
    }
    LinearLayout labeled(String s, View v) { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.addView(tv(s, DIM)); l.addView(v); return l; }
    void showPanel(int k) {
        boolean open = panels[k].getVisibility() != View.VISIBLE;
        for (int i = 0; i < panels.length; i++) { panels[i].setVisibility(open && i == k ? View.VISIBLE : View.GONE); toolBtns[i].setTextColor(open && i == k ? ACCENT : TXT); }
    }
    static String fmt(int ms) { int s = ms / 1000; return String.format(Locale.US, "%02d:%02d", s / 60, s % 60); }
    void refresh() {
        timeTxt.setText(inPath == null ? "00:00 / 00:00" : fmt(tl.pos) + " / " + fmt(durMs));
        info.setText(inPath == null ? "Open a video" : String.format(Locale.US, "Clip %d/%d | total %s", clips.indexOf(curClip) + 1, clips.size(), fmt(total())));
    }

    // ---------- Playback (respects the trim range) ----------
    void togglePlay() {
        if (inPath == null) return;
        if (playing) { stopPlay(); return; }
        int p = vv.getCurrentPosition(); if (p < startMs || p >= endMs - 50) vv.seekTo(startMs);
        vv.start(); playing = true; playBtn.setPlaying(true); h.post(tick);
    }
    void stopPlay() { playing = false; vv.pause(); playBtn.setPlaying(false); }
    final Runnable tick = new Runnable() { public void run() {
        if (!playing) return;
        int p = vv.getCurrentPosition();
        if (p >= endMs) { stopPlay(); vv.seekTo(startMs); p = startMs; }
        tl.setPos(p); refresh(); h.postDelayed(this, 60);
    } };

    // ---------- Filmstrip thumbnails (background thread) ----------
    void loadThumbs() {
        final String path = inPath; final int d = durMs, n = 8, th = px(56); final Bitmap[] t = new Bitmap[n];
        tl.thumbs = t;
        new Thread(() -> {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(path);
                for (int i = 0; i < n && path.equals(inPath); i++) {
                    Bitmap f = r.getFrameAtTime(1000L * d * (2 * i + 1) / (2 * n), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (f == null) continue;
                    t[i] = Bitmap.createScaledBitmap(f, Math.max(1, f.getWidth() * th / f.getHeight()), th, true);
                    if (t[i] != f) f.recycle();
                    tl.postInvalidate();
                }
            } catch (Exception e) { } finally { try { r.release(); } catch (Exception ex) { } }
        }).start();
    }

    static class PlayView extends View {
        boolean playing; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        PlayView(Context c) { super(c); p.setColor(0xFFFFFFFF); }
        void setPlaying(boolean b) { playing = b; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h) * .3f;
            if (playing) { c.drawRect(w / 2 - s * .9f, h / 2 - s, w / 2 - s * .2f, h / 2 + s, p); c.drawRect(w / 2 + s * .2f, h / 2 - s, w / 2 + s * .9f, h / 2 + s, p); }
            else { Path t = new Path(); t.moveTo(w / 2 - s * .6f, h / 2 - s); t.lineTo(w / 2 + s, h / 2); t.lineTo(w / 2 - s * .6f, h / 2 + s); t.close(); c.drawPath(t, p); }
        }
    }

    // ---------- Export ----------
    static String atempo(double s) {
        StringBuilder b = new StringBuilder();
        while (s > 2) { b.append("atempo=2,"); s /= 2; }
        while (s < 0.5) { b.append("atempo=0.5,"); s /= 0.5; }
        return b + String.format(Locale.US, "atempo=%.4f", s);
    }
    static String sec(long ms) { return String.format(Locale.US, "%.3f", ms / 1000.0); }

    static double srcFps(String p) {
        try {
            for (StreamInformation st : FFprobeKit.getMediaInformation(p).getMediaInformation().getStreams())
                if ("video".equals(st.getType())) { String[] f = st.getAverageFrameRate().split("/"); return Double.parseDouble(f[0]) / Double.parseDouble(f[1]); }
        } catch (Exception e) { }
        return 30;
    }

    // ---------- Clips (joining several videos in one project) ----------
    static int len(JSONObject c) { int s = c.optInt("start"), e = c.optInt("end"); return e > s ? e - s : c.optInt("dur"); }
    int total() { int t = 0; for (JSONObject c : clips) t += (c == curClip && ready) ? endMs - startMs : len(c); return t; }
    void commitClip() { if (curClip != null && ready) try { curClip.put("start", startMs).put("end", endMs); } catch (Exception e) { } }
    void selectClip(JSONObject c) {
        if (playing) stopPlay();
        commitClip(); curClip = c; ready = false; inPath = c.optString("path"); durMs = c.optInt("dur");
        vv.setVideoPath(inPath); refreshClips();
    }
    void refreshClips() {
        clipRow.removeAllViews();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(px(10), px(4), 0, px(4));
        for (int i = 0; i < clips.size(); i++) {
            final JSONObject c = clips.get(i); final int k = i; boolean on = c == curClip;
            TextView t = pill((i + 1) + " | " + fmt(len(c)), on ? ACCENT : 0xFF2A2A30, on ? 0xFF001417 : TXT, v -> selectClip(c));
            t.setPadding(px(12), px(6), px(12), px(6)); t.setOnLongClickListener(v -> { clipMenu(k); return true; }); clipRow.addView(t, lp);
        }
        TextView add = pill("+ Add clip", 0xFF2A2A30, ACCENT, v -> pick()); add.setPadding(px(12), px(6), px(12), px(6)); clipRow.addView(add, lp);
    }
    void clipMenu(final int i) {
        new AlertDialog.Builder(this).setItems(new String[]{"Delete clip", "Move left", "Move right"}, (d, w) -> {
            commitClip(); JSONObject c = clips.get(i);
            if (w == 0) {
                if (clips.size() == 1) { toast("A project needs at least one clip"); return; }
                clips.remove(i);
                if (c == curClip) { curClip = null; selectClip(clips.get(Math.min(i, clips.size() - 1))); } else refreshClips();
            } else { int j = i + (w == 1 ? -1 : 1); if (j >= 0 && j < clips.size()) { Collections.swap(clips, i, j); refreshClips(); } }
            refresh();
        }).show();
    }
    void applyVol() {
        try { if (mpl != null) { float v = cbMute.isChecked() ? 0 : Math.min(1f, sbVol.getProgress() / 100f); mpl.setVolume(v, v); } } catch (Exception e) { }
    }
    void pick() { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("video/*"), 8); }
    @Override protected void onActivityResult(int rq, int rs, Intent d) {
        if (rq != 8 || rs != RESULT_OK || d == null || d.getData() == null) return;
        final Uri u = d.getData(); toast("Adding clip…");
        new Thread(() -> {
            String path = pathFrom(u, new File(Projects.dir(this), pid + "_src" + System.currentTimeMillis() + ".mp4"));
            if (path == null) { runOnUiThread(() -> toast("Can't read that file")); return; }
            int dur = 0; MediaMetadataRetriever r = new MediaMetadataRetriever();
            try { r.setDataSource(path); dur = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)); }
            catch (Exception e) { } finally { try { r.release(); } catch (Exception ex) { } }
            final JSONObject c = new JSONObject();
            try { c.put("path", path).put("dur", dur).put("start", 0).put("end", 0); } catch (Exception e) { }
            runOnUiThread(() -> { clips.add(c); selectClip(c); });
        }).start();
    }
    String pathFrom(Uri u, File copy) {
        if ("file".equals(u.getScheme())) return u.getPath();
        Cursor c = getContentResolver().query(u, new String[]{MediaStore.Video.Media.DATA}, null, null, null);
        try { if (c != null && c.moveToFirst()) { String p = c.getString(0); if (p != null && new File(p).canRead()) return p; } }
        finally { if (c != null) c.close(); }
        try {
            InputStream i = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(copy);
            byte[] buf = new byte[65536]; int n; while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            i.close(); o.close(); return copy.getPath();
        } catch (Exception e) { return null; }
    }

    static boolean hasAudio(String p) {
        try { for (StreamInformation st : FFprobeKit.getMediaInformation(p).getMediaInformation().getStreams()) if ("audio".equals(st.getType())) return true; }
        catch (Exception e) { }
        return false;
    }
    static int[] dispSize(String p) {   // displayed width/height (rotation applied)
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(p);
            int w = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)), h = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return ("90".equals(rot) || "270".equals(rot)) ? new int[]{h, w} : new int[]{w, h};
        } catch (Exception e) { return new int[]{16, 9}; } finally { try { r.release(); } catch (Exception ex) { } }
    }

    void export() {
        if (clips.isEmpty() || !ready) { toast("Wait until the video is loaded"); return; }
        commitClip();
        final ArrayList<String> paths = new ArrayList<>(); final ArrayList<int[]> rng = new ArrayList<>(); int totalMs = 0;
        for (JSONObject c : clips) {
            int s = c.optInt("start"), e = c.optInt("end"); if (e <= s) { s = 0; e = c.optInt("dur"); }
            if (e - s < 300) { toast("One of the clips is too short"); return; }
            paths.add(c.optString("path")); rng.add(new int[]{s, e}); totalMs += e - s;
        }
        final int n = paths.size(), q = spQuality.getSelectedItemPosition(), cap = Q_FPS[q], h = Q_H[q];
        final double sp = SPEED_V[spSpeed.getSelectedItemPosition()], outSec = totalMs / 1000.0 / sp, vol = sbVol.getProgress() / 100.0;
        final String text = txt.getText().toString().trim();
        final boolean fade = cbFade.isChecked(), mute = cbMute.isChecked();
        final int fi = spFilter.getSelectedItemPosition(), xi = spXform.getSelectedItemPosition(), ai = spAspect.getSelectedItemPosition();
        final double br = (sbBright.getProgress() - 50) / 100.0, ct = sbContrast.getProgress() / 50.0, sa = sbSat.getProgress() / 50.0;
        bar.setProgress(0);

        new Thread(() -> {
            try {
                ArrayList<String> vf = new ArrayList<>(), af = new ArrayList<>();
                double f0 = srcFps(paths.get(0)); int F = (int) Math.round(Math.min(cap, f0));
                if (sp != 1) { vf.add(String.format(Locale.US, "setpts=PTS/%.4f", sp)); af.add(atempo(sp)); }
                if (!XFORM_VF[xi].isEmpty()) vf.add(XFORM_VF[xi]);
                if (ai > 0) vf.add(String.format(Locale.US, "crop=w='min(iw,ih*%.5f)':h='min(ih,iw/%.5f)'", ASPECT_V[ai], ASPECT_V[ai]));
                if (n == 1) vf.add("scale=w='if(gt(iw,ih),-2,min(" + h + ",iw))':h='if(gt(iw,ih),min(" + h + ",ih),-2)'");
                if (br != 0 || ct != 1 || sa != 1) vf.add(String.format(Locale.US, "eq=brightness=%.2f:contrast=%.2f:saturation=%.2f", br, ct, sa));
                if (!FILTER_VF[fi].isEmpty()) vf.add(FILTER_VF[fi]);
                if ((n == 1 ? f0 : F) * Math.max(1, sp) > cap + 0.5) vf.add("fps=" + cap);
                if (vol != 1) af.add(String.format(Locale.US, "volume=%.2f", vol));
                if (!text.isEmpty()) {
                    File f = new File(getCacheDir(), "t.txt"); FileWriter w = new FileWriter(f); w.write(text); w.close();
                    vf.add("drawtext=fontfile=/system/fonts/Roboto-Regular.ttf:textfile=" + f.getPath() + ":expansion=none:fontcolor=white"
                        + ":fontsize=h/14:borderw=2:bordercolor=black:x=(w-text_w)/2:y=h-text_h-h/12");
                }
                if (fade) {
                    vf.add("fade=t=in:st=0:d=0.5"); vf.add(String.format(Locale.US, "fade=t=out:st=%.2f:d=0.5", Math.max(0, outSec - 0.5)));
                    af.add("afade=t=in:st=0:d=0.5"); af.add(String.format(Locale.US, "afade=t=out:st=%.2f:d=0.5", Math.max(0, outSec - 0.5)));
                }
                vf.add("scale=trunc(iw/2)*2:trunc(ih/2)*2"); vf.add("format=yuv420p");

                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES); dir.mkdirs();
                final String out = new File(dir, "LiteCut_" + System.currentTimeMillis() + ".mp4").getPath();
                ArrayList<String> a = new ArrayList<>(); a.add("-y");
                for (int i = 0; i < n; i++) a.addAll(Arrays.asList("-ss", sec(rng.get(i)[0]), "-t", sec(rng.get(i)[1] - rng.get(i)[0]), "-i", paths.get(i)));
                if (n == 1) {
                    a.addAll(Arrays.asList("-vf", TextUtils.join(",", vf)));
                    if (mute) a.add("-an");
                    else { if (!af.isEmpty()) { a.add("-af"); a.add(TextUtils.join(",", af)); } a.addAll(Arrays.asList("-c:a", "aac", "-b:a", "128k")); }
                } else {
                    // Every clip is fitted into one canvas (the first clip decides landscape/portrait), then all are concatenated.
                    int[] cv = dispSize(paths.get(0)); boolean land = cv[0] >= cv[1]; int lg = h == 720 ? 1280 : 854, W = land ? lg : h, H = land ? h : lg;
                    StringBuilder fc = new StringBuilder(), cat = new StringBuilder();
                    for (int i = 0; i < n; i++) {
                        fc.append(String.format(Locale.US, "[%d:v]scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=%d,format=yuv420p[v%d];", i, W, H, W, H, F, i));
                        cat.append("[v").append(i).append("]");
                        if (!mute) {
                            if (hasAudio(paths.get(i))) fc.append(String.format(Locale.US, "[%d:a]aresample=44100,aformat=channel_layouts=stereo[a%d];", i, i));
                            else fc.append(String.format(Locale.US, "anullsrc=r=44100:cl=stereo,atrim=duration=%.3f[a%d];", (rng.get(i)[1] - rng.get(i)[0]) / 1000.0, i));
                            cat.append("[a").append(i).append("]");
                        }
                    }
                    fc.append(cat).append(String.format(Locale.US, "concat=n=%d:v=1:a=%d[v]%s;", n, mute ? 0 : 1, mute ? "" : "[a]"));
                    fc.append("[v]").append(TextUtils.join(",", vf)).append("[vout]");
                    if (!mute) fc.append(";[a]").append(af.isEmpty() ? "anull" : TextUtils.join(",", af)).append("[aout]");
                    a.addAll(Arrays.asList("-filter_complex", fc.toString(), "-map", "[vout]"));
                    if (mute) a.add("-an"); else a.addAll(Arrays.asList("-map", "[aout]", "-c:a", "aac", "-b:a", "128k"));
                }
                a.addAll(Arrays.asList("-c:v", "libx264", "-preset", "ultrafast", "-crf", "25", "-profile:v", "main",
                    "-pix_fmt", "yuv420p", "-g", "30", "-movflags", "+faststart", out));

                FFmpegKit.executeWithArgumentsAsync(a.toArray(new String[0]), s -> runOnUiThread(() -> {
                    if (ReturnCode.isSuccess(s.getReturnCode())) {
                        bar.setProgress(100); toast("Saved: " + out);
                        MediaScannerConnection.scanFile(this, new String[]{out}, null, null);
                    } else toast(ReturnCode.isCancel(s.getReturnCode()) ? "Cancelled" : "Export failed");
                }), null, st -> runOnUiThread(() -> bar.setProgress((int) Math.min(99, st.getTime() * 100 / (outSec * 1000)))));
            } catch (Exception e) { runOnUiThread(() -> toast("Error: " + e.getMessage())); }
        }).start();
    }
}
