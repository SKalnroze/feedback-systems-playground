Feature: What a run did can be looked at afterwards

  A simulation nobody can inspect is just a number generator. Every variable's history has to be
  queryable over any timescale, several variables have to be comparable on one chart, and any
  movement has to be traceable back to what caused it.

  Background:
    Given a system called "observability lab" with two people
    And the event "drip" fires every tick and lowers trust by 0.005

  Scenario: Variable history is recorded for every object
    Given a run is created
    When the run is stepped by 30 ticks
    Then the series "ana.trust" has at least 25 points
    And the last value of "ana.trust" is below 0.40

  Scenario: Several variables can be fetched together for one chart
    Given a run is created
    When the run is stepped by 20 ticks
    Then fetching the series "ana.trust" and "ben.trust" returns 2 series

  Scenario: A long range is downsampled rather than returned in full
    Given a run is created
    When the run is stepped by 600 ticks
    Then fetching "ana.trust" with resolution 50 returns fewer than 20 points
    And those points are marked as bucketed

  Scenario: Every event that fired is in the log
    Given a run is created
    When the run is stepped by 10 ticks
    Then the log contains at least 5 entries of type "EVENT"
    And the log mentions the event "drip"

  Scenario: Memories are visible per object, with a relationship summary
    Given the event "drip" also leaves a memory
    And a run is created
    When the run is stepped by 15 ticks
    Then "ana" has at least 1 memory recorded
    And the relationship matrix has an entry from "ana" about "ben"

  Scenario: The series catalogue lists what can be charted
    Given a run is created
    When the run is stepped by 5 ticks
    Then the series catalogue includes "ana.trust"
    And the series catalogue includes "ana.memoryStrength"
