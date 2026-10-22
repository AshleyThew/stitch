package io.github.ashleythew.stitch.minecraft;

import io.github.ashleythew.stitch.core.Stitch;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

/**
 * The resource pack readers the Stitch jar carries, one for each way a game names Minecraft's code and
 * lists its resources: minecraft.&lt;variant&gt;.Stitches. Only the running game's is ever loaded, since the
 * others name classes and methods it doesn't have.
 */
public final class Readers {

    private Readers() {
    }

    /** The reader for a variant, read through on every call. */
    @SuppressWarnings("unchecked")
    public static Supplier<List<Stitch>> of(String variant) {
        Method read;
        try {
            read = Class.forName(Readers.class.getPackageName() + "." + variant + ".Stitches").getMethod("read");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Stitch has no resource pack reader " + variant, e);
        }
        return () -> {
            try {
                return (List<Stitch>) read.invoke(null);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException r) {
                    throw r;
                }
                if (e.getCause() instanceof Error r) {
                    throw r;
                }
                throw new IllegalStateException(e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
