package io.github.yuu518.hyperosime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class HookContracts {
    static Method method(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
            throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, parameters);
        if (method.getReturnType() != result) {
            throw new NoSuchMethodException(owner.getName() + "#" + name + " return type");
        }
        method.setAccessible(true);
        return method;
    }

    static Method providerCheck(Class<?> owner) throws NoSuchMethodException {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    && !method.getName().equals("onCreate")
                    && method.getReturnType() == boolean.class && method.getParameterCount() == 0) {
                if (found != null) {
                    throw new NoSuchMethodException("Ambiguous provider permission check");
                }
                found = method;
            }
        }
        if (found == null) {
            throw new NoSuchMethodException("Provider permission check not found");
        }
        found.setAccessible(true);
        return found;
    }

    static Object field(Class<?> owner, String name, Object instance)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    static void setSupport(Class<?> owner) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField("sIsImeSupport");
        field.setAccessible(true);
        if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers())) {
            throw new NoSuchFieldException("Unexpected sIsImeSupport field");
        }
        field.setInt(null, 1);
    }
}
