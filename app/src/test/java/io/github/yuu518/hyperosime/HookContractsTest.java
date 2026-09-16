package io.github.yuu518.hyperosime;

import org.junit.Test;

import static org.junit.Assert.*;

public class HookContractsTest {
    static class Provider {
        public boolean f() { return false; }
        public boolean onCreate() { return true; }
        public static boolean unrelated() { return true; }
        public boolean argument(String value) { return true; }
    }

    static class ChangedProvider {
        public boolean first() { return false; }
        public boolean second() { return false; }
    }

    static class MutableSupport {
        static int sIsImeSupport;
    }

    static class FinalSupport {
        static final int sIsImeSupport = 0;
    }

    static class InstanceSupport {
        int sIsImeSupport;
    }

    static class WrongSupportType {
        static boolean sIsImeSupport;
    }

    @Test
    public void permissionCheckIgnoresStaticAndArgumentMethods() throws Exception {
        assertEquals("f", HookContracts.providerCheck(Provider.class).getName());
    }

    @Test(expected = NoSuchMethodException.class)
    public void ambiguousProviderFailsClosed() throws Exception {
        HookContracts.providerCheck(ChangedProvider.class);
    }

    @Test(expected = NoSuchMethodException.class)
    public void changedMethodReturnTypeIsRejected() throws Exception {
        HookContracts.method(Provider.class, "f", int.class);
    }

    @Test
    public void supportPreflightRequiresMutableStaticInteger() throws Exception {
        assertEquals(int.class, HookContracts.supportField(MutableSupport.class).getType());
        assertThrows(NoSuchFieldException.class, () -> HookContracts.supportField(FinalSupport.class));
        assertThrows(NoSuchFieldException.class, () -> HookContracts.supportField(InstanceSupport.class));
        assertThrows(NoSuchFieldException.class, () -> HookContracts.supportField(WrongSupportType.class));
    }
}
