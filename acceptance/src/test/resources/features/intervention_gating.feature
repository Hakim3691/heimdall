@FR-05
Feature: Output schema gate
  A local model drifts. Nothing reaches the screen unless it is exactly the contracted JSON
  object, asks to intervene, reports enough confidence and fits the word budget.

  Background:
    Given an intervention gate with default thresholds

  Scenario: A valid intervention passes the gate
    When the model answers:
      """
      {"intervene": true, "confidence": 0.91, "text": "Tap Allow to continue", "anchor": "top"}
      """
    Then the gate returns an intervention
    And the intervention anchor is TOP
    And the intervention has 4 words

  Scenario: A decision not to intervene is silence, not an error
    When the model answers:
      """
      {"intervene": false}
      """
    Then the gate returns silence with reason NOT_INTERVENE

  Scenario: Low confidence is dropped
    When the model answers:
      """
      {"intervene": true, "confidence": 0.31, "text": "Try again later"}
      """
    Then the gate returns silence with reason LOW_CONFIDENCE

  Scenario: A missing confidence is treated as no confidence
    When the model answers:
      """
      {"intervene": true, "text": "Try again later"}
      """
    Then the gate returns silence with reason LOW_CONFIDENCE

  Scenario: Prose instead of JSON is dropped
    When the model answers:
      """
      The user should tap the green button.
      """
    Then the gate returns silence with reason MALFORMED_OUTPUT

  Scenario: A truncated JSON object is dropped
    When the model answers:
      """
      {"intervene": true, "confidence": 0.9, "text": "Tap the
      """
    Then the gate returns silence with reason MALFORMED_OUTPUT

  Scenario: A nested value is outside the schema
    When the model answers:
      """
      {"intervene": true, "confidence": 0.9, "text": {"hint": "tap"}}
      """
    Then the gate returns silence with reason MALFORMED_OUTPUT

  Scenario: JSON wrapped in prose and code fences still passes
    When the model answers:
      """
      Sure, here is the answer:
      ```json
      {"intervene": true, "confidence": 0.8, "text": "Open settings to fix this"}
      ```
      """
    Then the gate returns an intervention
    And the intervention has 5 words

  Scenario: Blank copy is dropped
    When the model answers:
      """
      {"intervene": true, "confidence": 0.9, "text": "   "}
      """
    Then the gate returns silence with reason EMPTY_TEXT

  Scenario: Over-long copy is truncated to the word budget
    When the model answers with 20 words
    Then the gate returns an intervention
    And the intervention has 15 words

  Scenario: Escaped characters survive parsing
    When the model answers:
      """
      {"intervene": true, "confidence": 0.9, "text": "Tap \"Retry\" and wait"}
      """
    Then the gate returns an intervention
    And the intervention text is:
      """
      Tap "Retry" and wait
      """

  Scenario: A missing anchor defaults to the bottom of the screen
    When the model answers:
      """
      {"intervene": true, "confidence": 0.75, "text": "Swipe up from the bottom"}
      """
    Then the gate returns an intervention
    And the intervention anchor is BOTTOM
