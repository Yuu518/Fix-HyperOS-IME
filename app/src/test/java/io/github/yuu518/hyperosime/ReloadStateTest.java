package io.github.yuu518.hyperosime;

import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class ReloadStateTest {
    @Test
    public void stateCrossesModuleClassLoadersWithoutRetainingOldModuleObjects() throws Exception {
        Class<?> oldType = generation();
        Class<?> newType = generation();
        assertNotSame(oldType, newType);
        Object token = new Object();
        Object service = new Object();
        Object input = new Object();
        Object root = new Object();
        Object bottom = new Object();
        AtomicBoolean alive = new AtomicBoolean(true);
        List<Object[]> sessions = new ArrayList<>();
        sessions.add(new Object[]{service, input, root, bottom, alive});
        List<Object[]> readers = new ArrayList<>();
        readers.add(new Object[]{10279, "com.tencent.wetype", token});
        Constructor<?> constructor = oldType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object oldState = constructor.newInstance("com.tencent.wetype", getClass().getClassLoader(),
                token, List.of(String.class), sessions, readers);
        Method export = oldType.getDeclaredMethod("export");
        export.setAccessible(true);
        Object transferred = export.invoke(oldState);
        assertNeutral(transferred, oldType.getClassLoader());
        sessions.get(0)[0] = null;
        readers.clear();
        Method read = newType.getDeclaredMethod("read", Object.class);
        read.setAccessible(true);
        Object restored = read.invoke(null, transferred);
        Method newExport = newType.getDeclaredMethod("export");
        newExport.setAccessible(true);
        ReloadState result = ReloadState.read(newExport.invoke(restored));
        assertSame(token, result.token);
        assertSame(service, result.sessions.get(0)[0]);
        assertSame(bottom, result.sessions.get(0)[3]);
        alive.set(false);
        assertFalse(((AtomicBoolean) result.sessions.get(0)[4]).get());
        assertSame(token, result.readers.get(0)[2]);
        assertEquals("com.tencent.wetype", result.packageName);
    }

    @Test(expected = IllegalArgumentException.class)
    public void incompatibleStateVersionIsRejected() {
        Map<String, Object> state = emptyState().export();
        state.put("version", 2);
        ReloadState.read(state);
    }

    @Test(expected = IllegalArgumentException.class)
    public void incompleteWindowStateIsRejected() {
        Map<String, Object> state = emptyState().export();
        state.put("sessions", List.of(new Object[]{"service"}, new Object[]{"root"}));
        ReloadState.read(state);
    }

    private ReloadState emptyState() {
        return new ReloadState("com.tencent.wetype", getClass().getClassLoader(), new Object(),
                List.of(), List.of(), List.of());
    }

    private Class<?> generation() throws Exception {
        String name = ReloadState.class.getName();
        byte[] code;
        try (InputStream stream = ReloadState.class.getResourceAsStream("ReloadState.class")) {
            assertNotNull(stream);
            code = stream.readAllBytes();
        }
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (!requested.equals(name)) {
                    return super.loadClass(requested, resolve);
                }
                synchronized (this) {
                    Class<?> type = findLoadedClass(requested);
                    if (type == null) {
                        type = defineClass(requested, code, 0, code.length);
                    }
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                }
            }
        };
        return loader.loadClass(name);
    }

    private void assertNeutral(Object value, ClassLoader oldLoader) {
        assertNotSame(oldLoader, value.getClass().getClassLoader());
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, entry) -> {
                assertNeutral(key, oldLoader);
                assertNeutral(entry, oldLoader);
            });
        } else if (value instanceof List<?> list) {
            list.forEach(entry -> assertNeutral(entry, oldLoader));
        } else if (value instanceof Object[] array) {
            for (Object entry : array) {
                assertNeutral(entry, oldLoader);
            }
        }
    }
}
