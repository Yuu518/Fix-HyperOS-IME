package io.github.yuu518.hyperosime;

import android.graphics.Color;
import android.graphics.Insets;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;

import java.util.concurrent.atomic.AtomicBoolean;

final class ImeSession implements AutoCloseable {
    final ViewGroup inputFrame;
    private final MainHook module;
    private final InputMethodService service;
    private final View root;
    private final ViewGroup bottom;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::onLayout;
    private final AtomicBoolean alive;
    private boolean hadBottom;
    private int overlay;
    private int originalTopMargin;
    private boolean marginChanged;
    private int lastForeground;
    private String lastLayout;

    ImeSession(MainHook module, InputMethodService service, ViewGroup inputFrame, View root, ViewGroup bottom) {
        this(module, service, inputFrame, root, bottom, new AtomicBoolean(true));
    }

    ImeSession(MainHook module, InputMethodService service, ViewGroup inputFrame, View root, ViewGroup bottom,
               AtomicBoolean alive) {
        this.module = module;
        this.service = service;
        this.inputFrame = inputFrame;
        this.root = root;
        this.bottom = bottom;
        this.alive = alive;
    }

    void attach() {
        root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        onLayout();
        inputFrame.invalidate();
    }

    Object[] snapshot() {
        return new Object[]{service, inputFrame, root, bottom, alive};
    }

    boolean canReload() {
        return true;
    }

    void destroyed() {
        alive.set(false);
        close();
    }

    boolean hasBottom() {
        return bottom != null && bottom.isShown() && bottom.getHeight() > 0 && bottom.getChildCount() > 0;
    }

    boolean isKeyboardWindow(View view) {
        if (view.getRootView() != root.getRootView()) {
            return false;
        }
        View current = view;
        while (current != null) {
            if (current == bottom) {
                return false;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return true;
    }

    private void onLayout() {
        boolean visible = hasBottom();
        int height = visible ? bottom.getHeight() : 0;
        setOverlayMargin(height);
        if (visible != hadBottom || height != overlay) {
            hadBottom = visible;
            overlay = height;
            inputFrame.requestApplyInsets();
        }
        if (visible) {
            applyTransparentBottom();
        }
        logLayout();
    }

    private void setOverlayMargin(int height) {
        if (!(bottom.getLayoutParams() instanceof LinearLayout.LayoutParams params)) {
            return;
        }
        if (!marginChanged) {
            originalTopMargin = params.topMargin;
            marginChanged = true;
        }
        int desired = originalTopMargin - height;
        if (params.topMargin != desired) {
            params.topMargin = desired;
            bottom.setLayoutParams(params);
        }
    }

    private void restoreMargin() {
        if (marginChanged && bottom.getLayoutParams() instanceof LinearLayout.LayoutParams params) {
            params.topMargin = originalTopMargin;
            bottom.setLayoutParams(params);
            marginChanged = false;
        }
    }

    private int appearance() {
        Window window = service.getWindow().getWindow();
        WindowInsetsController controller = window == null ? null : window.getInsetsController();
        return controller == null ? -1 : controller.getSystemBarsAppearance();
    }

    private void applyTransparentBottom() {
        int appearance = appearance();
        boolean light = appearance != -1
                && (appearance & WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) != 0;
        int foreground = light ? 0xff303030 : 0xffeeeeee;
        boolean force = foreground != lastForeground;
        lastForeground = foreground;
        module.applyBottomColor(Color.TRANSPARENT, foreground, force);
    }

    private void logLayout() {
        StringBuilder text = new StringBuilder("Overlay ").append(service.getPackageName())
                .append(": overlay=").append(overlay)
                .append(", appearance=0x").append(Integer.toHexString(appearance()));
        if (bottom.getParent() instanceof ViewGroup panel) {
            for (int i = 0; i < panel.getChildCount(); i++) {
                View child = panel.getChildAt(i);
                text.append("; ").append(name(child))
                        .append(" top=").append(child.getTop())
                        .append(" h=").append(child.getHeight())
                        .append(" vis=").append(child.getVisibility());
                if (child.getLayoutParams() instanceof LinearLayout.LayoutParams params) {
                    text.append(" lp.h=").append(params.height)
                            .append(" w=").append(params.weight)
                            .append(" mt=").append(params.topMargin)
                            .append(" mb=").append(params.bottomMargin);
                }
            }
        }
        WindowInsets insets = inputFrame.getRootWindowInsets();
        if (insets != null) {
            text.append("; nav=").append(insets.getInsets(WindowInsets.Type.navigationBars()));
        }
        if (inputFrame.getChildCount() > 0) {
            View content = inputFrame.getChildAt(0);
            text.append("; content h=").append(content.getHeight())
                    .append(" pb=").append(content.getPaddingBottom());
        }
        String line = text.toString();
        if (!line.equals(lastLayout)) {
            lastLayout = line;
            module.info(line);
        }
    }

    private static String name(View view) {
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (RuntimeException error) {
            return view.getClass().getSimpleName();
        }
    }

    WindowInsets withBottomInset(WindowInsets original) {
        int type = WindowInsets.Type.navigationBars();
        Insets visible = original.getInsets(type);
        Insets stable = original.getInsetsIgnoringVisibility(type);
        return new WindowInsets.Builder(original)
                .setInsets(type, Insets.of(visible.left, visible.top, visible.right, overlay))
                .setInsetsIgnoringVisibility(type, Insets.of(stable.left, stable.top, stable.right, overlay))
                .build();
    }

    @Override
    public void close() {
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(layoutListener);
        }
        restoreMargin();
    }
}
