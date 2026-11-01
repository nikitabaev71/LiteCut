package com.lite.clipcut;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONObject;

public class HomeActivity extends Activity {
    static final int BG = 0xFF0E0E10, DIM = 0xFF8A8A92, TXT = 0xFFEDEDED;
    final ArrayList<JSONObject> items = new ArrayList<>(); BaseAdapter ad; TextView empty; float dp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 23)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); root.setPadding(px(12), px(12), px(12), 0);
        TextView title = tv("LiteCut", TXT, 22); title.setTypeface(null, Typeface.BOLD); root.addView(title);

        TextView create = tv("+  Create project", 0xFF00181A, 18); create.setTypeface(null, Typeface.BOLD); create.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF6FF0E8, 0xFF19B8C8}); g.setCornerRadius(px(14));
        create.setBackground(g);
        create.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("video/*"), 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(110)); lp.setMargins(0, px(14), 0, px(14)); root.addView(create, lp);

        root.addView(tv("Projects", TXT, 15));
        empty = tv("No projects yet. Tap Create project to start.", DIM, 13); empty.setPadding(0, px(16), 0, 0); root.addView(empty);
        GridView grid = new GridView(this); grid.setNumColumns(3); grid.setHorizontalSpacing(px(8)); grid.setVerticalSpacing(px(10)); grid.setPadding(0, px(10), 0, px(10));
        ad = new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int i) { return items.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View cv, ViewGroup parent) {
                JSONObject o = items.get(i);
                LinearLayout c = new LinearLayout(HomeActivity.this); c.setOrientation(LinearLayout.VERTICAL);
                ImageView iv = new ImageView(HomeActivity.this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackgroundColor(0xFF1A1A1E);
                Bitmap bm = BitmapFactory.decodeFile(Projects.thumb(HomeActivity.this, o.optString("id")).getPath()); if (bm != null) iv.setImageBitmap(bm);
                int w = (getResources().getDisplayMetrics().widthPixels - px(24) - px(16)) / 3;
                c.addView(iv, new LinearLayout.LayoutParams(w, w));
                TextView n = tv(o.optString("name"), TXT, 12); n.setSingleLine(); n.setEllipsize(TextUtils.TruncateAt.END); c.addView(n);
                c.addView(tv(size(o.optLong("size")) + " | " + dur(o.optInt("dur")), DIM, 11));
                return c;
            }
        };
        grid.setAdapter(ad);
        grid.setOnItemClickListener((p, v, i, id) -> open(items.get(i)));
        grid.setOnItemLongClickListener((p, v, i, id) -> { menu(items.get(i)); return true; });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); reload(); }
    void reload() { items.clear(); items.addAll(Projects.list(this)); ad.notifyDataSetChanged(); empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE); }

    int px(float v) { return (int) (v * dp + .5f); }
    void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
    TextView tv(String s, int c, float sz) { TextView t = new TextView(this); t.setText(s); t.setTextColor(c); t.setTextSize(sz); return t; }
    static String size(long b) { return b >= 1048576 ? String.format(Locale.US, "%.1fM", b / 1048576.0) : String.format(Locale.US, "%.1fK", b / 1024.0); }
    static String dur(int ms) { int s = Math.round(ms / 1000f); return String.format(Locale.US, "%02d:%02d", s / 60, s % 60); }

    void open(JSONObject o) {
        if (!new File(o.optString("path")).exists()) { toast("The source video is missing"); return; }
        startActivity(new Intent(this, MainActivity.class).putExtra("id", o.optString("id")));
    }

    void menu(final JSONObject o) {
        new AlertDialog.Builder(this).setTitle(o.optString("name")).setItems(new String[]{"Rename", "Delete"}, (d, w) -> {
            if (w == 1) new AlertDialog.Builder(this).setMessage("Delete this project? Your original video is not touched.")
                .setPositiveButton("Delete", (d2, x) -> { Projects.delete(this, o.optString("id")); reload(); }).setNegativeButton("Cancel", null).show();
            else {
                final EditText e = new EditText(this); e.setText(o.optString("name"));
                new AlertDialog.Builder(this).setView(e).setPositiveButton("OK", (d2, x) -> {
                    try { o.put("name", e.getText().toString().trim()); Projects.save(this, o); } catch (Exception ex) { }
                    reload();
                }).setNegativeButton("Cancel", null).show();
            }
        }).show();
    }

    // ---------- Create project ----------
    @Override protected void onActivityResult(int rq, int rs, Intent d) {
        if (rq != 7 || rs != RESULT_OK || d == null || d.getData() == null) return;
        final Uri u = d.getData(); toast("Creating project…");
        new Thread(() -> {
            String id = Long.toString(System.currentTimeMillis());
            String path = pathFrom(u, new File(Projects.dir(this), id + "_src.mp4"));
            if (path == null) { runOnUiThread(() -> toast("Can't read that file")); return; }
            int dur = 0; MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(path); dur = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                Bitmap f = r.getFrameAtTime(Math.min(1000000L, dur * 500L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (f != null) {
                    Bitmap s = Bitmap.createScaledBitmap(f, 256, Math.max(1, f.getHeight() * 256 / f.getWidth()), true);
                    FileOutputStream fos = new FileOutputStream(Projects.thumb(this, id)); s.compress(Bitmap.CompressFormat.JPEG, 80, fos); fos.close();
                }
            } catch (Exception e) { } finally { r.release(); }
            String day = new SimpleDateFormat("MMdd", Locale.US).format(new Date()); int n = 1;
            for (JSONObject q : Projects.list(this)) if (q.optString("name").startsWith(day + "(")) n++;
            final JSONObject po = new JSONObject();
            try {
                po.put("id", id).put("name", day + "(" + n + ")").put("path", path).put("dur", dur).put("size", new File(path).length())
                  .put("speed", 2).put("bright", 50).put("contrast", 50).put("sat", 50);
            } catch (Exception e) { }
            Projects.save(this, po);
            runOnUiThread(() -> open(po));
        }).start();
    }

    // Use the original file when readable; otherwise keep a private copy inside the project.
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
}
