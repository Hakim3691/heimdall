package com.heimdall.acceptance

import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Entry point for the acceptance suite.
 *
 * Runs every `.feature` file under `src/test/resources/features` with the glue in this package.
 * `./gradlew :acceptance:test` therefore executes the whole BDD suite as part of the normal unit
 * test task, which is what makes it a required gate in CI rather than an optional extra.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = "cucumber.glue", value = "com.heimdall.acceptance")
@ConfigurationParameter(key = "cucumber.execution.parallel.enabled", value = "false")
@ConfigurationParameter(key = "cucumber.plugin", value = "pretty")
@ConfigurationParameter(key = "cucumber.snippet-type", value = "camelcase")
class RunCucumberTest
