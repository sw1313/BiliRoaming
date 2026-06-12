package me.custom.biliextras.sponsorblock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * Host {@code Function0} proxies that return the host app's {@code kotlin.Unit} singleton.
 * Module {@code kotlin.Unit} is R8-renamed (e.g. to {@code b.h}) and must never be returned to Bilibili.
 */
public final class HostFunction0Proxy {
    private static volatile boolean installed;
    private static volatile boolean returnNull;
    private static volatile Object hostUnit;

    private HostFunction0Proxy() {
    }

    public static void installHostUnit(Class<?> fn0Interface) {
        if (installed) {
            return;
        }
        synchronized (HostFunction0Proxy.class) {
            if (installed) {
                return;
            }
            try {
                Method invoke = fn0Interface.getMethod("invoke");
                Class<?> erasedReturn = invoke.getReturnType();
                if (erasedReturn == Void.TYPE) {
                    returnNull = true;
                } else if (erasedReturn != Object.class) {
                    hostUnit = resolveSingleton(erasedReturn);
                } else {
                    // Function0<Unit>.invoke() erases to Object on JVM — load host kotlin.Unit by name.
                    hostUnit = resolveHostUnit(fn0Interface.getClassLoader());
                }
                installed = true;
            } catch (ReflectiveOperationException e) {
                // Last resort: return null from invoke; row creation must not fail.
                returnNull = true;
                installed = true;
            }
        }
    }

    /** Build "kotlin.Unit" without a single constant-pool string (R8 may rewrite that to b.h). */
    private static String kotlinUnitClassName() {
        return new String(new char[]{
                'k', 'o', 't', 'l', 'i', 'n', '.', 'U', 'n', 'i', 't'
        });
    }

    private static Object resolveHostUnit(ClassLoader hostClassLoader)
            throws ReflectiveOperationException {
        Class<?> unitClass = Class.forName(kotlinUnitClassName(), false, hostClassLoader);
        return resolveSingleton(unitClass);
    }

    private static Object resolveSingleton(Class<?> unitClass) throws ReflectiveOperationException {
        try {
            return unitClass.getField("INSTANCE").get(null);
        } catch (NoSuchFieldException ignored) {
            for (Field field : unitClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == unitClass) {
                    field.setAccessible(true);
                    return field.get(null);
                }
            }
            throw new NoSuchFieldException("No singleton on " + unitClass.getName());
        }
    }

    public static Object hostUnitInstance() {
        return returnNull ? null : hostUnit;
    }

    public static Object create(ClassLoader hostClassLoader, Class<?> fn0Interface, Runnable action) {
        installHostUnit(fn0Interface);
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("invoke".equals(name)) {
                    action.run();
                    return hostUnitInstance();
                }
                if ("equals".equals(name)) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(name)) {
                    return "SponsorBlockHostFunction0";
                }
                return null;
            }
        };
        return Proxy.newProxyInstance(hostClassLoader, new Class<?>[]{fn0Interface}, handler);
    }
}
