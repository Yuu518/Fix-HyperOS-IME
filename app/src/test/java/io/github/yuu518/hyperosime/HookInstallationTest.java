package io.github.yuu518.hyperosime;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class HookInstallationTest {
    static class Manager {
        static int sIsImeSupport;
    }

    private final Set<Class<?>> installed = new HashSet<>();
    private final List<String> hooks = new ArrayList<>();

    @Before
    public void resetSupport() {
        Manager.sIsImeSupport = -1;
    }

    private void addHook(HookInstallation installation, String name) {
        hooks.add(name);
        installation.onRollback(() -> hooks.remove(name));
    }

    @Test
    public void failedInstallationRestoresHooksAndStaticStateBeforeRetry() throws Exception {
        assertThrows(NoSuchMethodException.class, () ->
                HookInstallation.installOnce(installed, Manager.class, installation -> {
                    addHook(installation, "support");
                    installation.setSupport(HookContracts.supportField(Manager.class));
                    addHook(installation, "buttons");
                    assertFalse(installed.contains(Manager.class));
                    throw new NoSuchMethodException("switcher");
                }));
        assertTrue(hooks.isEmpty());
        assertEquals(-1, Manager.sIsImeSupport);
        assertFalse(installed.contains(Manager.class));

        assertTrue(HookInstallation.installOnce(installed, Manager.class, installation -> {
            installation.setSupport(HookContracts.supportField(Manager.class));
            addHook(installation, "support");
            addHook(installation, "buttons");
            addHook(installation, "switcher");
        }));
        assertEquals(List.of("support", "buttons", "switcher"), hooks);
        assertEquals(1, Manager.sIsImeSupport);
        assertTrue(installed.contains(Manager.class));
    }

    @Test
    public void installedManagerIsNotInstalledTwice() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HookInstallation.Installer installer = installation -> {
            attempts.incrementAndGet();
            addHook(installation, "switcher");
        };
        assertTrue(HookInstallation.installOnce(installed, Manager.class, installer));
        assertFalse(HookInstallation.installOnce(installed, Manager.class, installer));
        assertEquals(1, attempts.get());
        assertEquals(List.of("switcher"), hooks);
    }

    @Test
    public void failedPreflightDoesNotPublishInstallation() {
        assertThrows(NoSuchMethodException.class, () ->
                HookInstallation.installOnce(installed, Manager.class, installation -> {
                    HookContracts.method(Manager.class, "missing", void.class);
                    addHook(installation, "unreachable");
                }));
        assertTrue(hooks.isEmpty());
        assertTrue(installed.isEmpty());
        assertEquals(-1, Manager.sIsImeSupport);
    }

    @Test
    public void frameworkErrorsAlsoRollBack() {
        Error failure = new AssertionError("hook failed");
        assertSame(failure, assertThrows(Error.class, () ->
                HookInstallation.installOnce(installed, Manager.class, installation -> {
                    installation.setSupport(HookContracts.supportField(Manager.class));
                    addHook(installation, "support");
                    throw failure;
                })));
        assertTrue(hooks.isEmpty());
        assertTrue(installed.isEmpty());
        assertEquals(-1, Manager.sIsImeSupport);
    }

    @Test
    public void rollbackKeepsPreviouslyCommittedHooks() throws Exception {
        try (HookInstallation previous = new HookInstallation()) {
            addHook(previous, "existing");
            previous.commit();
        }
        assertThrows(IllegalStateException.class, () -> {
            try (HookInstallation installation = new HookInstallation()) {
                addHook(installation, "new");
                throw new IllegalStateException("later hook failed");
            }
        });
        assertEquals(List.of("existing"), hooks);
    }

    @Test
    public void cleanupContinuesInReverseOrderAndPreservesOriginalFailure() {
        List<Integer> order = new ArrayList<>();
        RuntimeException original = new IllegalStateException("install");
        RuntimeException cleanup = new IllegalStateException("unhook");
        assertSame(original, assertThrows(RuntimeException.class, () -> {
            try (HookInstallation installation = new HookInstallation()) {
                installation.setSupport(HookContracts.supportField(Manager.class));
                installation.onRollback(() -> order.add(1));
                installation.onRollback(() -> {
                    order.add(2);
                    throw cleanup;
                });
                installation.onRollback(() -> order.add(3));
                throw original;
            }
        }));
        assertEquals(List.of(3, 2, 1), order);
        assertEquals(-1, Manager.sIsImeSupport);
        assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
    }

    @Test
    public void closingTwiceDoesNotUndoTwice() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HookInstallation installation = new HookInstallation();
        installation.onRollback(calls::incrementAndGet);
        installation.close();
        installation.close();
        assertEquals(1, calls.get());
    }
}
