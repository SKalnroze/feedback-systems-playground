package dev.fsp.engine.graph;

/**
 * Shape of the relationship carried by a link between two variables.
 *
 * <p>A pure linear gain makes every reinforcing loop explode and every balancing loop oscillate,
 * so the interesting behaviour of a feedback system lives in these non-linearities: saturation,
 * thresholds, diminishing returns.
 */
public sealed interface Transfer {

    String kind();

    /** Maps the (already gain-scaled) input to the contribution applied to the target variable. */
    double apply(double input);

    static Transfer linear() {
        return Linear.INSTANCE;
    }

    /** Pass-through. The contribution is the gain-scaled input itself. */
    record Linear() implements Transfer {

        public static final Linear INSTANCE = new Linear();

        @Override
        public String kind() {
            return "linear";
        }

        @Override
        public double apply(double input) {
            return input;
        }
    }

    /**
     * Logistic S-curve in {@code [0, amplitude]}: slow start, rapid middle, saturating tail.
     * The workhorse for "influence that eventually stops growing".
     */
    record Sigmoid(double steepness, double midpoint, double amplitude) implements Transfer {

        public Sigmoid {
            if (steepness <= 0.0) {
                throw new IllegalArgumentException("steepness must be positive: " + steepness);
            }
        }

        @Override
        public String kind() {
            return "sigmoid";
        }

        @Override
        public double apply(double input) {
            return amplitude / (1.0 + Math.exp(-steepness * (input - midpoint)));
        }
    }

    /** Symmetric saturation around zero, keeping the sign of the input. */
    record Tanh(double scale, double amplitude) implements Transfer {

        public Tanh {
            if (scale <= 0.0) {
                throw new IllegalArgumentException("scale must be positive: " + scale);
            }
        }

        @Override
        public String kind() {
            return "tanh";
        }

        @Override
        public double apply(double input) {
            return amplitude * Math.tanh(input / scale);
        }
    }

    /** Nothing happens until the input crosses a threshold, then a fixed contribution applies. */
    record Threshold(double threshold, double below, double above) implements Transfer {

        @Override
        public String kind() {
            return "threshold";
        }

        @Override
        public double apply(double input) {
            return input >= threshold ? above : below;
        }
    }

    /** Hard clip to a band, leaving the input untouched inside it. */
    record Saturating(double min, double max) implements Transfer {

        public Saturating {
            if (min > max) {
                throw new IllegalArgumentException("min must not exceed max: " + min + " > " + max);
            }
        }

        @Override
        public String kind() {
            return "saturating";
        }

        @Override
        public double apply(double input) {
            return Math.clamp(input, min, max);
        }
    }

    /** Diminishing returns: each additional unit of input contributes less than the last. */
    record Logarithmic(double scale) implements Transfer {

        public Logarithmic {
            if (scale <= 0.0) {
                throw new IllegalArgumentException("scale must be positive: " + scale);
            }
        }

        @Override
        public String kind() {
            return "logarithmic";
        }

        @Override
        public double apply(double input) {
            return Math.signum(input) * scale * Math.log1p(Math.abs(input) / scale);
        }
    }

    /** Raises the input to a power, keeping sign; exponents above one accelerate, below one damp. */
    record PowerCurve(double exponent) implements Transfer {

        public PowerCurve {
            if (exponent <= 0.0) {
                throw new IllegalArgumentException("exponent must be positive: " + exponent);
            }
        }

        @Override
        public String kind() {
            return "power";
        }

        @Override
        public double apply(double input) {
            return Math.signum(input) * Math.pow(Math.abs(input), exponent);
        }
    }
}
