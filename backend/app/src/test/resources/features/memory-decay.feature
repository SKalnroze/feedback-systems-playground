Feature: Memories fade at a rate the model chooses

  The tool exists to compare forgetting curves, so the decay model has to be something an author
  picks per system and sees the consequences of. These scenarios pin down the behaviour that
  distinguishes the models from each other.

  Ages are counted from when the memory formed rather than from the start of the run, because that
  is what every decay model is actually a function of.

  Background:
    Given a system called "decay lab" with two people

  Scenario: Exponential decay halves a memory's strength every half-life
    Given memories decay exponentially with a half-life of 10 ticks
    And "ana" remembers an interaction with "ben" at full strength
    When the memory is 10 ticks old
    Then the memory strength is about 0.50
    When the memory is 20 ticks old
    Then the memory strength is about 0.25
    When the memory is 30 ticks old
    Then the memory strength is about 0.125

  Scenario: Power-law decay leaves a much longer tail than exponential
    Given memories decay by power law with exponent 0.5
    And "ana" remembers an interaction with "ben" at full strength
    When the memory is 200 ticks old
    Then the memory strength is above 0.05

  Scenario: A decay floor makes part of a memory permanent
    Given memories decay exponentially with a half-life of 5 ticks and a floor of 0.20
    And "ana" remembers an interaction with "ben" at full strength
    When the memory is 500 ticks old
    Then the memory strength is about 0.20

  Scenario: Forgotten memories are dropped when pruning is enabled
    Given memories decay exponentially with a half-life of 5 ticks
    And forgotten memories are pruned below a strength of 0.10
    And "ana" remembers an interaction with "ben" at full strength
    When the memory is 100 ticks old
    Then "ana" remembers nothing about "ben"

  Scenario: Without pruning a faded memory is still there, just weak
    Given memories decay exponentially with a half-life of 5 ticks
    And forgotten memories are kept
    And "ana" remembers an interaction with "ben" at full strength
    When the memory is 100 ticks old
    Then "ana" still holds 1 memory about "ben"
    And the memory strength is below 0.01
