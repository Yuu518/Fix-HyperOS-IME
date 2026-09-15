package io.github.yuu518.hyperosime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ReloadState {
    final String packageName;
    final ClassLoader loader;
    final Object token;
    final List<Class<?>> managers;
    final List<Object[]> sessions;
    final List<Object[]> readers;

    ReloadState(String packageName, ClassLoader loader, Object token, List<Class<?>> managers,
                List<Object[]> sessions, List<Object[]> readers) {
        this.packageName = packageName;
        this.loader = loader;
        this.token = token;
        this.managers = new ArrayList<>(managers);
        this.sessions = copyRows(sessions, 5);
        this.readers = copyRows(readers, 3);
    }

    Map<String, Object> export() {
        Map<String, Object> data = new HashMap<>();
        data.put("version", 1);
        data.put("package", packageName);
        data.put("loader", loader);
        data.put("token", token);
        data.put("managers", new ArrayList<>(managers));
        data.put("sessions", copyRows(sessions, 5));
        data.put("readers", copyRows(readers, 3));
        return data;
    }

    static ReloadState read(Object value) {
        if (!(value instanceof Map<?, ?> data) || !Integer.valueOf(1).equals(data.get("version"))
                || !(data.get("package") instanceof String name) || name.isEmpty()
                || !(data.get("loader") instanceof ClassLoader loader)
                || data.get("token") == null || !(data.get("managers") instanceof List<?> classes)
                || !(data.get("sessions") instanceof List<?> sessions)
                || !(data.get("readers") instanceof List<?> readers)) {
            throw new IllegalArgumentException("Unsupported hot reload state");
        }
        List<Class<?>> managers = new ArrayList<>();
        for (Object type : classes) {
            if (!(type instanceof Class<?> manager)) {
                throw new IllegalArgumentException("Invalid manager class");
            }
            managers.add(manager);
        }
        return new ReloadState(name, loader, data.get("token"), managers,
                copyRows(sessions, 5), copyRows(readers, 3));
    }

    private static List<Object[]> copyRows(List<?> rows, int width) {
        List<Object[]> result = new ArrayList<>();
        for (Object value : rows) {
            if (!(value instanceof Object[] row) || row.length != width) {
                throw new IllegalArgumentException("Invalid hot reload state row");
            }
            result.add(row.clone());
        }
        return result;
    }
}
