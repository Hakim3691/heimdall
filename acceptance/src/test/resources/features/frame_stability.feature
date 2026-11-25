@FR-02
Feature: Difference hashing of screen frames
  Stage 3 compares a 64-bit dHash of consecutive frames. Identical screens hash to the same
  value; a scroll or a dialog opening must move the hash by more than two bits.

  Scenario: The same frame always produces the same hash
    Given a uniform grey frame
    When the frame is hashed twice
    Then both signatures are identical
    And the Hamming distance is 0

  Scenario: A uniform grey frame hashes to zero
    Given a uniform grey frame
    Then the frame signature is zero

  Scenario: A ramp that brightens to the right has no comparison bit set
    Given a horizontal brightness ramp
    Then the frame signature is zero

  Scenario: An inverted ramp sets every comparison bit
    Given an inverted horizontal brightness ramp
    Then the frame signature has all 64 bits set

  Scenario: A ramp and its inverse differ in all 64 bits
    Given a horizontal brightness ramp
    And an inverted horizontal brightness ramp
    Then the two signatures differ in every bit

  Scenario: A small visual change stays under the stability threshold
    Given a uniform grey frame
    And the same frame with a small bright patch
    Then the two frames are considered static

  Scenario: A different screen breaks stability
    Given a uniform grey frame
    And an inverted horizontal brightness ramp
    Then the two frames are not considered static

  Scenario: Source resolution does not change the verdict for the same content
    Given a horizontal brightness ramp rendered at 32 pixels wide
    And a horizontal brightness ramp rendered at 160 pixels wide
    Then the two frames are considered static
