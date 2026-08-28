package dev.fsp.engine.expr;

/** Thrown when an expression refers to something the context cannot resolve. */
public class EvalException extends RuntimeException {

    public EvalException(String message) {
        super(message);
    }
}
