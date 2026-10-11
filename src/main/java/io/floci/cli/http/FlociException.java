package io.floci.cli.http;

public class FlociException extends Exception {

    private final int status;

    public FlociException(String message) {
        this(message, 0);
    }

    /** An error the server answered with HTTP {@code status}. */
    public FlociException(String message, int status) {
        super(message);
        this.status = status;
    }

    /** The HTTP status the server answered with, or 0 when the failure was not an HTTP response. */
    public int status() {
        return status;
    }

    /**
     * The server has no such endpoint (404) or does not implement it (501). Asked of the status,
     * never of the message: the message carries the request path, and a path can contain "404".
     */
    public boolean endpointMissing() {
        return status == 404 || status == 501;
    }
}
