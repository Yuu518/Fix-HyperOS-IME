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

    static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static Object field(Class<?> owner, String name, Object instance)
            throws ReflectiveOperationException {
        return field(owner, name).get(instance);
    }

    static Field staticField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = field(owner, name);
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new NoSuchFieldException("Expected static field: " + owner.getName() + "#" + name);
        }
        return field;
    }

    static Field supportField(Class<?> owner) throws NoSuchFieldException {
        Field field = staticField(owner, "sIsImeSupport");
        if (field.getType() != int.class || Modifier.isFinal(field.getModifiers())) {
            throw new NoSuchFieldException("Unexpected sIsImeSupport field");
        }
        return field;
    }
}
