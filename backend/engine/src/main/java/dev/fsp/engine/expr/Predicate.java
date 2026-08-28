package dev.fsp.engine.expr;

import java.util.List;

/**
 * Boolean condition tree, sharing {@link NumExpr} for its numeric operands.
 *
 * <p>Used for event target selection, trigger conditions, interaction gating and conditional
 * effects, so that one authored form covers every "only when..." in the system.
 */
public sealed interface Predicate {

    String kind();

    boolean test(EvalContext context);

    static Predicate always() {
        return new Constant(true);
    }

    static Predicate never() {
        return new Constant(false);
    }

    /** Unconditional true or false; the default when a spec omits a condition. */
    record Constant(boolean value) implements Predicate {

        @Override
        public String kind() {
            return "always";
        }

        @Override
        public boolean test(EvalContext context) {
            return value;
        }
    }

    /** Numeric comparison. */
    record Compare(Op op, NumExpr left, NumExpr right) implements Predicate {

        public enum Op {
            LT,
            LTE,
            GT,
            GTE,
            EQ,
            NEQ
        }

        /** Comparison tolerance for the equality operators, since operands are doubles. */
        private static final double EPSILON = 1e-9;

        @Override
        public String kind() {
            return "compare";
        }

        @Override
        public boolean test(EvalContext context) {
            double a = left.eval(context);
            double b = right.eval(context);
            return switch (op) {
                case LT -> a < b;
                case LTE -> a <= b;
                case GT -> a > b;
                case GTE -> a >= b;
                case EQ -> Math.abs(a - b) <= EPSILON;
                case NEQ -> Math.abs(a - b) > EPSILON;
            };
        }
    }

    /** True when every operand is true; an empty list is true. */
    record And(List<Predicate> operands) implements Predicate {

        public And {
            operands = List.copyOf(operands);
        }

        @Override
        public String kind() {
            return "and";
        }

        @Override
        public boolean test(EvalContext context) {
            for (Predicate operand : operands) {
                if (!operand.test(context)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** True when any operand is true; an empty list is false. */
    record Or(List<Predicate> operands) implements Predicate {

        public Or {
            operands = List.copyOf(operands);
        }

        @Override
        public String kind() {
            return "or";
        }

        @Override
        public boolean test(EvalContext context) {
            for (Predicate operand : operands) {
                if (operand.test(context)) {
                    return true;
                }
            }
            return false;
        }
    }

    record Not(Predicate operand) implements Predicate {

        @Override
        public String kind() {
            return "not";
        }

        @Override
        public boolean test(EvalContext context) {
            return !operand.test(context);
        }
    }

    /** Tag membership, the cheap way to carve out sub-populations without extra variables. */
    record HasTag(Scope scope, String tag) implements Predicate {

        @Override
        public String kind() {
            return "hasTag";
        }

        @Override
        public boolean test(EvalContext context) {
            return context.tags(scope).contains(tag);
        }
    }

    /** Object-type test, e.g. only apply this event to objects of type {@code person}. */
    record IsType(Scope scope, String typeId) implements Predicate {

        @Override
        public String kind() {
            return "isType";
        }

        @Override
        public boolean test(EvalContext context) {
            return typeId.equals(context.typeId(scope));
        }
    }
}
