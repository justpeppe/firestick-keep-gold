package it.keepgold.monitor;

import android.content.Context;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Media folder and settings, shared by the player screen, the web server and the boot receiver.
final class Store {
    static final File FOLDER = new File("/sdcard/Movies/Negozio");

    static final String[] VIDEO_EXT = {".mp4", ".m4v", ".mkv", ".mov", ".avi", ".ts", ".webm"};

    static final String[] IMAGE_EXT = {".jpg", ".jpeg", ".png"};

    private static Store instance;

    private final File settingsFile;

    List<String> order = new ArrayList<>();

    Set<String> disabled = new LinkedHashSet<>();

    boolean autostart = true;

    int returnMinutes = 60;

    int imageSeconds = 8;

    static synchronized Store get(Context context) {
        if (instance == null) instance = new Store(context.getApplicationContext());
        return instance;
    }

    private Store(Context context) {
        settingsFile = new File(context.getFilesDir(), "settings.json");
        FOLDER.mkdirs();
        try {
            JSONObject json = new JSONObject(readText(settingsFile));
            order = toList(json.optJSONArray("order"));
            disabled = new LinkedHashSet<>(toList(json.optJSONArray("disabled")));
            autostart = json.optBoolean("autostart", true);
            returnMinutes = json.optInt("return_minutes", 60);
            imageSeconds = json.optInt("image_seconds", 8);
        } catch (IOException | JSONException e) {
            // First run or unreadable file: keep the defaults.
        }

        // Leftovers of uploads cut by a power loss or a dropped connection.
        File[] files = FOLDER.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".part")) f.delete();
            }
        }
    }

    // java.nio.file is missing on Android 5.1, so read the small settings file by hand.
    static String readText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static List<String> toList(JSONArray array) {
        List<String> list = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) list.add(array.optString(i));
        }
        return list;
    }

    synchronized void save() {
        try {
            JSONObject json = new JSONObject();
            json.put("order", new JSONArray(order));
            json.put("disabled", new JSONArray(disabled));
            json.put("autostart", autostart);
            json.put("return_minutes", returnMinutes);
            json.put("image_seconds", imageSeconds);

            // A power cut mid-write would leave a truncated file; the rename swaps it in whole.
            File tmp = new File(settingsFile.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            tmp.renameTo(settingsFile);
        } catch (IOException | JSONException e) {
            // Settings stay in memory; the next save retries.
        }
    }

    static boolean isImage(String name) {
        return endsWith(name, IMAGE_EXT);
    }

    private static boolean endsWith(String name, String[] exts) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : exts) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    // Returns a bare file name inside FOLDER, or null for anything that could escape it or is not media.
    static String safeName(String name) {
        if (name == null) return null;
        name = new File(name).getName();
        if (name.isEmpty() || name.startsWith(".")) return null;
        return endsWith(name, VIDEO_EXT) || endsWith(name, IMAGE_EXT) ? name : null;
    }

    synchronized List<String> media() {
        String[] names = FOLDER.list();
        List<String> files = new ArrayList<>();
        if (names != null) {
            for (String n : names) {
                if (safeName(n) != null) files.add(n);
            }
        }
        List<String> result = new ArrayList<>();
        for (String n : order) {
            if (files.contains(n)) result.add(n);
        }
        List<String> rest = new ArrayList<>(files);
        rest.removeAll(result);
        String[] sorted = rest.toArray(new String[0]);
        Arrays.sort(sorted);
        result.addAll(Arrays.asList(sorted));
        return result;
    }

    synchronized List<String> playlist() {
        List<String> list = media();
        list.removeAll(disabled);
        return list;
    }

    synchronized void rename(String from, String to) {
        int i = order.indexOf(from);
        if (i >= 0) order.set(i, to);
        if (disabled.remove(from)) disabled.add(to);
        save();
    }

    static long freeBytes() {
        StatFs fs = new StatFs(FOLDER.getPath());
        return fs.getAvailableBytes();
    }

    static long totalBytes() {
        StatFs fs = new StatFs(FOLDER.getPath());
        return fs.getTotalBytes();
    }
}
