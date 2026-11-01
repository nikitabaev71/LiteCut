package com.lite.clipcut;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Scanner;
import org.json.JSONObject;

/** Projects live in the app's private storage: <id>.json (edit settings), <id>.jpg (thumbnail), <id>_src.mp4 (only if the video had to be copied). */
class Projects {
    static File dir(Context c) { File d = new File(c.getFilesDir(), "projects"); d.mkdirs(); return d; }
    static File thumb(Context c, String id) { return new File(dir(c), id + ".jpg"); }

    static JSONObject load(Context c, String id) {
        try { return new JSONObject(new Scanner(new File(dir(c), id + ".json")).useDelimiter("\\A").next()); } catch (Exception e) { return null; }
    }
    static void save(Context c, JSONObject o) {
        try {
            o.put("modified", System.currentTimeMillis());
            FileWriter w = new FileWriter(new File(dir(c), o.getString("id") + ".json")); w.write(o.toString()); w.close();
        } catch (Exception e) { }
    }
    static ArrayList<JSONObject> list(Context c) {
        ArrayList<JSONObject> l = new ArrayList<>(); String[] f = dir(c).list();
        if (f != null) for (String n : f) if (n.endsWith(".json")) { JSONObject o = load(c, n.substring(0, n.length() - 5)); if (o != null) l.add(o); }
        Collections.sort(l, new Comparator<JSONObject>() { public int compare(JSONObject a, JSONObject b) {
            long d = b.optLong("modified") - a.optLong("modified"); return d > 0 ? 1 : d < 0 ? -1 : 0; } });
        return l;
    }
    static void delete(Context c, String id) { for (String s : new String[]{".json", ".jpg", "_src.mp4"}) new File(dir(c), id + s).delete(); }
}
