package io.github.fludakit.tx.it.service;

public class CustomCheckedException extends Exception {
    public CustomCheckedException() {
        super("custom checked");
    }
}
