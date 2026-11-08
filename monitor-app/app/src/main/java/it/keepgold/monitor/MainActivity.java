package it.keepgold.monitor;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The show as one continuous film: two layers, the next item starts under the current one and they cross-fade, picture and sound.
public final class MainActivity extends Activity {
    private static final int BLACK = 0xFF0B0B0B;

    private static final int GOLD = 0xFFEAB92A;

    private static final int MUTED = 0xFFB0ACA0;

    private static final long FADE_MS = 3000;

    // The next item is loaded this long before its fade starts, so it is ready on time.
    private static final long PRELOAD_MS = 3000;

    private static final float ZOOM = 1.25f;

    private static final long BANNER_MS = 20000;

    private static final long TICK_MS = 50;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ExecutorService decoder = Executors.newSingleThreadExecutor();

    private Store store;

    private FrameLayout stage;

    private Slot a;

    private Slot b;

    // On screen now, and the one fading in or waiting to.
    private Slot cur;

    private Slot next;

    private long fadeStart;

    private long fadeMs;

    private long retryAt;

    private int index = -1;

    private int failures;

    private LinearLayout card;

    private TextView cardTitle;

    private TextView cardText;

    private TextView banner;

    private boolean online;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            step();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable hideBanner = () -> banner.animate().alpha(0).setDuration(800)
            .withEndAction(() -> banner.setVisibility(View.GONE)).start();

