package io.github.yuu518.hyperosime;

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
import android.view.WindowInsetsController;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

final class BottomTheme implements AutoCloseable {
    private final MainHook module;
    private final InputMethodService service;
    private final View input;
    private final View bottom;
    private final BooleanSupplier visible;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ViewTreeObserver.OnDrawListener drawListener = this::onDraw;
    private final Runnable sample = this::sample;
    private final SampleSchedule schedule = new SampleSchedule();
    private int originalAppearance;
    private boolean appearanceChanged;

    BottomTheme(MainHook module, InputMethodService service, View input, View bottom,
                BooleanSupplier visible) {
        this.module = module;
        this.service = service;
        this.input = input;
        this.bottom = bottom;
        this.visible = visible;
    }

    void attach() {
        input.getViewTreeObserver().addOnDrawListener(drawListener);
    }

    boolean canReload() {
        return schedule.canReload();
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
        if (window == null) {
            return;
        }
        if (!visible.getAsBoolean()) {
            restoreAppearance(window);
            return;
        }
        int[] edge = new int[2];
        int[] origin = new int[2];
        bottom.getLocationInWindow(edge);
        input.getLocationInWindow(origin);
        int y = Math.min(edge[1], origin[1] + input.getHeight()) - 2;
        int width = input.getWidth();
        if (y <= origin[1] || width < 32) {
            return;
        }
        Rect strip = new Rect(origin[0] + width / 8, y,
                origin[0] + width * 7 / 8, y + 1);
        Bitmap pixels = Bitmap.createBitmap(24, 1, Bitmap.Config.ARGB_8888);
        schedule.copyStarted(SystemClock.uptimeMillis());
        try {
            PixelCopy.request(window, strip, pixels, result -> {
                try {
                    if (result == PixelCopy.SUCCESS && !schedule.isClosed() && visible.getAsBoolean()) {
                        int[] colors = new int[24];
                        pixels.getPixels(colors, 0, 24, 0, 0, 24, 1);
                        Arrays.sort(colors);
                        int color = colors[colors.length / 2] | 0xff000000;
                        boolean light = Color.luminance(color) > 0.35f;
                        module.applyBottomColor(color, light ? 0xff303030 : 0xffeeeeee);
                        WindowInsetsController controller = window.getInsetsController();
                        if (controller != null) {
                            int mask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                            if (!appearanceChanged) {
                                originalAppearance = controller.getSystemBarsAppearance() & mask;
                                appearanceChanged = true;
                            }
                            int desired = light ? mask : 0;
                            if ((controller.getSystemBarsAppearance() & mask) != desired) {
                                controller.setSystemBarsAppearance(desired, mask);
                            }
                        }
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

    private void restoreAppearance(Window window) {
        if (appearanceChanged && window.getInsetsController() != null) {
            window.getInsetsController().setSystemBarsAppearance(originalAppearance,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            appearanceChanged = false;
        }
    }

    @Override
    public void close() {
        if (schedule.isClosed()) {
            return;
        }
        schedule.close();
        handler.removeCallbacks(sample);
        ViewTreeObserver observer = input.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnDrawListener(drawListener);
        }
        Window window = service.getWindow().getWindow();
        if (window != null) {
            restoreAppearance(window);
        }
    }
}
