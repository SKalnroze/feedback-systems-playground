package dev.fsp.engine.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fsp.engine.expr.EvalContext.MemoryAggregateQuery;
import dev.fsp.engine.expr.EvalContext.MemoryAggregateQuery.Aggregate;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExpressionTest {

    private static final Offset<Double> PRECISE = Offset.offset(1e-9);

    @Nested
    @DisplayName("numeric expressions")
    class Numeric {

        @Test
        void readsConstantsAndVariables() {
            EvalContext context = MapEvalContext.create().with(Scope.SELF, "trust", 0.4)
                    .with(Scope.TARGET, "trust", 0.9).with(Scope.GLOBAL, "tension", 0.2);

            assertThat(NumExpr.of(3.5).eval(context)).isCloseTo(3.5, PRECISE);
            assertThat(NumExpr.self("trust").eval(context)).isCloseTo(0.4, PRECISE);
            assertThat(NumExpr.target("trust").eval(context)).isCloseTo(0.9, PRECISE);
            assertThat(NumExpr.global("tension").eval(context)).isCloseTo(0.2, PRECISE);
        }

        @Test
        void readsTheCurrentTick() {
            assertThat(new NumExpr.Tick().eval(MapEvalContext.create().at(42L))).isCloseTo(42.0, PRECISE);
        }

        @Test
        void failsLoudlyOnUnboundVariables() {
            EvalContext context = MapEvalContext.create();

            assertThatThrownBy(() -> NumExpr.self("missing").eval(context)).isInstanceOf(EvalException.class)
                    .hasMessageContaining("SELF.missing");
        }

        @Test
        void rejectsBlankVariableNames() {
            assertThatThrownBy(() -> new NumExpr.Var(Scope.SELF, " ")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void evaluatesArithmetic() {
            EvalContext context = MapEvalContext.create();

            assertThat(arith(NumExpr.Arithmetic.Op.ADD, 2, 3).eval(context)).isCloseTo(5.0, PRECISE);
            assertThat(arith(NumExpr.Arithmetic.Op.SUBTRACT, 2, 3).eval(context)).isCloseTo(-1.0, PRECISE);
            assertThat(arith(NumExpr.Arithmetic.Op.MULTIPLY, 2, 3).eval(context)).isCloseTo(6.0, PRECISE);
            assertThat(arith(NumExpr.Arithmetic.Op.DIVIDE, 6, 3).eval(context)).isCloseTo(2.0, PRECISE);
            assertThat(arith(NumExpr.Arithmetic.Op.MIN, 2, 3).eval(context)).isCloseTo(2.0, PRECISE);
            assertThat(arith(NumExpr.Arithmetic.Op.MAX, 2, 3).eval(context)).isCloseTo(3.0, PRECISE);
            assertThat(arith(NumExpr.Arithmetic.Op.POWER, 2, 3).eval(context)).isCloseTo(8.0, PRECISE);
        }

        @Test
        void divisionByZeroYieldsZeroInsteadOfInfinity() {
            assertThat(arith(NumExpr.Arithmetic.Op.DIVIDE, 1, 0).eval(MapEvalContext.create())).isZero();
        }

        @Test
        void evaluatesUnaryTransforms() {
            EvalContext context = MapEvalContext.create();

            assertThat(unary(NumExpr.Unary.Op.NEGATE, 3).eval(context)).isCloseTo(-3.0, PRECISE);
            assertThat(unary(NumExpr.Unary.Op.ABS, -3).eval(context)).isCloseTo(3.0, PRECISE);
            assertThat(unary(NumExpr.Unary.Op.SQRT, 9).eval(context)).isCloseTo(3.0, PRECISE);
            assertThat(unary(NumExpr.Unary.Op.LN, Math.E).eval(context)).isCloseTo(1.0, PRECISE);
            assertThat(unary(NumExpr.Unary.Op.EXP, 0).eval(context)).isCloseTo(1.0, PRECISE);
            assertThat(unary(NumExpr.Unary.Op.SIGMOID, 0).eval(context)).isCloseTo(0.5, PRECISE);
            assertThat(unary(NumExpr.Unary.Op.TANH, 0).eval(context)).isCloseTo(0.0, PRECISE);
            assertThat(unary(NumExpr.Unary.Op.SIGN, -7).eval(context)).isCloseTo(-1.0, PRECISE);
        }

        @Test
        void guardsUndefinedDomainsForSqrtAndLog() {
            EvalContext context = MapEvalContext.create();

            assertThat(unary(NumExpr.Unary.Op.SQRT, -1).eval(context)).isZero();
            assertThat(unary(NumExpr.Unary.Op.LN, 0).eval(context)).isZero();
        }

        @Test
        void clampsToBounds() {
            EvalContext context = MapEvalContext.create();

            assertThat(new NumExpr.Clamp(NumExpr.of(5.0), 0.0, 1.0).eval(context)).isCloseTo(1.0, PRECISE);
            assertThat(new NumExpr.Clamp(NumExpr.of(-5.0), 0.0, 1.0).eval(context)).isCloseTo(0.0, PRECISE);
            assertThatThrownBy(() -> new NumExpr.Clamp(NumExpr.of(0.0), 1.0, 0.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void branchesOnAPredicate() {
            EvalContext context = MapEvalContext.create().with(Scope.SELF, "trust", 0.8);
            NumExpr expression = new NumExpr.Conditional(
                    new Predicate.Compare(Predicate.Compare.Op.GT, NumExpr.self("trust"), NumExpr.of(0.5)),
                    NumExpr.of(1.0), NumExpr.of(-1.0));

            assertThat(expression.eval(context)).isCloseTo(1.0, PRECISE);
        }

        @Test
        void sumsAnyNumberOfTerms() {
            EvalContext context = MapEvalContext.create();

            assertThat(new NumExpr.Sum(List.of(NumExpr.of(1), NumExpr.of(2), NumExpr.of(3))).eval(context))
                    .isCloseTo(6.0, PRECISE);
            assertThat(new NumExpr.Sum(List.of()).eval(context)).isZero();
        }

        @Test
        void readsMemoryAggregates() {
            EvalContext context = MapEvalContext.create().aggregating(Aggregate.MEAN_STRENGTH, 0.62);
            NumExpr expression = new NumExpr.MemoryAggregate(
                    new MemoryAggregateQuery(Aggregate.MEAN_STRENGTH, "interaction", true, 0.1));

            assertThat(expression.eval(context)).isCloseTo(0.62, PRECISE);
        }

        @Test
        void everyNodeReportsAKind() {
            List<NumExpr> nodes = List.of(NumExpr.of(1), NumExpr.self("a"), new NumExpr.Tick(),
                    new NumExpr.MemoryAggregate(new MemoryAggregateQuery(Aggregate.COUNT, null, false, 0.0)),
                    arith(NumExpr.Arithmetic.Op.ADD, 1, 1), unary(NumExpr.Unary.Op.ABS, 1),
                    new NumExpr.Clamp(NumExpr.of(0), 0, 1),
                    new NumExpr.Conditional(Predicate.always(), NumExpr.of(0), NumExpr.of(1)),
                    new NumExpr.Sum(List.of()));

            assertThat(nodes.stream().map(NumExpr::kind).distinct().count()).isEqualTo(nodes.size());
        }

        private NumExpr arith(NumExpr.Arithmetic.Op op, double a, double b) {
            return new NumExpr.Arithmetic(op, NumExpr.of(a), NumExpr.of(b));
        }

        private NumExpr unary(NumExpr.Unary.Op op, double a) {
            return new NumExpr.Unary(op, NumExpr.of(a));
        }
    }

    @Nested
    @DisplayName("predicates")
    class Predicates {

        @Test
        void comparesNumbers() {
            EvalContext context = MapEvalContext.create();

            assertThat(compare(Predicate.Compare.Op.LT, 1, 2).test(context)).isTrue();
            assertThat(compare(Predicate.Compare.Op.LTE, 2, 2).test(context)).isTrue();
            assertThat(compare(Predicate.Compare.Op.GT, 1, 2).test(context)).isFalse();
            assertThat(compare(Predicate.Compare.Op.GTE, 2, 2).test(context)).isTrue();
            assertThat(compare(Predicate.Compare.Op.EQ, 2, 2).test(context)).isTrue();
            assertThat(compare(Predicate.Compare.Op.NEQ, 2, 2).test(context)).isFalse();
        }

        @Test
        void equalityToleratesFloatingPointNoise() {
            EvalContext context = MapEvalContext.create();
            NumExpr accumulated = new NumExpr.Sum(List.of(NumExpr.of(0.1), NumExpr.of(0.2)));

            assertThat(new Predicate.Compare(Predicate.Compare.Op.EQ, accumulated, NumExpr.of(0.3)).test(context))
                    .isTrue();
        }

        @Test
        void combinesWithBooleanOperators() {
            EvalContext context = MapEvalContext.create();
            Predicate yes = Predicate.always();
            Predicate no = Predicate.never();

            assertThat(new Predicate.And(List.of(yes, yes)).test(context)).isTrue();
            assertThat(new Predicate.And(List.of(yes, no)).test(context)).isFalse();
            assertThat(new Predicate.Or(List.of(no, yes)).test(context)).isTrue();
            assertThat(new Predicate.Or(List.of(no, no)).test(context)).isFalse();
            assertThat(new Predicate.Not(no).test(context)).isTrue();
        }

        @Test
        void emptyConjunctionIsTrueAndEmptyDisjunctionIsFalse() {
            EvalContext context = MapEvalContext.create();

            assertThat(new Predicate.And(List.of()).test(context)).isTrue();
            assertThat(new Predicate.Or(List.of()).test(context)).isFalse();
        }

        @Test
        void shortCircuitsWithoutEvaluatingUnboundOperands() {
            EvalContext context = MapEvalContext.create();
            Predicate explodes = new Predicate.Compare(Predicate.Compare.Op.GT, NumExpr.self("missing"),
                    NumExpr.of(0.0));

            assertThat(new Predicate.And(List.of(Predicate.never(), explodes)).test(context)).isFalse();
            assertThat(new Predicate.Or(List.of(Predicate.always(), explodes)).test(context)).isTrue();
        }

        @Test
        void testsTagsAndTypes() {
            EvalContext context = MapEvalContext.create().tagged(Scope.TARGET, "newcomer", "remote")
                    .typed(Scope.TARGET, "person");

            assertThat(new Predicate.HasTag(Scope.TARGET, "newcomer").test(context)).isTrue();
            assertThat(new Predicate.HasTag(Scope.TARGET, "manager").test(context)).isFalse();
            assertThat(new Predicate.HasTag(Scope.SELF, "newcomer").test(context)).isFalse();
            assertThat(new Predicate.IsType(Scope.TARGET, "person").test(context)).isTrue();
            assertThat(new Predicate.IsType(Scope.TARGET, "team").test(context)).isFalse();
            assertThat(new Predicate.IsType(Scope.GLOBAL, "person").test(context)).isFalse();
        }

        @Test
        void everyNodeReportsAKind() {
            List<Predicate> nodes = List.of(Predicate.always(), compare(Predicate.Compare.Op.EQ, 1, 1),
                    new Predicate.And(List.of()), new Predicate.Or(List.of()), new Predicate.Not(Predicate.always()),
                    new Predicate.HasTag(Scope.SELF, "x"), new Predicate.IsType(Scope.SELF, "person"));

            assertThat(nodes.stream().map(Predicate::kind).distinct().count()).isEqualTo(nodes.size());
        }

        private Predicate compare(Predicate.Compare.Op op, double a, double b) {
            return new Predicate.Compare(op, NumExpr.of(a), NumExpr.of(b));
        }
    }
}
