package io.github.yuu518.hyperosime;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class HookInstallation implements AutoCloseable {
    interface Undo {
        void run() throws ReflectiveOperationException;
    }

    interface Installer {
        void install(HookInstallation installation) throws ReflectiveOperationException;
    }

    private final List<Undo> undo = new ArrayList<>();

    static boolean installOnce(Set<Class<?>> installed, Class<?> manager, Installer installer)
            throws ReflectiveOperationException {
        if (installed.contains(manager)) {
            return false;
        }
        try (HookInstallation installation = new HookInstallation()) {
            installer.install(installation);
            installed.add(manager);
            installation.commit();
        }
        return true;
    }

    void onRollback(Undo action) {
        undo.add(action);
    }

    void setSupport(Field field) throws IllegalAccessException {
        int original = field.getInt(null);
        onRollback(() -> field.setInt(null, original));
        field.setInt(null, 1);
    }

    void commit() {
        undo.clear();
    }

    @Override
    public void close() throws ReflectiveOperationException {
        Throwable failure = null;
        // A failed unhook must not prevent the remaining hooks and fields from being restored.
        for (int i = undo.size() - 1; i >= 0; i--) {
            try {
                undo.get(i).run();
            } catch (ReflectiveOperationException | RuntimeException | Error error) {
                if (failure == null) {
                    failure = error;
                } else if (failure != error) {
                    failure.addSuppressed(error);
                }
            }
        }
        undo.clear();
        if (failure instanceof ReflectiveOperationException reflective) {
            throw reflective;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error fatal) {
            throw fatal;
        }
    }
}
