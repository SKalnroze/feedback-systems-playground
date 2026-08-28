Feature: Systems are defined, validated and published

  A system is edited as a draft and published as an immutable version. Runs point at versions, so
  editing a system can never change what a finished run meant.

  Scenario: A system is created and appears in the list
    When a system called "my first system" is created
    Then the system appears in the list of systems
    And the system has no published versions

  Scenario: A valid draft can be published
    Given a system called "publishable" with two people
    When the draft is published
    Then the system has 1 published version
    And the published version is version 1

  Scenario: Publishing an unchanged draft does not create a second version
    Given a system called "unchanged" with two people
    When the draft is published
    And the draft is published again
    Then the system has 1 published version

  Scenario: A draft with a dangling reference is rejected
    Given a system called "broken" with two people
    And the draft has a link from a variable that does not exist
    When publishing is attempted
    Then publishing fails with a validation error mentioning "unknown"

  Scenario: Validation reports the feedback loops it found
    Given a system called "looped" with two people
    And the draft has a reinforcing loop between two variables
    When the draft is validated
    Then the validation reports 1 feedback loop
    And that loop is reinforcing

  Scenario: A system can be started from a module preset
    When a system is created from the preset "office-team-8"
    Then the draft has 8 objects
    And the draft is valid
