package io.github.ashleythew.stitch.core;

/** A stitch that can't be used, with the reason, named so a pack author can find it. */
public final class StitchException extends RuntimeException {

    private final String stitch;

    public StitchException(String stitch, String message) {
        super(stitch + ": " + message);
        this.stitch = stitch;
    }

    /** The stitch's id. */
    public String stitch() {
        return stitch;
    }
}
