package io.github.yuu518.hyperosime;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Binder;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

public final class MainHook extends XposedModule {
    static final String TAG = "HyperOSIME";
    private final Set<Class<?>> installedManagers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<InputMethodService, ImeSession> sessions = new IdentityHashMap<>();
    private final Binder readerToken = new Binder();
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
        if (CompatibilityPolicy.PHRASE_PACKAGE.equals(targetPackage)) {
            new ClipboardAccess(this).install(param.getClassLoader());
            return;
        }
        if (targetPackage.equals("com.miui.securityinputmethod")
                || targetPackage.equals("io.github.Yuu518.hyperosime")
                || targetPackage.equals("android")) {
            return;
        }
        try {
            if (CompatibilityPolicy.isStockIme(targetPackage)) {
                installStockSwitcher(param.getClassLoader());
            } else {
                installIme(param.getClassLoader());
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("Unable to install IME hooks in " + targetPackage, error);
        }
    }

    private void installStockSwitcher(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> moduleManager = Class.forName("android.inputmethodservice.InputMethodModuleManager", false, loader);
        Method loadDex = HookContracts.method(moduleManager, "loadDex", void.class, ClassLoader.class, String.class);
        hook(loadDex).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Class<?> manager = Class.forName("com.miui.inputmethod.InputMethodBottomManager", false,
                        (ClassLoader) chain.getArg(0));
                synchronized (installedManagers) {
                    if (!installedManagers.contains(manager)) {
                        installSwitcher(manager);
                        installedManagers.add(manager);
                        info("Stock IME switcher-only hook installed: " + targetPackage);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                report("Unable to install stock IME switcher hook", error);
            }
            return result;
        });
    }

    private void installIme(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> injector = Class.forName("android.inputmethodservice.InputMethodServiceInjector", false, loader);
        Class<?> moduleManager = Class.forName("android.inputmethodservice.InputMethodModuleManager", false, loader);
        Method addBottom = HookContracts.method(injector, "addMiuiBottomView", void.class,
                LayoutInflater.class, ViewGroup.class, ViewGroup.class, View.class, ViewGroup.class,
                InputMethodManager.class, InputMethodService.class);
        Method loadDex = HookContracts.method(moduleManager, "loadDex", void.class, ClassLoader.class, String.class);
        Method support = HookContracts.method(injector, "isImeSupport", boolean.class, Context.class);

        hook(support).intercept(chain -> {
            Context context = (Context) chain.getArg(0);
            return context != null && targetPackage.equals(context.getPackageName())
                    ? true : chain.proceed();
        });
        hook(loadDex).intercept(chain -> {
            Object result = chain.proceed();
            try {
                installPhrase((ClassLoader) chain.getArg(0));
            } catch (ReflectiveOperationException | RuntimeException error) {
                report("Unable to install phrase hooks", error);
            }
            return result;
        });
        hook(addBottom).intercept(chain -> {
            InputMethodService service = (InputMethodService) chain.getArg(6);
            if (!targetPackage.equals(service.getPackageName())) {
                return chain.proceed();
            }
            registerReader(service);
            HookContracts.setSupport(injector);
            Object result = chain.proceed();
            try {
                ImeSession previous = sessions.remove(service);
                if (previous != null) {
                    previous.close();
                }
                ImeSession session = new ImeSession(this, service, (ViewGroup) chain.getArg(2),
                        (View) chain.getArg(3), (ViewGroup) chain.getArg(4));
                sessions.put(service, session);
                session.attach();
                info("Bottom attached: " + targetPackage);
            } catch (RuntimeException error) {
                report("Unable to track IME layout", error);
            }
            return result;
        });
        hook(HookContracts.method(View.class, "dispatchApplyWindowInsets", WindowInsets.class, WindowInsets.class))
                .intercept(chain -> {
                    View view = (View) chain.getThisObject();
                    for (ImeSession session : sessions.values()) {
                        if (session.inputFrame == view && session.hasBottom()) {
                            return chain.proceed(new Object[]{session.withoutNavigationBottom((WindowInsets) chain.getArg(0))});
                        }
                    }
                    return chain.proceed();
                });
        hook(HookContracts.method(View.class, "getRootWindowInsets", WindowInsets.class)).intercept(chain -> {
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
        });
        hook(HookContracts.method(InputMethodService.class, "onDestroy", void.class)).intercept(chain -> {
            ImeSession session = sessions.remove((InputMethodService) chain.getThisObject());
            if (session != null) {
                session.close();
            }
            return chain.proceed();
        });
        info("IME hooks installed: " + targetPackage);
    }

    private synchronized void installPhrase(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> manager = Class.forName("com.miui.inputmethod.InputMethodBottomManager", false, loader);
        if (installedManagers.contains(manager)) {
            return;
        }
        Method support = HookContracts.method(manager, "isImeSupport", boolean.class, InputMethodService.class);
        hook(support).intercept(chain -> {
            InputMethodService service = (InputMethodService) chain.getArg(0);
            return service != null && targetPackage.equals(service.getPackageName()) ? true : chain.proceed();
        });
        HookContracts.setSupport(manager);
        bottomColor = HookContracts.method(manager, "setBottomColor", void.class,
                boolean.class, int.class, int.class, int.class);
        installedManagers.add(manager);
        hook(HookContracts.method(manager, "onWindowShown", void.class)).intercept(chain -> {
            Object helper = HookContracts.field(manager, "sBottomViewHelper", null);
            if (helper != null) {
                InputMethodService service = (InputMethodService) HookContracts.field(helper.getClass(), "mInputMethodService", helper);
                registerReader(service);
            }
            return chain.proceed();
        });
        Class<?> util = Class.forName("com.miui.inputmethod.InputMethodUtil", false, loader);
        for (boolean left : new boolean[]{true, false}) {
            Method button = HookContracts.method(util, left ? "getLeftBottomSelectValue" : "getRightBottomSelectValue",
                    String.class, Context.class);
            hook(button).intercept(chain -> {
                Context context = (Context) chain.getArg(0);
                String value = Settings.Secure.getString(context.getContentResolver(),
                        left ? "full_screen_keyboard_left_function" : "full_screen_keyboard_right_function");
                return CompatibilityPolicy.buttonFunction(value, left);
            });
        }
        installSwitcher(manager);
        info("Phrase support and switcher hooks installed: " + targetPackage);
    }

    private void installSwitcher(Class<?> manager) throws ReflectiveOperationException {
        Method list = HookContracts.method(manager, "getSupportIme", java.util.List.class);
        hook(list).intercept(chain -> {
            Object helper = HookContracts.field(manager, "sBottomViewHelper", null);
            if (helper == null) {
                return chain.proceed();
            }
            InputMethodService service = (InputMethodService) HookContracts.field(helper.getClass(), "mInputMethodService", helper);
            InputMethodManager imm = service.getSystemService(InputMethodManager.class);
            return new ArrayList<>(imm.getEnabledInputMethodList());
        });
    }

    private void registerReader(InputMethodService service) {
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
