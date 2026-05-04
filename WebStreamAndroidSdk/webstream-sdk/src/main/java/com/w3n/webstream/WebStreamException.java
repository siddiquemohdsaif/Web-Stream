package com.w3n.webstream;

/**
 * SDK error type used for configuration, transport, server, and media failures.
 */
public final class WebStreamException extends Exception {
    public enum Code {
        INVALID_CONFIG,
        PERMISSION_MISSING,
        TRANSPORT_FAILED,
        SERVER_REJECTED,
        CALL_FULL,
        MEDIA_CAPTURE_FAILED,
        MEDIA_RENDER_FAILED,
        UNKNOWN
    }

    private final Code code;

    public WebStreamException(Code code, String message) {
        super(message);
        this.code = code == null ? Code.UNKNOWN : code;
    }

    public WebStreamException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? Code.UNKNOWN : code;
    }

    public Code getCode() {
        return code;
    }
}
