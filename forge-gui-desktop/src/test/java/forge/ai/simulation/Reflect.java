package forge.ai.simulation;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reaches a private method or field for a test. A method is found by its name and its number of arguments, a field by
 * its name, in the class named (or the target's class) or else the nearest superclass that declares one. A lookup that
 * finds nothing, or two methods of one name and count in one class, fails the test.
 */
public final class Reflect {
    private Reflect() {
    }

    /** {@code target.method(args)}. What it throws is rethrown: an Error or a RuntimeException as it is, a checked
     *  exception wrapped in a RuntimeException. */
    public static Object call(final Object target, final String method, final Object... args) {
        return invoke(method(target.getClass(), method, args.length), target, args);
    }

    /** The static {@code c.method(args)}; what it throws is rethrown as {@link #call} rethrows it. */
    public static Object callStatic(final Class<?> c, final String method, final Object... args) {
        return invoke(method(c, method, args.length), null, args);
    }

    /** What the static {@code c.method(args)} throws, or null when it returns. */
    public static Throwable thrownBy(final Class<?> c, final String method, final Object... args) {
        try {
            method(c, method, args.length).invoke(null, args);
            return null;
        } catch (final InvocationTargetException e) {
            return e.getCause();
        } catch (final IllegalAccessException e) {
            throw new AssertionError("cannot call " + c.getName() + "." + method + ": " + e, e);
        }
    }

    /** Sets {@code target}'s field {@code field}. */
    public static void set(final Object target, final String field, final Object value) {
        put(field(target.getClass(), field), target, value);
    }

    /** Sets the static field {@code field} of {@code c}. */
    public static void setStatic(final Class<?> c, final String field, final Object value) {
        put(field(c, field), null, value);
    }

    private static Method method(final Class<?> c, final String name, final int arity) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            Method found = null;
            for (final Method m : k.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic() || !m.getName().equals(name) || m.getParameterCount() != arity) {
                    continue;
                }
                if (found != null) {
                    throw new AssertionError(k.getName() + " declares more than one " + name + " taking " + arity
                            + " argument(s)");
                }
                found = m;
            }
            if (found != null) {
                found.setAccessible(true);
                return found;
            }
        }
        throw new AssertionError(c.getName() + " has no method " + name + " taking " + arity + " argument(s)");
    }

    private static Field field(final Class<?> c, final String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (final Field f : k.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new AssertionError(c.getName() + " has no field " + name);
    }

    private static Object invoke(final Method m, final Object target, final Object[] args) {
        try {
            return m.invoke(target, args);
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof Error err) {
                throw err;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (final IllegalAccessException e) {
            throw new AssertionError("cannot call " + m + ": " + e, e);
        }
    }

    private static void put(final Field f, final Object target, final Object value) {
        try {
            f.set(target, value);
        } catch (final IllegalAccessException e) {
            throw new AssertionError("cannot set " + f + ": " + e, e);
        }
    }
}
