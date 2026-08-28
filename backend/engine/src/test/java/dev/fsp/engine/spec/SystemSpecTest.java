package dev.fsp.engine.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fsp.engine.event.Effect;
import dev.fsp.engine.event.EventGenerator;
import dev.fsp.engine.event.TargetSelector;
import dev.fsp.engine.expr.Scope;
import dev.fsp.engine.graph.Transfer;
import dev.fsp.engine.memory.MemorySettings;
import dev.fsp.engine.memory.MemoryTrigger;
import dev.fsp.engine.memory.MemoryTrigger.MemoryFilter;
import dev.fsp.engine.memory.MemoryTrigger.ReactivationEffect;
import dev.fsp.engine.memory.TriggerCondition;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.spec.SystemSpec.ValidationIssue;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SystemSpecTest {

    private static ObjectTypeSpec person() {
        return new ObjectTypeSpec("person", "Person",
                List.of(VariableSpec.unitStock("trust", 0.5), VariableSpec.constant("openness", 0.4)),
                MemorySettings.DEFAULT, Set.of(), Map.of());
    }

    private static SystemSpec.Builder base() {
        return SystemSpec.builder("sys").objectType(person()).object(ObjectSpec.of("alice", "person"));
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void fillsInDefaults() {
            SystemSpec spec = SystemSpec.builder("sys").build();

            assertThat(spec.name()).isEqualTo("sys");
            assertThat(spec.settings()).isEqualTo(SimulationSettings.DEFAULT);
            assertThat(spec.objects()).isEmpty();
        }

        @Test
        void rejectsABlankId() {
            assertThatThrownBy(() -> SystemSpec.builder(" ").build()).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void addsSeveralObjectsOfOneType() {
            SystemSpec spec = SystemSpec.builder("sys").objectType(person()).objects("person", "a", "b", "c").build();

            assertThat(spec.objects()).extracting(ObjectSpec::id).containsExactly("a", "b", "c");
        }

        @Test
        void looksUpItsParts() {
            SystemSpec spec = base().globalVariable(VariableSpec.unitStock("tension", 0.1))
                    .event(EventSpec.of("e", EventGenerator.Never.INSTANCE, TargetSelector.Everyone.all(),
                            List.of(new Effect.AddTag(Scope.SELF, "t"))))
                    .build();

            assertThat(spec.objectType("person").id()).isEqualTo("person");
            assertThat(spec.event("e").id()).isEqualTo("e");
            assertThat(spec.globalVariable("tension").initial()).isEqualTo(0.1);
            assertThatThrownBy(() -> spec.objectType("ghost")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> spec.event("ghost")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> spec.globalVariable("ghost")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void acceptsASoundSpec() {
            SystemSpec spec = base()
                    .link(LinkSpec.of("l", new VariableRef.OfObject("alice", "trust"),
                            new VariableRef.OfObject("alice", "trust"), 0.1))
                    .build();

            assertThat(spec.validate()).isEmpty();
            assertThat(spec.isValid()).isTrue();
        }

        @Test
        void rejectsDuplicateIds() {
            SystemSpec spec = SystemSpec.builder("sys").objectType(person()).objectType(person())
                    .object(ObjectSpec.of("alice", "person")).object(ObjectSpec.of("alice", "person")).build();

            assertThat(spec.validate()).extracting(ValidationIssue::message)
                    .anyMatch(message -> message.contains("duplicate objectType id"))
                    .anyMatch(message -> message.contains("duplicate object id"));
        }

        @Test
        void rejectsAnObjectOfAnUnknownType() {
            SystemSpec spec = SystemSpec.builder("sys").object(ObjectSpec.of("alice", "ghost")).build();

            assertThat(spec.validate()).singleElement()
                    .satisfies(issue -> assertThat(issue.message()).contains("unknown object type"));
        }

        @Test
        void rejectsAnObjectSettingAVariableItsTypeDoesNotHave() {
            SystemSpec spec = SystemSpec.builder("sys").objectType(person())
                    .object(ObjectSpec.of("alice", "person").with("charisma", 0.5)).build();

            assertThat(spec.validate()).anyMatch(issue -> issue.message().contains("does not declare"));
        }

        @Test
        void warnsAboutInitialValuesOutsideTheDeclaredBounds() {
            SystemSpec spec = SystemSpec.builder("sys").objectType(person())
                    .object(ObjectSpec.of("alice", "person").with("trust", 5.0)).build();

            assertThat(spec.validate()).singleElement().satisfies(issue -> {
                assertThat(issue.isError()).isFalse();
                assertThat(issue.message()).contains("clamped");
            });
        }

        @Test
        void rejectsLinksToUnknownVariables() {
            SystemSpec spec = base()
                    .link(LinkSpec.of("l", new VariableRef.Global("nowhere"),
                            new VariableRef.OfObject("alice", "charisma"), 0.1))
                    .build();

            assertThat(spec.validate()).extracting(ValidationIssue::message)
                    .anyMatch(message -> message.contains("unknown global variable"))
                    .anyMatch(message -> message.contains("has no variable charisma"));
        }

        @Test
        void rejectsLinksFromAnUnknownObjectOrType() {
            SystemSpec spec = base()
                    .link(LinkSpec.of("l1", new VariableRef.OfObject("ghost", "trust"),
                            new VariableRef.OfObject("alice", "trust"), 0.1))
                    .link(LinkSpec.of("l2",
                            new VariableRef.OfType("ghosts", "trust", VariableRef.OfType.Aggregate.MEAN),
                            new VariableRef.OfObject("alice", "trust"), 0.1))
                    .link(LinkSpec.of("l3",
                            new VariableRef.OfType("person", "charisma", VariableRef.OfType.Aggregate.MEAN),
                            new VariableRef.OfObject("alice", "trust"), 0.1))
                    .build();

            assertThat(spec.validate()).extracting(ValidationIssue::message)
                    .anyMatch(message -> message.contains("unknown object ghost"))
                    .anyMatch(message -> message.contains("unknown object type ghosts"))
                    .anyMatch(message -> message.contains("has no variable charisma"));
        }

        @Test
        void rejectsWritingToAConstant() {
            SystemSpec spec = base().globalVariable(VariableSpec.constant("baseline", 1.0))
                    .link(LinkSpec.of("l1", new VariableRef.OfObject("alice", "trust"),
                            new VariableRef.OfObject("alice", "openness"), 0.1))
                    .link(LinkSpec.of("l2", new VariableRef.OfObject("alice", "trust"),
                            new VariableRef.Global("baseline"), 0.1))
                    .build();

            assertThat(spec.validate()).extracting(ValidationIssue::message)
                    .anyMatch(message -> message.contains("cannot write to constant variable"))
                    .anyMatch(message -> message.contains("cannot write to constant global"));
        }

        @Test
        void rejectsALinkTargetingAnAggregateAtConstructionTime() {
            assertThatThrownBy(() -> LinkSpec.of("l", new VariableRef.OfObject("alice", "trust"),
                    new VariableRef.OfType("person", "trust", VariableRef.OfType.Aggregate.MEAN), 0.1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("aggregate");
        }

        @Test
        void warnsAboutAnEventThatDoesNothing() {
            SystemSpec spec = base().event(
                    EventSpec.of("idle", EventGenerator.Never.INSTANCE, TargetSelector.Everyone.all(), List.of()))
                    .build();

            assertThat(spec.validate()).singleElement()
                    .satisfies(issue -> assertThat(issue.message()).contains("no effects"));
        }

        @Test
        void rejectsATriggerEmittingAnUnknownEvent() {
            SystemSpec spec = base()
                    .trigger(new MemoryTrigger("t", "t", MemoryFilter.ANY, TriggerCondition.SubjectPresent.INSTANCE,
                            new ReactivationEffect(0.0, 0.0, false, 0.0, null, 0.0, "ghost"), 0))
                    .build();

            assertThat(spec.validate()).singleElement()
                    .satisfies(issue -> assertThat(issue.message()).contains("emits unknown event ghost"));
        }

        @Test
        void issuesDescribeThemselvesReadably() {
            ValidationIssue issue = ValidationIssue.error("link", "l1", "something is wrong");

            assertThat(issue.toString()).isEqualTo("ERROR link[l1]: something is wrong");
            assertThat(issue.isError()).isTrue();
        }
    }

    @Nested
    @DisplayName("series catalogue")
    class Catalogue {

        @Test
        void listsEveryVariableThatCanBeCharted() {
            SystemSpec spec = base().object(ObjectSpec.of("bob", "person"))
                    .globalVariable(VariableSpec.unitStock("tension", 0.0)).build();

            assertThat(spec.seriesCatalogue()).containsKeys("global.tension", "alice.trust", "bob.trust",
                    "alice.openness");
        }

        @Test
        void skipsObjectsOfUnknownTypes() {
            SystemSpec spec = SystemSpec.builder("sys").object(ObjectSpec.of("alice", "ghost")).build();

            assertThat(spec.seriesCatalogue()).isEmpty();
        }
    }

    @Nested
    @DisplayName("supporting records")
    class Supporting {

        @Test
        void variableSpecEnforcesItsOwnBounds() {
            assertThatThrownBy(() -> new VariableSpec("v", "v", VariableSpec.Kind.STOCK, 5.0, 0.0, 1.0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside");
            assertThatThrownBy(() -> new VariableSpec("v", "v", VariableSpec.Kind.STOCK, 0.0, 1.0, 0.0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> VariableSpec.unitStock(" ", 0.0)).isInstanceOf(IllegalArgumentException.class);
            assertThat(VariableSpec.signedStock("mood", -0.5).clamp(-9.0)).isEqualTo(-1.0);
            assertThat(VariableSpec.constant("k", 3.0).isWritable()).isFalse();
        }

        @Test
        void objectTypeRejectsDuplicateVariables() {
            assertThatThrownBy(() -> ObjectTypeSpec.of("person",
                    List.of(VariableSpec.unitStock("trust", 0.0), VariableSpec.unitStock("trust", 0.0)),
                    MemorySettings.DEFAULT)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void objectTypeExposesItsInitialValues() {
            assertThat(person().initialValues()).containsEntry("trust", 0.5).containsEntry("openness", 0.4);
            assertThatThrownBy(() -> person().variable("ghost")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void objectSpecBuildsUpFluently() {
            ObjectSpec object = ObjectSpec.of("alice", "person").with("trust", 0.8).tagged("lead")
                    .featured("remote", 1.0);

            assertThat(object.variables()).containsEntry("trust", 0.8);
            assertThat(object.tags()).containsExactly("lead");
            assertThat(object.features()).containsEntry("remote", 1.0);
            assertThatThrownBy(() -> ObjectSpec.of(" ", "person")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ObjectSpec("a", " ", "a", Map.of(), Set.of(), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void linkSpecBuildsUpFluentlyAndComputesContributions() {
            LinkSpec link = LinkSpec
                    .of("l", new VariableRef.Global("a"), new VariableRef.Global("b"), 0.5)
                    .delayedBy(3).through(new Transfer.Saturating(0.0, 0.2)).onRateOfChange();

            assertThat(link.delayTicks()).isEqualTo(3);
            assertThat(link.usesRate()).isTrue();
            assertThat(link.contribution(1.0)).isEqualTo(0.2);
            assertThatThrownBy(() -> new LinkSpec("l", "l", new VariableRef.Global("a"), new VariableRef.Global("b"),
                    1.0, -1, Transfer.linear(), false)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void eventSpecBuildsUpFluently() {
            EventSpec event = EventSpec
                    .of("e", EventGenerator.Never.INSTANCE, TargetSelector.Everyone.all(),
                            List.of(new Effect.AddTag(Scope.SELF, "t")))
                    .withCooldown(5).limitedTo(3);

            assertThat(event.cooldownTicks()).isEqualTo(5);
            assertThat(event.hasOccurrenceLimit()).isTrue();
            assertThatThrownBy(() -> EventSpec
                    .of("e", EventGenerator.Never.INSTANCE, TargetSelector.Everyone.all(), List.of())
                    .withCooldown(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void variableRefsRenderStableSeriesKeys() {
            assertThat(new VariableRef.Global("tension").seriesKey()).isEqualTo("global.tension");
            assertThat(new VariableRef.OfObject("alice", "trust").seriesKey()).isEqualTo("alice.trust");
            assertThat(new VariableRef.OfType("person", "trust", VariableRef.OfType.Aggregate.MEAN).seriesKey())
                    .isEqualTo("mean(person.trust)");
            assertThat(new VariableRef.Global("t").isWritable()).isTrue();
            assertThatThrownBy(() -> new VariableRef.Global(" ")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void simulationSettingsValidateTheirIntervals() {
            assertThatThrownBy(() -> new SimulationSettings(0, 0, 0L, 1, false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SimulationSettings(1, -1, 0L, 1, false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SimulationSettings(1, 0, -1L, 1, false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SimulationSettings(1, 0, 0L, -1, false))
                    .isInstanceOf(IllegalArgumentException.class);

            SimulationSettings settings = new SimulationSettings(5, 100, 1_000L, 2, true);
            assertThat(settings.hasTickLimit()).isTrue();
            assertThat(settings.autoCheckpointsEnabled()).isTrue();
            assertThat(settings.shouldSampleAt(10L)).isTrue();
            assertThat(settings.shouldSampleAt(11L)).isFalse();
        }

        @Test
        void interactionConfigBuildsUpFluently() {
            InteractionRule.Config config = InteractionRule.Config.enabled("meet").with("rate", 0.4).weighted(2.0);

            assertThat(config.params()).containsEntry("rate", 0.4);
            assertThat(config.weight()).isEqualTo(2.0);
            assertThatThrownBy(() -> new InteractionRule.Config("meet", true, -1.0, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(InteractionRule.Parameter.of("rate", 0.1, 0.0, 1.0).label()).isEqualTo("rate");
        }
    }
}
