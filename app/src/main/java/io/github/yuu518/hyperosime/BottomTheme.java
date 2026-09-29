package io.github.yuu518.hyperosime;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

final class BottomTheme implements AutoCloseable {
    private static final int DARK_FOREGROUND = 0xff303030;
    private static final int LIGHT_FOREGROUND = 0xffeeeeee;
    private static final int SAMPLES = 24;
    private static final int EDGE_SAMPLES = 6;

    private final MainHook module;
    private final InputMethodService service;
    private final View bottom;
    private final BooleanSupplier visible;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ViewTreeObserver.OnDrawListener drawListener = this::onDraw;
    private final Runnable sample = this::sample;
    private final SampleSchedule schedule = new SampleSchedule();
    private int foreground;
    private int appliedForeground;

    BottomTheme(MainHook module, InputMethodService service, View bottom, BooleanSupplier visible) {
        this.module = module;
        this.service = service;
        this.bottom = bottom;
        this.visible = visible;
        boolean night = (service.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        this.foreground = night ? LIGHT_FOREGROUND : DARK_FOREGROUND;
    }

    void attach() {
        bottom.getViewTreeObserver().addOnDrawListener(drawListener);
    }

    boolean canReload() {
        return schedule.canReload();
    }

    void apply() {
        if (schedule.isClosed() || !visible.getAsBoolean()) {
            return;
        }
        boolean force = foreground != appliedForeground;
        appliedForeground = foreground;
        module.applyBottomColor(Color.TRANSPARENT, foreground, force);
    }

    private void onDraw() {
        postSample(schedule.onDraw(SystemClock.uptimeMillis()));
    }

    private void postSample(long delay) {
        if (delay >= 0) {
            handler.postDelayed(sample, delay);
        }
    }

    private void sample() {
        if (!schedule.beginSample()) {
            return;
        }
        Window window = service.getWindow().getWindow();
        if (window == null || !visible.getAsBoolean()) {
            return;
        }
        int[] origin = new int[2];
        bottom.getLocationInWindow(origin);
        int width = bottom.getWidth();
        if (width < SAMPLES || bottom.getHeight() < 8) {
            return;
        }
        int y = origin[1] + 4;
        Rect strip = new Rect(origin[0], y, origin[0] + width, y + 1);
        Bitmap pixels = Bitmap.createBitmap(SAMPLES, 1, Bitmap.Config.ARGB_8888);
        schedule.copyStarted(SystemClock.uptimeMillis());
        try {
            PixelCopy.request(window, strip, pixels, result -> {
                try {
                    if (result == PixelCopy.SUCCESS && !schedule.isClosed() && visible.getAsBoolean()) {
                        int[] colors = new int[SAMPLES];
                        pixels.getPixels(colors, 0, SAMPLES, 0, 0, SAMPLES, 1);
                        float[] luminance = new float[EDGE_SAMPLES * 2];
                        for (int i = 0; i < EDGE_SAMPLES; i++) {
                            luminance[i] = Color.luminance(colors[i] | 0xff000000);
                            luminance[EDGE_SAMPLES + i] = Color.luminance(colors[SAMPLES - 1 - i] | 0xff000000);
                        }
                        Arrays.sort(luminance);
                        foreground = luminance[EDGE_SAMPLES] > 0.35f ? DARK_FOREGROUND : LIGHT_FOREGROUND;
                        apply();
                    }
                } finally {
                    pixels.recycle();
                    postSample(schedule.copyFinished(SystemClock.uptimeMillis()));
                }
            }, handler);
        } catch (IllegalArgumentException error) {
            pixels.recycle();
            postSample(schedule.copyFinished(SystemClock.uptimeMillis()));
        }
    }

    @Override
    public void close() {
        if (schedule.isClosed()) {
            return;
        }
        schedule.close();
        handler.removeCallbacks(sample);
        ViewTreeObserver observer = bottom.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnDrawListener(drawListener);
        }
    }
}
