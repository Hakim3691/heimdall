@FR-02 @NFR-1
Feature: Three-layer trigger cascade
  Heimdall runs the local vision model only when the phone is handheld, the user has
  hesitated, and the screen has stopped changing. Every other case must be silent, because
  the power budget (NFR-1) is spent entirely on inference.

  Background:
    Given a fresh Heimdall trigger cascade

  Scenario: A phone resting on a desk never reaches the vision model
    Given the gyroscope reports a motionless device
    And the user has not interacted for 5 seconds
    And two identical frames have been captured
    When the cascade is evaluated
    Then the cascade blocks with reason MOTION

  Scenario: A device being carried is not a hesitant user
    Given the gyroscope reports a device in motion
    And the user has not interacted for 5 seconds
    And two identical frames have been captured
    When the cascade is evaluated
    Then the cascade blocks with reason MOTION

  Scenario: Holding the phone still without interacting opens every gate
    Given the gyroscope reports a handheld micro-tremor
    And the user has not interacted for 2 seconds
    And two identical frames have been captured
    When the cascade is evaluated
    Then the cascade passes

  Scenario: Without the accessibility stream a changing screen arms hesitation
    Given the accessibility interaction stream is unavailable
    And the gyroscope reports a handheld micro-tremor
    And two frames differ beyond the stability threshold
    When a third identical frame is captured
    And 2 more seconds pass
    Then the cascade passes

  Scenario: A tap restarts the hesitation window
    Given the gyroscope reports a handheld micro-tremor
    And the user tapped 1 second ago
    And two identical frames have been captured
    When the cascade is evaluated
    Then the cascade blocks with reason HESITATION
    When 1 more second passes
    Then the cascade passes

  Scenario: Hesitation alone cannot trigger without a stable screen
    Given the gyroscope reports a handheld micro-tremor
    And the user has not interacted for 2 seconds
    And no frame has been compared yet
    When the cascade is evaluated
    Then the cascade blocks with reason FRAME_STABILITY

  Scenario: A screen that is still changing keeps the cascade closed
    Given the gyroscope reports a handheld micro-tremor
    And the user has not interacted for 2 seconds
    And two frames differ beyond the stability threshold
    When the cascade is evaluated
    Then the cascade blocks with reason FRAME_STABILITY

  Scenario: A frame is only stable relative to the previous one
    Given the gyroscope reports a handheld micro-tremor
    And the user has not interacted for 2 seconds
    And two frames differ beyond the stability threshold
    When a third identical frame is captured
    And the cascade is evaluated
    Then the cascade passes

  Scenario: Rendering a tooltip starts the anti-nag cooldown
    Given the gyroscope reports a handheld micro-tremor
    And the user has not interacted for 2 seconds
    And two identical frames have been captured
    When the cascade passes and a tooltip is rendered
    Then the cascade blocks with reason COOLDOWN
    When 21 more seconds pass
    Then the cascade passes

  Scenario: The cooldown survives a new interaction
    Given the gyroscope reports a handheld micro-tremor
    And the user has not interacted for 2 seconds
    And two identical frames have been captured
    When the cascade passes and a tooltip is rendered
    And the user taps now
    Then the cascade blocks with reason COOLDOWN
