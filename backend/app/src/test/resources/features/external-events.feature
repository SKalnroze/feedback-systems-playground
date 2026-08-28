Feature: External events arrive on the schedule the author chose

  Systems are run to see how they respond to things that happen to them, so the range needs to
  cover the whole span from "nothing ever happens" to a specific shock at a specific tick.

  Background:
    Given a system called "event lab" with two people

  Scenario: A system with no events changes nothing on its own
    When the run advances to tick 50
    Then no events have fired
    And "ana" has trust of about 0.50

  Scenario: A scheduled event fires exactly once, at its tick
    Given the event "shock" is scheduled for tick 10 and lowers trust by 0.20
    When the run advances to tick 50
    Then the event "shock" has fired 1 time
    And "ana" has trust of about 0.30

  Scenario: A probabilistic event fires at roughly its stated rate
    Given the event "chance" fires with probability 0.30 each tick and lowers trust by 0.01
    When the run advances to tick 400
    Then the event "chance" has fired between 90 and 150 times

  Scenario: A cooldown keeps an event from firing again too soon
    Given the event "shock" fires every tick with a cooldown of 10 ticks and lowers trust by 0.05
    When the run advances to tick 100
    Then the event "shock" has fired between 9 and 11 times

  Scenario: An occurrence limit stops an event for good
    Given the event "shock" fires every tick at most 3 times and lowers trust by 0.05
    When the run advances to tick 100
    Then the event "shock" has fired 3 times
