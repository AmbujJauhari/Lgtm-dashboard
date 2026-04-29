package com.collops.provisioner;

public class GrafanaException extends RuntimeException {

    private final int statusCode;

    public GrafanaException(int statusCode, String message) {
        super("HTTP " + statusCode + ": " + message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
