package dev.fsp.engine;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rules that keep the engine a plain library.
 *
 * <p>The simulation core has to stay runnable from a unit test, a batch replay or a future
 * command-line tool without dragging a web framework in behind it. That property is easy to lose by
 * accident and hard to recover, so it is asserted rather than merely intended.
 */
class ArchitectureTest {

    private static JavaClasses engineClasses;

    @BeforeAll
    static void importClasses() {
        engineClasses = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.fsp.engine");
    }

    @Test
    @DisplayName("the engine does not depend on Spring")
    void noSpringInTheEngine() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                .because("the engine must stay runnable without an application context").check(engineClasses);
    }

    @Test
    @DisplayName("the engine does not depend on Jakarta EE or persistence")
    void noJakartaInTheEngine() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("jakarta..", "javax.persistence..", "java.sql..")
                .because("persistence belongs to the application layer, not the simulation core")
                .check(engineClasses);
    }

    @Test
    @DisplayName("the engine does not depend on a serialisation library")
    void noSerialisationLibraryInTheEngine() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("com.fasterxml..", "tools.jackson..", "com.google.gson..")
                .because("how state is written down is the host application's choice").check(engineClasses);
    }

    @Test
    @DisplayName("the engine does not read the clock or the filesystem")
    void noAmbientIoInTheEngine() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("java.io..", "java.nio.file..")
                .because("a simulation that touches the filesystem cannot be replayed deterministically")
                .check(engineClasses);
    }

    @Test
    @DisplayName("no unseeded random number generators")
    void noUnseededRandomGenerators() {
        noClasses().should().dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Random")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.util.concurrent.ThreadLocalRandom")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.SecureRandom")
                .because("every draw must be reproducible from the run seed").check(engineClasses);
    }

    @Test
    @DisplayName("nothing calls Math.random")
    void noMathRandom() {
        noClasses().should().callMethod(Math.class, "random")
                .because("Math.random is unseeded and would break replay").check(engineClasses);
    }

    @Test
    @DisplayName("the engine does not read the wall clock")
    void noWallClock() {
        noClasses().should().callMethod(System.class, "currentTimeMillis").orShould()
                .callMethod(System.class, "nanoTime").orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.time.Instant")
                .because("simulated time is the tick counter; wall-clock time would vary between replays")
                .check(engineClasses);
    }

    @Test
    @DisplayName("expression evaluation stays inside the expression package")
    void expressionsAreSelfContained() {
        classes().that().resideInAPackage("dev.fsp.engine.expr..").should().onlyDependOnClassesThat()
                .resideInAnyPackage("dev.fsp.engine.expr..", "java..")
                .because("authored expressions must not be able to reach simulation internals")
                .check(engineClasses);
    }
}
