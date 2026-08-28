Feature: Memories come back when something brings them back

  Decay alone makes memory a one-way fade. Reactivation is what turns it into a feedback loop, and
  it is the reason a grudge can outlive every event that caused it.

  Background:
    Given a system called "reactivation lab" with two people
    And memories decay exponentially with a half-life of 10 ticks

  Scenario: An event brings a fading memory back
    Given "ana" remembers an interaction with "ben" at full strength
    And a trigger reactivates memories when the event "reminder" fires
    And the event "reminder" is scheduled for tick 30
    When the run advances to tick 29
    Then the memory strength is below 0.15
    When the run advances to tick 30
    Then the memory strength is about 1.00
    And the memory has been reactivated 1 time

  Scenario: Repeated reactivation keeps a memory alive indefinitely
    Given "ana" remembers an interaction with "ben" at full strength
    And a trigger reactivates memories every 5 ticks
    When the run advances to tick 300
    Then the memory strength is above 0.50
    And the memory has been reactivated more than 50 times

  Scenario: Rumination sours how a memory feels
    Given "ana" remembers an interaction with "ben" at full strength with valence -0.30
    And a trigger reactivates memories every 5 ticks and shifts valence by -0.05
    When the run advances to tick 100
    Then the memory valence is below -0.90

  Scenario: A trigger only reaches the memories its filter allows
    Given "ana" remembers an interaction with "ben" at full strength
    And "ana" remembers an observation of "ben" at full strength
    And a trigger reactivates only "observation" memories every 5 ticks
    When the run advances to tick 50
    Then the "observation" memory has been reactivated more than 5 times
    And the "interaction" memory has been reactivated 0 times
