package net.talaatharb.expanse.domain;

import lombok.Getter;

@Getter
public final class DesignException extends RuntimeException {
    private final String code;
    private final Long currentRevision;

    public DesignException(String code, String message) {
        this(code, message, null);
    }

    public DesignException(String code, String message, Long currentRevision) {
        super(message);
        this.code = code;
        this.currentRevision = currentRevision;
    }
}
