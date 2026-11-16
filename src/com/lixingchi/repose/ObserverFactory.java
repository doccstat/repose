package com.lixingchi.repose;

import java.lang.reflect.*;
import java.util.function.Consumer;

final class ObserverFactory {
    static Object create(Class<?> type, Consumer<String> failure) throws ReflectiveOperationException {
        Class<?> parent = type.getSuperclass();
        try { parent.getDeclaredConstructor(); }
        catch (NoSuchMethodException erased) {
            // R8 can erase an empty superclass constructor and inline Object.<init> into its children.
            if (parent.getDeclaredConstructors().length != 0) throw erased;
            for (Class<?> base = parent; base != Object.class; base = base.getSuperclass())
                for (Field field : base.getDeclaredFields())
                    if (!Modifier.isStatic(field.getModifiers())) throw erased;
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            for (Field field : unsafeType.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != unsafeType) continue;
                field.setAccessible(true);
                Object unsafe = field.get(null);
                if (unsafe == null) continue;
                Object observer = unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
                type.getMethod("initialize", Consumer.class).invoke(observer, failure);
                return observer;
            }
            throw new NoSuchFieldException("observer_allocator_unavailable");
        }
        return type.getConstructor(Consumer.class).newInstance(failure);
    }
}
