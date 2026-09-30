package io.github.fludakit.tx.it.service;

public class CustomUncheckedException extends RuntimeException {
    public CustomUncheckedException() {
        super("custom unchecked");
    }
}
