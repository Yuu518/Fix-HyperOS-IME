package io.github.yuu518.hyperosime;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class ClipboardAccess {
    private final MainHook module;
    private final ConcurrentHashMap<Integer, Registration> readers = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> reading = new ThreadLocal<>();

    ClipboardAccess(MainHook module) {
        this.module = module;
    }

    void install(ClassLoader loader) {
        try {
            Class<?> provider = Class.forName("com.miui.provider.InputProvider", false, loader);
            Method check = HookContracts.providerCheck(provider);
            Method call = HookContracts.method(provider, "call", Bundle.class, String.class, String.class, Bundle.class);
            module.hook(check).intercept(chain -> {
                if (Boolean.TRUE.equals(reading.get())) {
                    return true;
                }
                return chain.proceed();
            });
            module.hook(call).intercept(chain -> {
                if (!CompatibilityPolicy.REGISTER_METHOD.equals(chain.getArg(0))) {
                    return chain.proceed();
                }
                Context context = ((ContentProvider) chain.getThisObject()).getContext();
                String packageName = (String) chain.getArg(1);
                Bundle extras = (Bundle) chain.getArg(2);
                IBinder token = extras == null ? null : extras.getBinder("token");
                int uid = Binder.getCallingUid();
                boolean accepted = token != null && token.isBinderAlive()
                        && isCurrentIme(context, uid, packageName, true);
                if (accepted) {
                    Registration registration = new Registration(uid, packageName, token);
                    try {
                        token.linkToDeath(registration, 0);
                        Registration previous = readers.put(uid, registration);
                        if (previous != null) {
                            previous.token.unlinkToDeath(previous, 0);
                        }
                        if (!token.isBinderAlive()) {
                            registration.binderDied();
                            accepted = false;
                        }
                    } catch (RemoteException error) {
                        accepted = false;
                    }
                }
                Bundle result = new Bundle();
                result.putBoolean("registered", accepted);
                module.info("Clipboard reader " + (accepted ? "registered: " : "rejected: ") + packageName);
                return result;
            });
            for (Method method : provider.getDeclaredMethods()) {
                if (!method.getName().equals("query") && !method.getName().equals("getType")) {
                    continue;
                }
                module.deoptimize(method);
                module.hook(method).intercept(chain -> {
                    Boolean previous = reading.get();
                    int uid = Binder.getCallingUid();
                    Registration registration = readers.get(uid);
                    Context context = ((ContentProvider) chain.getThisObject()).getContext();
                    boolean allowed = registration != null && registration.token.isBinderAlive()
                            && isCurrentIme(context, uid, registration.packageName, true);
                    reading.set(allowed);
                    try {
                        Object result = chain.proceed();
                        if (allowed && method.getName().equals("query")
                                && registration.readLogged.compareAndSet(false, true)) {
                            module.info("Clipboard query completed: " + registration.packageName);
                        }
                        return result;
                    } finally {
                        if (previous == null) {
                            reading.remove();
                        } else {
                            reading.set(previous);
                        }
                    }
                });
            }
            module.info("Clipboard read guards installed; writes and signatures unchanged");
        } catch (ReflectiveOperationException | RuntimeException error) {
            module.report("Unsupported phrase provider; clipboard hooks unavailable", error);
        }
    }

    private boolean isCurrentIme(Context context, int uid, String packageName, boolean registered) {
        if (context == null || packageName == null) {
            return false;
        }
        try {
            String setting = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            ComponentName component = setting == null ? null : ComponentName.unflattenFromString(setting);
            if (component == null) {
                return false;
            }
            ServiceInfo service = context.getPackageManager().getServiceInfo(component, 0);
            return CompatibilityPolicy.isReader(uid, service.applicationInfo.uid, packageName,
                    component.getPackageName(), registered,
                    "android.permission.BIND_INPUT_METHOD".equals(service.permission));
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException error) {
            return false;
        }
    }

    private final class Registration implements IBinder.DeathRecipient {
        final int uid;
        final String packageName;
        final IBinder token;
        final AtomicBoolean readLogged = new AtomicBoolean();

        Registration(int uid, String packageName, IBinder token) {
            this.uid = uid;
            this.packageName = packageName;
            this.token = token;
        }

        @Override
        public void binderDied() {
            readers.remove(uid, this);
        }
    }
}
