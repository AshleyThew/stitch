package io.github.ashleythew.stitch.optifine;

import java.lang.reflect.Method;

/** The name of OptiFine's Program, which Stitch doesn't compile against. Outside the mixin package, which Mixin keeps to itself. */
public final class OptiFinePrograms {

    private static volatile Method getName;

    private OptiFinePrograms() {
    }

    public static String name(Object program) {
        try {
            Method m = getName;
            if (m == null) {
                getName = m = program.getClass().getMethod("getName");
            }
            return (String) m.invoke(program);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}
