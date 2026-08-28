package dev.fsp.engine.expr;

import java.util.List;

/**
 * Numeric expression tree.
 *
 * <p>Deliberately a structured AST rather than a text language: the visual editor builds these
 * nodes directly, they serialise to JSON one-to-one, and there is no parser and no eval of
 * user-supplied source anywhere in the engine.
 */
public sealed interface NumExpr {

    String kind();

    double eval(EvalContext context);

    static NumExpr of(double value) {
        return new Constant(value);
    }

    static NumExpr self(String variable) {
        return new Var(Scope.SELF, variable);
    }

    static NumExpr target(String variable) {
        return new Var(Scope.TARGET, variable);
    }

    static NumExpr global(String variable) {
        return new Var(Scope.GLOBAL, variable);
    }

    /** A literal number. */
    record Constant(double value) implements NumExpr {

        @Override
        public String kind() {
            return "const";
        }

        @Override
        public double eval(EvalContext context) {
            return value;
        }
    }

    /** The current value of a variable in some scope. */
    record Var(Scope scope, String name) implements NumExpr {

        public Var {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("variable name must not be blank");
            }
        }

        @Override
        public String kind() {
            return "var";
        }

        @Override
        public double eval(EvalContext context) {
            return context.variable(scope, name);
        }
    }

    /** The current tick, for age- or schedule-dependent expressions. */
    record Tick() implements NumExpr {

        @Override
        public String kind() {
            return "tick";
        }

        @Override
        public double eval(EvalContext context) {
            return context.tick();
        }
    }

    /** A statistic over the evaluating object's memories, e.g. "mean strength of grudges about X". */
    record MemoryAggregate(EvalContext.MemoryAggregateQuery query) implements NumExpr {

        @Override
        public String kind() {
            return "memory";
        }

        @Override
        public double eval(EvalContext context) {
            return context.memoryAggregate(query);
        }
    }

    /** Binary arithmetic. Division by zero yields zero rather than an infinity, to keep runs finite. */
    record Arithmetic(Op op, NumExpr left, NumExpr right) implements NumExpr {

        public enum Op {
            ADD,
            SUBTRACT,
            MULTIPLY,
            DIVIDE,
            MIN,
            MAX,
            POWER
        }

        @Override
        public String kind() {
            return "arith";
        }

        @Override
        public double eval(EvalContext context) {
            double a = left.eval(context);
            double b = right.eval(context);
            return switch (op) {
                case ADD -> a + b;
                case SUBTRACT -> a - b;
                case MULTIPLY -> a * b;
                case DIVIDE -> b == 0.0 ? 0.0 : a / b;
                case MIN -> Math.min(a, b);
                case MAX -> Math.max(a, b);
                case POWER -> Math.pow(a, b);
            };
        }
    }

    /** Unary transforms, including the saturating shapes used for soft limits on feedback. */
    record Unary(Op op, NumExpr operand) implements NumExpr {

        public enum Op {
            NEGATE,
            ABS,
            SQRT,
            LN,
            EXP,
            SIGMOID,
            TANH,
            SIGN
        }

        @Override
        public String kind() {
            return "unary";
        }

        @Override
        public double eval(EvalContext context) {
            double x = operand.eval(context);
            return switch (op) {
                case NEGATE -> -x;
                case ABS -> Math.abs(x);
                case SQRT -> x <= 0.0 ? 0.0 : Math.sqrt(x);
                case LN -> x <= 0.0 ? 0.0 : Math.log(x);
                case EXP -> Math.exp(x);
                case SIGMOID -> 1.0 / (1.0 + Math.exp(-x));
                case TANH -> Math.tanh(x);
                case SIGN -> Math.signum(x);
            };
        }
    }

    /** Hard bounds, the usual way of keeping a variable inside its declared range. */
    record Clamp(NumExpr value, double min, double max) implements NumExpr {

        public Clamp {
            if (min > max) {
                throw new IllegalArgumentException("min must not exceed max: " + min + " > " + max);
            }
        }

        @Override
        public String kind() {
            return "clamp";
        }

        @Override
        public double eval(EvalContext context) {
            return Math.clamp(value.eval(context), min, max);
        }
    }

    /** Branch on a predicate. */
    record Conditional(Predicate condition, NumExpr whenTrue, NumExpr whenFalse) implements NumExpr {

        @Override
        public String kind() {
            return "if";
        }

        @Override
        public double eval(EvalContext context) {
            return condition.test(context) ? whenTrue.eval(context) : whenFalse.eval(context);
        }
    }

    /** Sum of a list, kept as its own node so the editor can render n-ary fan-in cleanly. */
    record Sum(List<NumExpr> terms) implements NumExpr {

        public Sum {
            terms = List.copyOf(terms);
        }

        @Override
        public String kind() {
            return "sum";
        }

        @Override
        public double eval(EvalContext context) {
            double total = 0.0;
            for (NumExpr term : terms) {
                total += term.eval(context);
            }
            return total;
        }
    }
}
