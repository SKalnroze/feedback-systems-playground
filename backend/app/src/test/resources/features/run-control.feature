Feature: Runs can be driven from the outside

  A run is a long-lived background process that the user starts, watches, slows down, pauses and
  comes back to later. Control has to be reliable enough that pausing at tick 500 always means tick
  500, whatever speed the run was going at.

  Background:
    Given a system called "control lab" with two people
    And the event "drip" fires every tick and lowers trust by 0.001

  Scenario: A new run starts at tick zero, not yet running
    When a run is created
    Then the run status is "CREATED"
    And the run is at tick 0

  Scenario: Stepping advances exactly the requested number of ticks
    Given a run is created
    When the run is stepped by 5 ticks
    Then the run is at tick 5
    And the run status is "PAUSED"

  Scenario: A paused run stops advancing
    Given a run is created
    When the run is stepped by 3 ticks
    And nothing happens for a moment
    Then the run is at tick 3

  Scenario: A started run advances on its own and can be paused
    Given a run is created
    When the run is started at 200 ticks per second
    And the run reaches at least tick 20
    And the run is paused
    Then the run status is "PAUSED"
    And the run stays at the tick it was paused at

  Scenario: Stopping a run ends it
    Given a run is created
    When the run is stepped by 2 ticks
    And the run is stopped
    Then the run status is "STOPPED"

  Scenario: A run records the seed it used so it can be repeated
    When a run is created with seed 12345
    Then the run reports seed 12345

  Scenario: A seed too large for a browser number survives intact
    When a run is created with seed -3072724163409498002
    Then the run reports seed -3072724163409498002
