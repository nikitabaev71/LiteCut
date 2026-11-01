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
import android.view.View;
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
    String inPath; int durMs, startMs, endMs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 23)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(16,16,16,16);
        vv = new VideoView(this); vv.setMediaController(new MediaController(this));
        vv.setOnPreparedListener(mp -> { durMs = vv.getDuration(); startMs = 0; endMs = durMs; refresh(); });
        c.addView(vv, new LinearLayout.LayoutParams(-1, (int) (220 * getResources().getDisplayMetrics().density)));
        info = new TextView(this); c.addView(info);
        c.addView(row(btn("Open video", v -> pick()),
            btn("Set start", v -> { startMs = vv.getCurrentPosition(); if (startMs >= endMs) endMs = durMs; refresh(); }),
            btn("Set end", v -> { endMs = vv.getCurrentPosition(); if (endMs <= startMs) startMs = 0; refresh(); })));
        spSpeed = add(c, "Speed", sp(SPEEDS)); spSpeed.setSelection(2);
        spXform = add(c, "Rotate / flip", sp(XFORM));
        spAspect = add(c, "Crop to ratio", sp(ASPECT));
        spFilter = add(c, "Filter", sp(FILTERS));
        sbBright = add(c, "Brightness", sb()); sbContrast = add(c, "Contrast", sb()); sbSat = add(c, "Saturation", sb());
        txt = new EditText(this); txt.setHint("Text overlay (optional)"); c.addView(txt);
        cbFade = new CheckBox(this); cbFade.setText("Fade in / out"); c.addView(cbFade);
        cbMute = new CheckBox(this); cbMute.setText("Mute audio"); c.addView(cbMute);
        spQuality = add(c, "Export", sp(QUALITY));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(100); c.addView(bar);
        c.addView(row(btn("Export", v -> export()), btn("Cancel", v -> FFmpegKit.cancel())));
        ScrollView s = new ScrollView(this); s.addView(c); setContentView(s);
        refresh();
    }

    // ---------- UI helpers ----------
    Button btn(String t, View.OnClickListener l) { Button x = new Button(this); x.setText(t); x.setOnClickListener(l); return x; }
    LinearLayout row(View... vs) {
        LinearLayout r = new LinearLayout(this);
        for (View v : vs) r.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
        return r;
    }
    Spinner sp(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); return s;
    }
    SeekBar sb() { SeekBar s = new SeekBar(this); s.setMax(100); s.setProgress(50); return s; }
    <T extends View> T add(LinearLayout c, String label, T v) { TextView t = new TextView(this); t.setText(label); c.addView(t); c.addView(v); return v; }
    void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
    void refresh() { info.setText(inPath == null ? "No video loaded" :
        String.format(Locale.US, "Trim %.1fs → %.1fs  (%.1fs kept)", startMs / 1000.0, endMs / 1000.0, (endMs - startMs) / 1000.0)); }

    // ---------- Input ----------
    void pick() { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("video/*"), 7); }
    @Override protected void onActivityResult(int rq, int rs, Intent d) {
        if (rq == 7 && rs == RESULT_OK && d != null) {
            inPath = pathFrom(d.getData());
            if (inPath == null) toast("Can't read that file"); else vv.setVideoPath(inPath);
        }
    }
    String pathFrom(Uri u) {
        if ("file".equals(u.getScheme())) return u.getPath();
        Cursor c = getContentResolver().query(u, new String[]{MediaStore.Video.Media.DATA}, null, null, null);
        try { if (c != null && c.moveToFirst()) { String p = c.getString(0); if (p != null && new File(p).canRead()) return p; } }
        finally { if (c != null) c.close(); }
        try { // fallback: copy into cache
            File f = new File(getCacheDir(), "in.mp4");
            InputStream i = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(f);
            byte[] buf = new byte[65536]; int n; while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            i.close(); o.close(); return f.getPath();
        } catch (Exception e) { return null; }
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

    void export() {
        if (inPath == null) { toast("Open a video first"); return; }
        final long len = endMs - startMs;
        if (len < 500) { toast("Trim range too short"); return; }
        final int q = spQuality.getSelectedItemPosition(), cap = Q_FPS[q], h = Q_H[q];
        final double sp = SPEED_V[spSpeed.getSelectedItemPosition()], outSec = len / 1000.0 / sp;
        final String text = txt.getText().toString().trim();
        final boolean fade = cbFade.isChecked(), mute = cbMute.isChecked();
        final int fi = spFilter.getSelectedItemPosition(), xi = spXform.getSelectedItemPosition(), ai = spAspect.getSelectedItemPosition();
        final double br = (sbBright.getProgress() - 50) / 100.0, ct = sbContrast.getProgress() / 50.0, sa = sbSat.getProgress() / 50.0;
        bar.setProgress(0);

        new Thread(() -> {
            try {
                ArrayList<String> vf = new ArrayList<>(), af = new ArrayList<>();
                if (sp != 1) { vf.add(String.format(Locale.US, "setpts=PTS/%.4f", sp)); af.add(atempo(sp)); }
                if (!XFORM_VF[xi].isEmpty()) vf.add(XFORM_VF[xi]);
                if (ai > 0) vf.add(String.format(Locale.US, "crop=w='min(iw,ih*%.5f)':h='min(ih,iw/%.5f)'", ASPECT_V[ai], ASPECT_V[ai]));
                // cap short side at 720 (or 480) without upscaling
                vf.add("scale=w='if(gt(iw,ih),-2,min(" + h + ",iw))':h='if(gt(iw,ih),min(" + h + ",ih),-2)'");
                if (br != 0 || ct != 1 || sa != 1) vf.add(String.format(Locale.US, "eq=brightness=%.2f:contrast=%.2f:saturation=%.2f", br, ct, sa));
                if (!FILTER_VF[fi].isEmpty()) vf.add(FILTER_VF[fi]);
                if (srcFps(inPath) * Math.max(1, sp) > cap + 0.5) vf.add("fps=" + cap);
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
                ArrayList<String> a = new ArrayList<>(Arrays.asList("-y", "-ss", sec(startMs), "-t", sec(len), "-i", inPath, "-vf", TextUtils.join(",", vf)));
                if (mute) a.add("-an");
                else { if (!af.isEmpty()) { a.add("-af"); a.add(TextUtils.join(",", af)); } a.addAll(Arrays.asList("-c:a", "aac", "-b:a", "128k")); }
                a.addAll(Arrays.asList("-c:v", "libx264", "-preset", "ultrafast", "-crf", "25", "-profile:v", "main",
                    "-pix_fmt", "yuv420p", "-movflags", "+faststart", out));

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
