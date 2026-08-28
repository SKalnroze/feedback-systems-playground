package dev.fsp.engine.expr;

/** Which object a variable reference or tag test is about, relative to the thing being evaluated. */
public enum Scope {

    /** The object the rule is running for. */
    SELF,

    /** The other party: the interaction partner, event target, or memory subject. */
    TARGET,

    /** System-wide variables, belonging to no object. */
    GLOBAL
}
