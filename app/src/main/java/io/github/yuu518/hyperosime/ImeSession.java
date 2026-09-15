package io.github.yuu518.hyperosime;

import android.graphics.Insets;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

final class ImeSession implements AutoCloseable {
    final ViewGroup inputFrame;
    private final MainHook module;
    private final InputMethodService service;
    private final View root;
    private final ViewGroup bottom;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::onLayout;
    private boolean hadBottom;
    private int lastBottomHeight = -1;
    private final BottomTheme theme;

    ImeSession(MainHook module, InputMethodService service, ViewGroup inputFrame, View root, ViewGroup bottom) {
        this.module = module;
        this.service = service;
        this.inputFrame = inputFrame;
        this.root = root;
        this.bottom = bottom;
        this.theme = new BottomTheme(module, service, inputFrame, bottom, this::hasBottom);
    }

    void attach() {
        root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        theme.attach();
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
        if (visible != hadBottom) {
            hadBottom = visible;
            inputFrame.requestApplyInsets();
        }
        if (height != lastBottomHeight) {
            lastBottomHeight = height;
            module.info("Layout " + service.getPackageName() + ": bottom=" + height
                    + ", input=" + inputFrame.getHeight() + ", root=" + root.getHeight());
        }
    }

    WindowInsets withoutNavigationBottom(WindowInsets original) {
        int type = WindowInsets.Type.navigationBars();
        Insets visible = original.getInsets(type);
        Insets stable = original.getInsetsIgnoringVisibility(type);
        return new WindowInsets.Builder(original)
                .setInsets(type, Insets.of(visible.left, visible.top, visible.right, 0))
                .setInsetsIgnoringVisibility(type, Insets.of(stable.left, stable.top, stable.right, 0))
                .build();
    }

    @Override
    public void close() {
        theme.close();
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(layoutListener);
        }
    }
}
