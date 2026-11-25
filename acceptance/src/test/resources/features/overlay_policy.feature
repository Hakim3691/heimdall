@FR-01
Feature: Tooltip presentation policy
  The pill is display-only: it never takes a tap meant for the app underneath, it disappears
  on its own, and its copy obeys the 15-word budget.

  Background:
    Given the default overlay configuration

  Scenario: A tooltip is never touchable
    Given an intervention anchored BOTTOM
    Then the overlay spec is not touchable

  Scenario: Tooltips auto-dismiss after 4.5 seconds
    Given an intervention anchored BOTTOM
    Then the overlay spec dismisses after 4500 milliseconds

  Scenario: The tooltip is inset 120 dp from the anchored edge
    Given an intervention anchored TOP
    Then the overlay spec uses a 120 dp inset

  Scenario: An intervention's anchor is respected
    Given an intervention anchored TOP
    Then the overlay spec is anchored TOP

  Scenario: Expanded help is off unless the user opted in
    Given an intervention anchored BOTTOM
    Then expanded help is unavailable

  Scenario: Expanded help follows the opt-in setting
    Given overlay configuration with expanded help enabled
    And an intervention anchored BOTTOM
    Then expanded help is available
    And the overlay spec is still not touchable

  Scenario: Copy is normalised to a single line
    Given an intervention with line breaks in its text
    Then the overlay text contains no line breaks

  Scenario: Copy longer than the budget is truncated
    Given an intervention with 22 words
    Then the overlay text has at most 15 words
