@FR-07
Feature: Local model storage
  Heimdall mmaps the two GGUF files the user picks in the system file picker, read in place with
  zero copy. A truncated download or a non-GGUF file must be reported before capture starts, never
  discovered mid-inference.

  Scenario: Both model files present and valid
    Given a language model of 5 GB with a GGUF header
    And 20 GB of free storage
    Then the model validation result is ready

  Scenario: A missing file is reported as missing
    Given a language model with 0 bytes
    Then the model validation reports MISSING

  Scenario: A file that is not GGUF is rejected
    Given a language model of 5 GB with a zip header
    Then the model validation reports NOT_A_GGUF

  Scenario: A truncated download is rejected
    Given a language model of 200 MB with a GGUF header
    Then the model validation reports TRUNCATED

  Scenario: Low free storage is rejected even with valid weights
    Given a language model of 5 GB with a GGUF header
    And 64 MB of free storage
    Then the model validation reports INSUFFICIENT_STORAGE

  Scenario: A valid vision projector is required as well
    Given a vision projector of 1 GB with a GGUF header
    And 20 GB of free storage
    Then the model validation result is ready

  Scenario: The recommended MiniCPM-V architecture is accepted
    Given a language model of 5 GB with GGUF architecture "qwen2"
    And 20 GB of free storage
    Then the model validation result is ready

  Scenario: A minicpmv-architecture conversion is accepted as well
    Given a language model of 5 GB with GGUF architecture "minicpmv"
    And 20 GB of free storage
    Then the model validation result is ready

  Scenario: A GGUF with an unsupported architecture is rejected before loading
    Given a language model of 5 GB with GGUF architecture "bonsai"
    And 20 GB of free storage
    Then the model validation reports INCOMPATIBLE_MODEL

  Scenario: A weights file offered as the vision projector is rejected
    Given a vision projector of 1 GB with GGUF architecture "qwen2"
    And 20 GB of free storage
    Then the model validation reports INCOMPATIBLE_MODEL
