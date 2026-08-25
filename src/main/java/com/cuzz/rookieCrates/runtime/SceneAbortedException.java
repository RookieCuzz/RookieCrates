package com.cuzz.rookieCrates.runtime;

public final class SceneAbortedException extends RuntimeException {
    private final SceneAbortReason reason;

    public SceneAbortedException(SceneAbortReason reason) {
        super("Crate scene aborted: " + reason.name());
        this.reason = reason;
    }

    public SceneAbortReason reason() {
        return reason;
    }
}
