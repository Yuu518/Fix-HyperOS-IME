package io.github.yuu518.hyperosime;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.error.HookFailedError;

public final class MainHook extends XposedModule {
    static final String TAG = "HyperOSIME";
    private final Set<Class<?>> installedManagers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<InputMethodService, ImeSession> sessions = new IdentityHashMap<>();
    private IBinder readerToken = new Binder();
    private final Map<Executable, HookHandle> oldHooks = new HashMap<>();
    private final List<HookHandle> activeHooks = new ArrayList<>();
    private ClassLoader targetLoader;
    private ClipboardAccess clipboard;
    private volatile boolean retiring;
    private String targetPackage;
    private Method bottomColor;
    private boolean initialized;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "API " + getApiVersion() + "; process " + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage() || initialized) {
            return;
        }
        initialized = true;
        targetPackage = param.getPackageName();
        targetLoader = param.getClassLoader();
        installPackage();
    }

    private boolean installPackage() {
        if (CompatibilityPolicy.PHRASE_PACKAGE.equals(targetPackage)) {
            if (clipboard == null) {
                clipboard = new ClipboardAccess(this);
            }
            return clipboard.install(targetLoader);
        }
        if (targetPackage.equals("com.miui.securityinputmethod")
                || targetPackage.equals("io.github.Yuu518.hyperosime")
                || targetPackage.equals("android")) {
            return true;
        }
        try {
            if (CompatibilityPolicy.isStockIme(targetPackage)) {
                installStockSwitcher(targetLoader);
            } else {
                installIme(targetLoader);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | HookFailedError error) {
            report("Unable to install IME hooks in " + targetPackage, error);
            return false;
        }
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        if (!initialized || targetLoader == null || retiring) {
            return false;
        }
        try {
            long deadline = android.os.SystemClock.uptimeMillis() + 2000;
            do {
                boolean ready = onMainThread(() -> {
                    for (ImeSession session : sessions.values()) {
                        if (!session.canReload()) {
                            return false;
                        }
                    }
                    List<Object[]> windows = new ArrayList<>();
                    for (ImeSession session : sessions.values()) {
                        windows.add(session.snapshot());
                    }
                    retiring = true;
                    List<Object[]> readers = clipboard == null ? List.of() : clipboard.suspend();
                    ReloadState state = new ReloadState(targetPackage, targetLoader, readerToken,
                            new ArrayList<>(installedManagers), windows, readers);
                    try {
                        param.setSavedInstanceState(state.export());
                    } catch (RuntimeException error) {
                        if (clipboard != null) {
                            clipboard.restore(readers);
                        }
                        retiring = false;
                        throw error;
                    }
                    for (ImeSession session : sessions.values()) {
                        session.close();
                    }
                    installedManagers.clear();
                    bottomColor = null;
                    info("Hot reload prepared: " + targetPackage);
                    return true;
                });
                if (ready) {
                    return true;
                }
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    break;
                }
                Thread.sleep(25);
            } while (android.os.SystemClock.uptimeMillis() < deadline);
            info("Hot reload deferred: keyboard render is busy; restart the scoped app");
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            report("Unable to prepare hot reload; restart the scoped app", error);
        }
        return false;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        try {
            onMainThread(() -> {
                for (HookHandle handle : param.getOldHookHandles()) {
                    oldHooks.put(handle.getExecutable(), handle);
                }
                try {
                    ReloadState state = ReloadState.read(param.getSavedInstanceState());
                    targetPackage = state.packageName;
                    targetLoader = state.loader;
                    readerToken = (IBinder) state.token;
                    initialized = true;
                    if (CompatibilityPolicy.PHRASE_PACKAGE.equals(targetPackage)) {
                        clipboard = new ClipboardAccess(this);
                        clipboard.restore(state.readers);
                    }
                    if (!installPackage()) {
                        throw new IllegalStateException("Package hook installation failed");
                    }
                    for (Class<?> manager : state.managers) {
                        if (CompatibilityPolicy.isStockIme(targetPackage)) {
                            installStockManager(manager);
                        } else {
                            installPhrase(manager.getClassLoader());
                        }
                    }
                    for (Object[] window : state.sessions) {
                        AtomicBoolean alive = (AtomicBoolean) window[4];
                        if (!alive.get()) {
                            continue;
                        }
                        InputMethodService service = (InputMethodService) window[0];
                        if (service.getWindow() == null || service.getWindow().getWindow() == null) {
                            continue;
                        }
                        ImeSession session = new ImeSession(this, service, (ViewGroup) window[1],
                                (View) window[2], (ViewGroup) window[3], alive);
                        sessions.put(service, session);
                        session.attach();
                        registerReader(service);
                    }
                    info("Hot reload complete: " + targetPackage + "; sessions=" + sessions.size());
                } catch (Exception | HookFailedError error) {
                    retiring = true;
                    for (ImeSession session : sessions.values()) {
                        session.close();
                    }
                    sessions.clear();
                    if (clipboard != null) {
                        clipboard.suspend();
                    }
                    for (HookHandle handle : activeHooks) {
                        handle.unhook();
                    }
                    activeHooks.clear();
                    throw error;
                } finally {
                    for (HookHandle handle : oldHooks.values()) {
                        handle.unhook();
                    }
                    oldHooks.clear();
                }
                return null;
            });
        } catch (Exception | HookFailedError error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            for (HookHandle handle : param.getOldHookHandles()) {
                try {
                    handle.unhook();
                } catch (IllegalStateException ignored) {
                }
            }
            report("Hot reload failed; restart the scoped app", error);
        }
    }

    private <T> T onMainThread(Callable<T> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return action.call();
        }
        ReloadTask<T> task = new ReloadTask<>(action);
        Handler handler = new Handler(Looper.getMainLooper());
        if (!handler.post(task)) {
            throw new IllegalStateException("Main thread is unavailable");
        }
        try {
            return task.await(3000);
        } finally {
            handler.removeCallbacks(task);
        }
    }

    void intercept(Method method, Hooker hooker, HookInstallation installation) {
        intercept(method, hooker, false, installation);
    }

    private void intercept(Method method, Hooker hooker, boolean lifecycle, HookInstallation installation) {
        if (retiring) {
            throw new IllegalStateException("Module is retiring");
        }
        Hooker guarded = chain -> retiring && !lifecycle ? chain.proceed() : hooker.intercept(chain);
        HookHandle previous = oldHooks.get(method);
        HookHandle handle = previous == null
                ? hook(method).intercept(guarded) : previous.replaceHook(guarded);
        oldHooks.remove(method);
        activeHooks.add(handle);
        installation.onRollback(() -> {
            try {
                handle.unhook();
            } finally {
                activeHooks.remove(handle);
            }
        });
    }

    private void attachSession(InputMethodService service, ViewGroup input, View root, ViewGroup bottom) {
        if (retiring) {
            return;
        }
        ImeSession previous = sessions.remove(service);
        if (previous != null) {
            previous.close();
        }
        ImeSession session = new ImeSession(this, service, input, root, bottom);
        sessions.put(service, session);
        session.attach();
    }

    private synchronized void installStockManager(Class<?> manager) throws ReflectiveOperationException {
        if (retiring) {
            return;
        }
        if (HookInstallation.installOnce(installedManagers, manager, installation -> {
            Method list = HookContracts.method(manager, "getSupportIme", java.util.List.class);
            Field helper = HookContracts.staticField(manager, "sBottomViewHelper");
            installSwitcher(list, helper, installation);
        })) {
            info("Stock IME switcher-only hook installed: " + targetPackage);
        }
    }

    private void installStockSwitcher(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> moduleManager = Class.forName("android.inputmethodservice.InputMethodModuleManager", false, loader);
        Method loadDex = HookContracts.method(moduleManager, "loadDex", void.class, ClassLoader.class, String.class);
        try (HookInstallation installation = new HookInstallation()) {
            intercept(loadDex, chain -> {
                Object result = chain.proceed();
                try {
                    Class<?> manager = Class.forName("com.miui.inputmethod.InputMethodBottomManager", false,
                            (ClassLoader) chain.getArg(0));
                    installStockManager(manager);
                } catch (ReflectiveOperationException | RuntimeException | HookFailedError error) {
                    report("Unable to install stock IME switcher hook", error);
                }
                return result;
            }, installation);
            installation.commit();
        }
    }

    private void installIme(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> injector = Class.forName("android.inputmethodservice.InputMethodServiceInjector", false, loader);
        Class<?> moduleManager = Class.forName("android.inputmethodservice.InputMethodModuleManager", false, loader);
        Method addBottom = HookContracts.method(injector, "addMiuiBottomView", void.class,
                LayoutInflater.class, ViewGroup.class, ViewGroup.class, View.class, ViewGroup.class,
                InputMethodManager.class, InputMethodService.class);
        Method loadDex = HookContracts.method(moduleManager, "loadDex", void.class, ClassLoader.class, String.class);
        Method support = HookContracts.method(injector, "isImeSupport", boolean.class, Context.class);
        Field supportFlag = HookContracts.supportField(injector);
        Method dispatchInsets = HookContracts.method(View.class, "dispatchApplyWindowInsets",
                WindowInsets.class, WindowInsets.class);
        Method rootInsets = HookContracts.method(View.class, "getRootWindowInsets", WindowInsets.class);
        Method destroy = HookContracts.method(InputMethodService.class, "onDestroy", void.class);

        try (HookInstallation installation = new HookInstallation()) {
            intercept(support, chain -> {
                Context context = (Context) chain.getArg(0);
                return context != null && targetPackage.equals(context.getPackageName())
                        ? true : chain.proceed();
            }, installation);
            intercept(loadDex, chain -> {
                Object result = chain.proceed();
                try {
                    installPhrase((ClassLoader) chain.getArg(0));
                } catch (ReflectiveOperationException | RuntimeException | HookFailedError error) {
                    report("Unable to install phrase hooks", error);
                }
                return result;
            }, installation);
            intercept(addBottom, chain -> {
                InputMethodService service = (InputMethodService) chain.getArg(6);
                if (!targetPackage.equals(service.getPackageName())) {
                    return chain.proceed();
                }
                registerReader(service);
                supportFlag.setInt(null, 1);
                Object result = chain.proceed();
                try {
                    attachSession(service, (ViewGroup) chain.getArg(2),
                            (View) chain.getArg(3), (ViewGroup) chain.getArg(4));
                    info("Bottom attached: " + targetPackage);
                } catch (RuntimeException error) {
                    report("Unable to track IME layout", error);
                }
                return result;
            }, installation);
            intercept(dispatchInsets, chain -> {
                View view = (View) chain.getThisObject();
                for (ImeSession session : sessions.values()) {
                    if (session.inputFrame == view && session.hasBottom()) {
                        return chain.proceed(new Object[]{session.withoutNavigationBottom((WindowInsets) chain.getArg(0))});
                    }
                }
                return chain.proceed();
            }, installation);
            intercept(rootInsets, chain -> {
                WindowInsets original = (WindowInsets) chain.proceed();
                View view = (View) chain.getThisObject();
                if (original != null) {
                    for (ImeSession session : sessions.values()) {
                        if (session.hasBottom() && session.isKeyboardWindow(view)) {
                            return session.withoutNavigationBottom(original);
                        }
                    }
                }
                return original;
            }, installation);
            intercept(destroy, chain -> {
                ImeSession session = sessions.remove((InputMethodService) chain.getThisObject());
                if (session != null) {
                    session.destroyed();
                }
                return chain.proceed();
            }, true, installation);
            installation.commit();
        }
        info("IME hooks installed: " + targetPackage);
    }

    private synchronized void installPhrase(ClassLoader loader) throws ReflectiveOperationException {
        if (retiring) {
            return;
        }
        Class<?> manager = Class.forName("com.miui.inputmethod.InputMethodBottomManager", false, loader);
        if (HookInstallation.installOnce(installedManagers, manager, installation -> {
            Method support = HookContracts.method(manager, "isImeSupport", boolean.class, InputMethodService.class);
            Field supportFlag = HookContracts.supportField(manager);
            Method color = HookContracts.method(manager, "setBottomColor", void.class,
                    boolean.class, int.class, int.class, int.class);
            HookContracts.staticField(manager, "sBottomView");
            Field helperField = HookContracts.staticField(manager, "sBottomViewHelper");
            Method shown = HookContracts.method(manager, "onWindowShown", void.class);
            Method list = HookContracts.method(manager, "getSupportIme", java.util.List.class);
            Class<?> util = Class.forName("com.miui.inputmethod.InputMethodUtil", false, loader);
            Method leftButton = HookContracts.method(util, "getLeftBottomSelectValue",
                    String.class, Context.class);
            Method rightButton = HookContracts.method(util, "getRightBottomSelectValue",
                    String.class, Context.class);

            intercept(support, chain -> {
                InputMethodService service = (InputMethodService) chain.getArg(0);
                return service != null && targetPackage.equals(service.getPackageName()) ? true : chain.proceed();
            }, installation);
            installation.setSupport(supportFlag);
            intercept(shown, chain -> {
                Object helper = helperField.get(null);
                if (helper != null) {
                    InputMethodService service = (InputMethodService) HookContracts.field(helper.getClass(), "mInputMethodService", helper);
                    registerReader(service);
                }
                return chain.proceed();
            }, installation);
            for (boolean left : new boolean[]{true, false}) {
                intercept(left ? leftButton : rightButton, chain -> {
                    Context context = (Context) chain.getArg(0);
                    String value = Settings.Secure.getString(context.getContentResolver(),
                            left ? "full_screen_keyboard_left_function" : "full_screen_keyboard_right_function");
                    return CompatibilityPolicy.buttonFunction(value, left);
                }, installation);
            }
            installSwitcher(list, helperField, installation);
            Method previousColor = bottomColor;
            installation.onRollback(() -> bottomColor = previousColor);
            bottomColor = color;
        })) {
            info("Phrase support and switcher hooks installed: " + targetPackage);
        }
    }

    private void installSwitcher(Method list, Field helperField, HookInstallation installation) {
        intercept(list, chain -> {
            Object helper = helperField.get(null);
            if (helper == null) {
                return chain.proceed();
            }
            InputMethodService service = (InputMethodService) HookContracts.field(helper.getClass(), "mInputMethodService", helper);
            InputMethodManager imm = service.getSystemService(InputMethodManager.class);
            return new ArrayList<>(imm.getEnabledInputMethodList());
        }, installation);
    }

    private void registerReader(InputMethodService service) {
        if (retiring) {
            return;
        }
        try {
            Bundle extras = new Bundle();
            extras.putBinder("token", readerToken);
            Bundle result = service.getContentResolver().call(Uri.parse("content://" + CompatibilityPolicy.PROVIDER_AUTHORITY),
                    CompatibilityPolicy.REGISTER_METHOD, targetPackage, extras);
            if (result == null || !result.getBoolean("registered")) {
                info("Clipboard reader unavailable; enable module scope for com.miui.phrase and restart it");
            }
        } catch (RuntimeException error) {
            report("Clipboard reader registration failed", error);
        }
    }

    void info(String message) {
        log(Log.INFO, TAG, message);
    }

    void applyBottomColor(int color, int foreground) {
        if (bottomColor == null) {
            return;
        }
        try {
            View view = (View) HookContracts.field(bottomColor.getDeclaringClass(), "sBottomView", null);
            if (view != null && view.getBackground() instanceof ColorDrawable background
                    && background.getColor() == color) {
                return;
            }
            bottomColor.invoke(null, true, color, foreground, foreground & 0x99ffffff);
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("Unable to synchronize bottom color", error);
        }
    }

    void report(String message, Throwable error) {
        log(Log.ERROR, TAG, message, error);
    }
}
