package dev.fsp.app.api;

/** Something the caller referred to does not exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String kind, Object id) {
        super("no such " + kind + ": " + id);
    }
}
