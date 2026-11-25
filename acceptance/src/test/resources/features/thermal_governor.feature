@FR-06 @NFR-1
Feature: Thermal and power governance
  The device tells Heimdall how hot it is; Heimdall widens its duty cycle and, when the device is
  in severe or critical thermal states, stops running inference entirely.

  Background:
    Given a fresh thermal governor

  Scenario Outline: Each platform thermal status maps to a duty policy
    When the platform reports thermal status <status>
    Then the governor tier is <tier>
    And the duty interval is <interval> milliseconds
    And inference is <state>

    Examples:
      | status | tier     | interval | state   |
      | 0      | NONE     | 10000    | running |
      | 1      | LIGHT    | 15000    | running |
      | 2      | MODERATE | 30000    | running |
      | 3      | SEVERE   | paused   | paused  |
      | 4      | CRITICAL | paused   | paused  |
      | 5      | SHUTDOWN | paused   | paused  |

  Scenario: An unknown thermal status is treated as moderate
    When the platform reports thermal status 99
    Then the governor tier is MODERATE

  Scenario: A finished run consumes the duty budget
    Given the governor allows a run now
    When an inference run is consumed
    Then the governor blocks the next run with 10000 milliseconds remaining
    When 10 seconds pass
    Then the governor permits a run

  Scenario: Cooling down lifts a pause immediately
    Given the platform reports thermal status 3
    When the platform reports thermal status 0
    Then the governor permits a run

  Scenario: A paused governor never reports a remaining wait
    When the platform reports thermal status 4
    Then the governor blocks the next run with 0 milliseconds remaining
