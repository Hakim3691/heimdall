@FR-01 @FR-04
Feature: Onboarding and privilege readiness
  Every privilege is granted by a separate user gesture, and projection consent cannot be
  persisted. Onboarding therefore asks for them in a fixed order and only starts capture when
  nothing is missing.

  Scenario: A fresh install starts with the overlay permission
    Given a fresh install with no models
    Then the next onboarding step is OVERLAY

  Scenario: Accessibility is requested after the overlay
    Given overlay granted but nothing else
    Then the next onboarding step is ACCESSIBILITY

  Scenario: Notifications are requested before models
    Given overlay and accessibility granted
    Then the next onboarding step is NOTIFICATIONS

  Scenario: Model files are required before capture
    Given every permission except projection
    Then the next onboarding step is MODELS

  Scenario: Projection consent is the last step
    Given every permission and both models ready except projection
    Then the next onboarding step is PROJECTION

  Scenario: Capture can start only when nothing is missing
    Given permissions granted with models ready but no projection consent
    Then capture cannot start
    When projection consent is granted
    Then capture can start
    And the next onboarding step is DONE
