Feature: Runs can be checkpointed, resumed and branched

  Checkpoints are what make the tool usable for asking "what if": run to an interesting moment,
  save it, then explore more than one continuation from the same starting point and compare them.

  Background:
    Given a system called "checkpoint lab" with two people
    And the event "drip" fires every tick and lowers trust by 0.002

  Scenario: A checkpoint can be taken and listed
    Given a run is created
    When the run is stepped by 20 ticks
    And a checkpoint called "before the shock" is taken
    Then the run has 1 checkpoint
    And the checkpoint is at tick 20

  Scenario: Restoring rewinds the run to the checkpoint
    Given a run is created
    When the run is stepped by 20 ticks
    And a checkpoint called "twenty" is taken
    And the run is stepped by 20 ticks
    Then the run is at tick 40
    When the run is restored to the checkpoint "twenty"
    Then the run is at tick 20

  Scenario: Forking leaves the original run untouched
    Given a run is created
    When the run is stepped by 20 ticks
    And a checkpoint called "twenty" is taken
    And the checkpoint "twenty" is forked into a new run
    Then the forked run is at tick 20
    And the forked run records that it branched from the original at tick 20
    And the original run is still at tick 20

  Scenario: A resumed run continues exactly as if it had never stopped
    Given a run is created with seed 777
    When the run is stepped by 50 ticks
    And the run state digest is remembered
    Given a second run is created with seed 777
    When the second run is stepped by 25 ticks
    And a checkpoint is taken on the second run
    And the second run is restored from that checkpoint
    And the second run is stepped by 25 ticks
    Then the second run state digest matches the remembered one
