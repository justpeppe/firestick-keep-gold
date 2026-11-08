package it.keepgold.monitor;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.Locale;

import fi.iki.elonen.NanoHTTPD;

// The phone management page and its JSON API.
final class WebServer extends NanoHTTPD {
    static final int PORT = 8000;

    // Keep 200 MB free so Fire OS keeps working.
    private static final long RESERVE = 200L * 1024 * 1024;

    private final Context context;

    private final Store store;

    WebServer(Context context) {
        super(PORT);
        this.context = context;
        this.store = Store.get(context);
    }

    void begin() throws IOException {
        // A phone that leaves the wifi mid-upload must not hold a thread and a .part file forever.
        start(60000, true);
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String path = session.getUri();
            if (session.getMethod() == Method.GET) {
                if (path.equals("/api/state")) return json(state());
                if (path.startsWith("/media/")) return media(session, path.substring(7));
                if (path.equals("/tokens.css")) return asset("web/tokens.css", "text/css");
                if (path.equals("/logo.jpg") || path.equals("/icon.jpg")) return asset("web" + path, "image/jpeg");
                return asset("web/index.html", "text/html; charset=utf-8");
            }
            if (session.getMethod() == Method.POST) {

                // Browsers send Origin on POST: this stops other websites from driving the TV through a phone on the wifi.
                String origin = session.getHeaders().get("origin");
                if (origin != null && !origin.equals("http://" + session.getHeaders().get("host"))) {
                    return error(Response.Status.FORBIDDEN, "non permesso");
                }
                if (path.equals("/api/upload")) return upload(session);
                return post(path, new JSONObject(readBody(session)));
            }
            return error(Response.Status.METHOD_NOT_ALLOWED, "metodo non supportato");
        } catch (Exception e) {
            return error(Response.Status.INTERNAL_ERROR, "errore sulla TV");
        }
    }

    private JSONObject state() throws JSONException {
        JSONArray media = new JSONArray();
        for (String name : store.media()) {
            JSONObject m = new JSONObject();
            m.put("name", name);
            m.put("size", new File(Store.FOLDER, name).length());
            m.put("image", Store.isImage(name));
            m.put("enabled", !store.disabled.contains(name));
            media.put(m);
        }
        JSONObject s = new JSONObject();
        s.put("media", media);
        s.put("free", Store.freeBytes());
        s.put("total", Store.totalBytes());
        s.put("autostart", store.autostart);
        s.put("return_minutes", store.returnMinutes);
        s.put("image_seconds", store.imageSeconds);
        return s;
    }

    private Response post(String path, JSONObject data) throws JSONException {
        String name = Store.safeName(data.optString("name", null));
        boolean restart = false;
        synchronized (store) {
            switch (path) {
                case "/api/toggle":
                    if (name != null) {
                        if (data.optBoolean("enabled")) store.disabled.remove(name);
                        else store.disabled.add(name);
                    }
                    break;
                case "/api/delete":
                    if (name == null || !new File(Store.FOLDER, name).delete()) {
                        return error(Response.Status.INTERNAL_ERROR, "Non sono riuscito a eliminarlo");
                    }

                    // A later upload with the same name must not come back switched off or out of place.
                    store.order.remove(name);
                    store.disabled.remove(name);
                    break;
                case "/api/rename": {
                    String ext = name == null ? "" : name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
                    String to = Store.safeName(data.optString("new", "").trim() + ext);
                    if (name == null || to == null || !new File(Store.FOLDER, name).exists()) {
                        return error(Response.Status.BAD_REQUEST, "Nome non valido");
                    }
                    if (!to.equals(name) && new File(Store.FOLDER, to).exists()) {
                        return error(Response.Status.CONFLICT, "Esiste già un contenuto con questo nome");
                    }
                    if (!new File(Store.FOLDER, name).renameTo(new File(Store.FOLDER, to))) {
                        return error(Response.Status.INTERNAL_ERROR, "Non sono riuscito a rinominarlo");
                    }
                    store.rename(name, to);
                    break;
                }
                case "/api/order": {
                    JSONArray names = data.optJSONArray("names");
                    store.order.clear();
                    for (int i = 0; names != null && i < names.length(); i++) {
                        String n = Store.safeName(names.optString(i));
                        if (n != null) store.order.add(n);
                    }
                    break;
                }
                case "/api/settings":
                    store.autostart = data.optBoolean("autostart", true);
                    store.returnMinutes = Math.min(240, Math.max(1, data.optInt("return_minutes", 60)));
                    store.imageSeconds = Math.min(60, Math.max(3, data.optInt("image_seconds", 8)));
                    break;
                case "/api/restart":
                    restart = true;

                    // Only when the show is hidden: reopening it while on screen pauses it and it would restart from black.
                    if (KioskService.awaySince != 0) KioskService.bringToFront(context);
                    break;
                default:
                    return error(Response.Status.NOT_FOUND, "not found");
            }
            store.save();
        }

        // Edits apply from the next item; only "Riavvia" restarts the show from the top.
        KioskService.contentChanged(restart);
        return json(new JSONObject().put("ok", true));
    }

    private Response upload(IHTTPSession session) throws JSONException {
        String name = Store.safeName(session.getParms().get("name"));
        long length = contentLength(session);

        // Early answers first read the body away: answering mid-upload resets the connection and the phone would only see "connessione persa".
        if (name == null) return refuse(session, length, error(Response.Status.BAD_REQUEST, "Formato non supportato"));
        if (length <= 0) return error(Response.Status.BAD_REQUEST, "File vuoto");
        if (new File(Store.FOLDER, name).exists()) {
            return refuse(session, length, error(Response.Status.CONFLICT, "Esiste già un contenuto con questo nome"));
        }
        if (length + RESERVE > Store.freeBytes()) {
            return refuse(session, length, error(new Status(507, "Insufficient Storage"), "Spazio insufficiente"));
        }
        File part = new File(Store.FOLDER, "." + name + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            InputStream in = session.getInputStream();
            byte[] buf = new byte[256 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new IOException("upload interrupted");
                out.write(buf, 0, n);
                remaining -= n;
            }
            out.getFD().sync();
        } catch (IOException e) {
            part.delete();
            return error(Response.Status.INTERNAL_ERROR, "Caricamento interrotto");
        }
        if (!part.renameTo(new File(Store.FOLDER, name))) {
            part.delete();
            return error(Response.Status.INTERNAL_ERROR, "Caricamento non salvato");
        }
        KioskService.contentChanged(false);
        return json(new JSONObject().put("ok", true));
    }

    private Response media(IHTTPSession session, String raw) throws IOException {
        // NanoHTTPD already percent-decodes the URI.
        String name = Store.safeName(raw);
        File file = name == null ? null : new File(Store.FOLDER, name);
        if (file == null || !file.exists()) return error(Response.Status.NOT_FOUND, "not found");
        long size = file.length();
        long start = 0;
        long end = size - 1;

        // Safari only plays a video if the server answers byte-range requests.
        String range = session.getHeaders().get("range");
        Response.IStatus status = Response.Status.OK;
        if (range != null && range.startsWith("bytes=")) {
            try {
                String[] parts = range.substring(6).split(",")[0].split("-", -1);
                if (!parts[0].isEmpty()) {
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) end = Math.min(Long.parseLong(parts[1]), end);
                } else {
                    start = Math.max(0, size - Long.parseLong(parts[1]));
                }
                status = Response.Status.PARTIAL_CONTENT;
            } catch (RuntimeException e) {
                // Malformed range: send the whole file.
                start = 0;
                end = size - 1;
            }
            if (start >= size || end < start) {
                Response r = error(Response.Status.RANGE_NOT_SATISFIABLE, "range");
                r.addHeader("Content-Range", "bytes */" + size);
                return r;
            }
        }
        FileInputStream in = new FileInputStream(file);
        in.skip(start);
        Response r = newFixedLengthResponse(status, mime(name), in, end - start + 1);
        r.addHeader("Accept-Ranges", "bytes");
        if (status == Response.Status.PARTIAL_CONTENT) r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        if (session.getParms().containsKey("download")) {
            r.addHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
        }
        return r;
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (Store.isImage(n)) return "image/jpeg";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".webm")) return "video/webm";
        return "video/mp4";
    }

    private Response asset(String path, String mime) throws IOException {
        return newChunkedResponse(Response.Status.OK, mime, context.getAssets().open(path));
    }

    private static Response refuse(IHTTPSession session, long length, Response r) {
        try {
            InputStream in = session.getInputStream();
            byte[] buf = new byte[64 * 1024];
            for (long left = length; left > 0; ) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                left -= n;
            }
        } catch (IOException e) {
            // The phone went away; nobody is left to read the answer.
        }
        return r;
    }

    private static Response json(JSONObject data) {
        return newFixedLengthResponse(Response.Status.OK, "application/json", data.toString());
    }

    private static Response error(Response.IStatus status, String message) {
        try {
            return newFixedLengthResponse(status, "application/json", new JSONObject().put("error", message).toString());
        } catch (JSONException e) {
            return newFixedLengthResponse(status, "text/plain", message);
        }
    }

    private static long contentLength(IHTTPSession session) {
        String value = session.getHeaders().get("content-length");
        return value == null ? 0 : Long.parseLong(value);
    }

    private static String readBody(IHTTPSession session) throws IOException {
        long length = contentLength(session);
        byte[] body = new byte[(int) Math.min(length, 1024 * 1024)];
        int read = 0;
        InputStream in = session.getInputStream();
        while (read < body.length) {
            int n = in.read(body, read, body.length - read);
            if (n < 0) break;
            read += n;
        }
        return read == 0 ? "{}" : new String(body, 0, read, "UTF-8");
    }

    // NanoHTTPD has no constant for 507.
    private static final class Status implements Response.IStatus {
        private final int code;

        private final String text;

        Status(int code, String text) {
            this.code = code;
            this.text = text;
        }

        @Override
        public String getDescription() {
            return code + " " + text;
        }

        @Override
        public int getRequestStatus() {
            return code;
        }
    }
}
