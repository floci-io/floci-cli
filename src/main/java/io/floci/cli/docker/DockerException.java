package io.floci.cli.docker;

public class DockerException extends Exception {

    private final boolean advised;

    /** An error in this CLI's own words; a second line in it is its next step. */
    public DockerException(String message) {
        this(message, message != null && message.strip().contains("\n"));
    }

    private DockerException(String message, boolean advised) {
        super(message);
        this.advised = advised;
    }

    /**
     * An error that is docker's own output. However many lines it has, none of them is advice
     * from this CLI, so it never counts as already saying what to do next.
     */
    public static DockerException ofOutput(String output) {
        return new DockerException(output == null ? "" : output.strip(), false);
    }

    /** The message followed by {@code nextStep}, unless the message already ends with its own. */
    public String withNextStep(String nextStep) {
        String message = getMessage() == null ? "" : getMessage().strip();
        return advised ? message : message + "\n" + nextStep;
    }
}
