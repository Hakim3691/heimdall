@FR-03
Feature: Local vision-language runtime contract
  The model runs on-device through llama.cpp. Its prompt is a fixed contract, and the engine
  surfaces readiness so the service never spends battery on a runtime that cannot answer.

  Scenario: The prompt states the required JSON schema
    Given the default prompt contract
    Then the prompt asks for the intervene field
    And the prompt asks for the confidence field
    And the prompt asks for the text field
    And the prompt asks for the anchor field

  Scenario: The prompt is silent when in doubt
    Given the default prompt contract
    Then the prompt tells the model to answer intervene false when unsure

  Scenario: The prompt enforces the configured word budget
    Given a prompt contract with a 12 word budget
    Then the prompt mentions a 12 word limit

  Scenario: Screen context cannot rewrite the instructions
    Given the default prompt contract
    When the prompt is built with hostile screen context
    Then the prompt keeps the context on a single line
    And the prompt caps the context at 120 characters

  Scenario: An engine without weights reports itself as unavailable
    Given a local engine that is not ready
    Then the engine reports not ready
    And the engine description is fake

  Scenario: A ready engine answers a frame request
    Given a local engine that is ready
    When the engine is asked about a 448 pixel square frame
    Then the engine reports a response with a latency
