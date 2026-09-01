Feature: Object types are authored, shared and versioned

  A type defined inside one system cannot be used by the next, so the same vocabulary gets rebuilt
  for every model. A template is that definition published once and reused. It is versioned for the
  same reason systems are: a system built against one shape of "person" must not silently acquire
  another because someone edited the shared copy.

  Scenario: A shared type is created and published
    When an object template called "Worker" is created
    And the template is published
    Then the template has 1 published version

  Scenario: Republishing an unchanged template does not create a second version
    When an object template called "Steady" is created
    And the template is published
    And the template is published again
    Then the template has 1 published version

  Scenario: A system pins the version it was built against
    Given an object template called "Pinned" published as version 1
    When the template gains a variable and is published again
    Then the template has 2 published versions
    And version 1 of the template still has 2 variables

  Scenario: The difference between two versions names what actually changed
    Given an object template called "Drifting" published as version 1
    When the template gains a variable and is published again
    Then the difference between version 1 and version 2 reports "added"

  Scenario: A template with no draft cannot be published
    When an object template with no draft is created
    Then publishing that template fails
