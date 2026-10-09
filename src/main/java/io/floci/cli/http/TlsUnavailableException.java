package io.floci.cli.http;

/**
 * The server answered {@code /tls-cert} with 404: either TLS is off, or it is on and the
 * certificate is still being generated ({@link #notYet()}), which is worth waiting for.
 */
public class TlsUnavailableException extends FlociException {

    private final boolean notYet;

    public TlsUnavailableException(String message, boolean notYet) {
        super(message);
        this.notYet = notYet;
    }

    public boolean notYet() {
        return notYet;
    }
}