    // Shows a gold banner on the TV the moment the stick joins a network, with the address to open on the phone.
    private final BroadcastReceiver netReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            NetworkInfo n = ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
            boolean now = n != null && n.isConnected();
            if (now && !online && !isInitialStickyBroadcast()) showBanner();
            online = now;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        store = Store.get(this);
        KioskService.start(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BLACK);
        stage = new FrameLayout(this);
        a = new Slot();
        b = new Slot();
        root.addView(stage, match());
        root.addView(buildCard(), match());
        root.addView(buildBanner(), bottomCenter());
        setContentView(root);
    }

    // One layer of the show: a video surface and a photo, zoomed together, faded as a whole.
    private final class Slot {
        final FrameLayout fade = new FrameLayout(MainActivity.this);

        final FrameLayout zoom = new FrameLayout(MainActivity.this);

        final TextureView video = new TextureView(MainActivity.this);

        final ImageView photo = new ImageView(MainActivity.this);

        final ExoPlayer player = new ExoPlayer.Builder(MainActivity.this).build();

        boolean isVideo;

        boolean ready;

        boolean failed;

        long shownAt;

        long endAt = Long.MAX_VALUE;

        long loadedAt;

        long lastPos;

        long lastMove;

        int token;

        Slot() {
            photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            photo.setBackgroundColor(BLACK);
            zoom.addView(video, match());
            zoom.addView(photo, match());
            fade.addView(zoom, match());
            fade.setBackgroundColor(BLACK);
            fade.setAlpha(0);
            stage.addView(fade, match());
            player.setVideoTextureView(video);

            // A size event that arrives before the first layout would leave the video stretched.
            video.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fitVideo(video, player.getVideoSize()));
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) ready = true;
                }

                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    fitVideo(video, size);
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    // An unplayable file is skipped instead of stopping the show.
                    failed = true;
                }
            });
        }

        void load(File file) {
            clear();
            loadedAt = SystemClock.uptimeMillis();
            isVideo = !Store.isImage(file.getName());
            if (isVideo) {
                photo.setVisibility(View.GONE);
                player.setVolume(0);
                player.setPlayWhenReady(false);
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
                player.prepare();
                return;
            }
            photo.setVisibility(View.VISIBLE);
            final int t = token;
            decoder.execute(() -> {
                if (t != token) return;
                Bitmap bitmap;
                try {
                    bitmap = decode(file);
                } catch (Throwable e) {
                    // Out of memory or a broken file must skip the photo, not crash the app.
                    bitmap = null;
                }
                final Bitmap result = bitmap;
                handler.post(() -> {
                    if (t != token) return;
                    photo.setImageBitmap(result);
                    if (result == null) failed = true;
                    else ready = true;
                });
            });
        }

        // How long the item stays on screen, fades included.
        long length() {
            if (!isVideo) return store.imageSeconds * 1000L + FADE_MS;
            long d = player.getDuration();

            // shortcut: files with no duration (some .ts/.mkv) play at most 10 minutes; fine for gym clips.
            return d == C.TIME_UNSET ? 10 * 60_000L : d;
        }

        long remaining(long now) {
            if (failed) return 0;
            if (isVideo) {
                if (player.getPlaybackState() == Player.STATE_ENDED) return 0;

                // A decoder that stops moving for 10 s counts as finished, so the loop never freezes.
                long pos = player.getCurrentPosition();
                if (pos != lastPos) {
                    lastPos = pos;
                    lastMove = now;
                } else if (now - lastMove > 10_000) {
                    return 0;
                }
            }
            long left = isVideo ? length() - player.getCurrentPosition() : shownAt + length() - now;
            return Math.min(left, endAt - now);
        }

        void show(long now, long fadeMs) {
            shownAt = now;
            lastMove = now;
            lastPos = -1;
            fade.bringToFront();
            fade.animate().cancel();
            fade.setAlpha(0);
            fade.animate().alpha(1).setDuration(fadeMs).setInterpolator(new LinearInterpolator()).start();
            zoom.animate().cancel();
            zoom.setScaleX(1);
            zoom.setScaleY(1);
            if (isVideo) {
                player.play();
                return;
            }

            // Photos only: this speed reaches ZOOM at the planned end.

            // The target is twice as far, so the zoom never stops while the next item is late or still fading in.
            float far = 1 + (ZOOM - 1) * 2;
            zoom.animate().scaleX(far).scaleY(far).setDuration(length() * 2)
                    .setInterpolator(new LinearInterpolator()).start();
        }

        void clear() {
            token++;
            ready = failed = false;
            endAt = Long.MAX_VALUE;
            player.stop();
            player.clearMediaItems();
            fade.animate().cancel();
            fade.setAlpha(0);
            zoom.animate().cancel();
            photo.setImageDrawable(null);
        }
    }

    private void step() {
        long now = SystemClock.uptimeMillis();

        // Cross-fade in progress: the layer alpha is animated, the sound follows it here.
        if (fadeStart > 0) {
            float f = Math.min(1f, (now - fadeStart) / (float) fadeMs);
            if (cur != null) cur.player.setVolume(1 - f);
            next.player.setVolume(f);

            // End on the animator's real alpha, so the layer underneath is never cleared while the new one is still see-through.
            if (f >= 1 && (next.fade.getAlpha() >= 1f || now - fadeStart > fadeMs + 1000)) {
                if (cur != null) cur.clear();
                cur = next;
                next = null;
                fadeStart = 0;
            }
            return;
        }

        long remaining = cur == null ? 0 : cur.remaining(now);
        if (next == null && remaining <= FADE_MS + PRELOAD_MS && now >= retryAt) {
            File file = nextFile();
            if (file == null) {
                if (remaining <= 0) showCard("NESSUN CONTENUTO", "Carica video o foto dall'app o dal sito della TV");
                retryAt = now + 2000;
                return;
            }
            next = cur == a ? b : a;
            next.load(file);
        }
        if (next != null && !next.ready && !next.failed && now - next.loadedAt > 15_000) next.failed = true;
        if (next != null && next.failed) {
            next.clear();
            next = null;
            failures++;

            // Every file failed in a row: say so on screen and stop hammering the decoder.
            boolean allBad = failures >= Math.max(1, store.playlist().size());
            retryAt = now + (allBad ? 10_000 : 500);
            if (allBad && remaining <= 0) showCard("FILE NON LEGGIBILI", "Ricaricali dall'app o dal sito della TV");
            return;
        }
        if (next != null && next.ready && remaining <= FADE_MS) {
            fadeMs = cur == null ? FADE_MS : Math.max(300, remaining);
            fadeMs = Math.max(300, Math.min(fadeMs, next.length() / 2));
            next.show(now, fadeMs);
            fadeStart = now;
            failures = 0;
            if (card.getVisibility() == View.VISIBLE) {
                card.animate().alpha(0).setDuration(fadeMs).withEndAction(() -> card.setVisibility(View.GONE)).start();
            }
        }
    }

    private File nextFile() {
        List<String> items = store.playlist();
        if (items.isEmpty()) return null;
        index = (index + 1) % items.size();
        return new File(Store.FOLDER, items.get(index));
    }

    private void showCard(String title, String text) {
        stopAll();
        cardTitle.setText(title);
        cardText.setText(text + "\n\nIndirizzo: " + ip() + ":" + WebServer.PORT);
        card.animate().cancel();
        card.setAlpha(1);
        card.setVisibility(View.VISIBLE);
    }

    private void stopAll() {
        a.clear();
        b.clear();
        cur = next = null;
        fadeStart = 0;
    }

    // "Riavvia dall'inizio": the current item fades into the first one instead of cutting.
    void onContentChanged(boolean restart) {
        if (!restart || KioskService.awaySince != 0) return;
        index = -1;
        retryAt = 0;
        if (fadeStart > 0) return;
        if (next != null) {
            next.clear();
            next = null;
        }
        if (cur != null) cur.endAt = SystemClock.uptimeMillis() + FADE_MS + PRELOAD_MS;
    }

    // Keep Gold card: logo, gold title, muted text. Shown at start-up and when there is nothing to play.
    private View buildCard() {
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackgroundColor(BLACK);
        ImageView logo = new ImageView(this);
        try (InputStream in = getAssets().open("web/logo.jpg")) {
            logo.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (IOException e) {
            logo.setVisibility(View.GONE);
        }
        card.addView(logo, new LinearLayout.LayoutParams(dp(220), dp(220)));
        cardTitle = new TextView(this);
        cardTitle.setTextColor(GOLD);
        cardTitle.setTextSize(30);
        cardTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        cardTitle.setLetterSpacing(0.06f);
        cardTitle.setGravity(Gravity.CENTER);
        cardTitle.setPadding(0, dp(24), 0, dp(8));
        cardTitle.setText("TV PALESTRA");
        cardText = new TextView(this);
        cardText.setTextColor(MUTED);
        cardText.setTextSize(20);
        cardText.setGravity(Gravity.CENTER);
        card.addView(cardTitle);
        card.addView(cardText);
        return card;
    }

    private View buildBanner() {
        banner = new TextView(this);
        banner.setTextColor(BLACK);
        banner.setGravity(Gravity.CENTER);
        banner.setTextSize(22);
        banner.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        banner.setLetterSpacing(0.04f);
        banner.setPadding(dp(32), dp(16), dp(32), dp(16));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(GOLD);
        pill.setCornerRadius(dp(40));
        banner.setBackground(pill);
        banner.setVisibility(View.GONE);
        return banner;
    }

    private void showBanner() {
        handler.removeCallbacks(hideBanner);

        // Staff open the app icon or the saved site; the address is only a small hint for the first set-up.
        String hint = "\nindirizzo: " + ip() + ":" + WebServer.PORT;
        SpannableString text = new SpannableString("COLLEGATO  ·  APRI L'APP O IL SITO DELLA TV" + hint);
        text.setSpan(new RelativeSizeSpan(0.6f), text.length() - hint.length(), text.length(), 0);
        banner.setText(text);
        banner.animate().cancel();
        banner.setAlpha(0);
        banner.setVisibility(View.VISIBLE);
        banner.animate().alpha(1).setDuration(800).start();
        handler.postDelayed(hideBanner, BANNER_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(netReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        KioskService.activity = this;
        KioskService.awaySince = 0;
        immersive();
        index = -1;
        retryAt = 0;
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(netReceiver);
        handler.removeCallbacks(hideBanner);
        banner.animate().cancel();
        banner.setVisibility(View.GONE);
        KioskService.awaySince = SystemClock.elapsedRealtime();
        handler.removeCallbacks(tick);
        stopAll();
    }

    @Override
    protected void onDestroy() {
        if (KioskService.activity == this) KioskService.activity = null;
        a.player.release();
        b.player.release();
        decoder.shutdown();
        super.onDestroy();
    }

    // Remote buttons do nothing on the show, so a curious finger cannot stop it; Home still leaves and the service brings it back.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return true;
    }

    // Letterboxes videos that are not 16:9 instead of stretching them across the screen.
    private static void fitVideo(TextureView view, VideoSize size) {
        float w = view.getWidth();
        float h = view.getHeight();
        if (size.width == 0 || size.height == 0 || w == 0 || h == 0) return;
        float videoAspect = size.width * size.pixelWidthHeightRatio / size.height;
        float viewAspect = w / h;
        Matrix m = new Matrix();
        if (videoAspect > viewAspect) m.setScale(1, viewAspect / videoAspect, w / 2, h / 2);
        else m.setScale(videoAspect / viewAspect, 1, w / 2, h / 2);
        view.setTransform(m);
    }

    // Photos from the phone are 1920px already; files copied by hand may be larger, so sample them down for this 1 GB device.
    private static Bitmap decode(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 2048) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    private String ip() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        int ip = wifi.getConnectionInfo().getIpAddress();
        return String.format(Locale.ROOT, "%d.%d.%d.%d", ip & 0xff, ip >> 8 & 0xff, ip >> 16 & 0xff, ip >> 24 & 0xff);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private FrameLayout.LayoutParams bottomCenter() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(48);
        return lp;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }
}
