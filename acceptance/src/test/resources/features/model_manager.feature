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

  Scenario: A Qwen3.5-VL language model is accepted
    Given a language model of 5 GB with GGUF architecture "qwen35"
    And 20 GB of free storage
    Then the model validation result is ready

  Scenario: The official MiniCPM-V 2.6 projector is accepted
    Given a vision projector of 1 GB with GGUF architecture "clip" and projector type "resampler"
    And 20 GB of free storage
    Then the model validation result is ready

  Scenario: A projector with an unknown type is rejected
    Given a vision projector of 1 GB with GGUF architecture "clip" and projector type "bonsai_clip"
    And 20 GB of free storage
    Then the model validation reports INCOMPATIBLE_MODEL

  Scenario: MiniCPM-V weights with a Qwen3-VL projector are rejected as a pair
    Given language weights "qwen2" with projector type "qwen3vl_merger"
    Then the model validation reports INCOMPATIBLE_PAIR

  Scenario: Qwen3.5-VL weights with a Qwen3-VL projector are accepted as a pair
    Given language weights "qwen35" with projector type "qwen3vl_merger"
    Then the model validation result is ready
