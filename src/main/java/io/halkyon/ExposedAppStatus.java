package io.halkyon;

public class ExposedAppStatus {

    private String host;
    private String message;

    public ExposedAppStatus() {
    }

    public ExposedAppStatus(String host, String message) {
        this.host = host;
        this.message = message;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
