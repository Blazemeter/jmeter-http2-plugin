package com.blazemeter.jmeter.http2.integration;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Integration suite for protocol_error regression checks.
 *
 * Run with: -Dit.demoblaze=true -Dit.blazedemo=true -Dit.h2c=true
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    HTTP2ProtocolErrorRegressionIT.class,
    HTTP2ProtocolErrorRegressionBlazeDemoIT.class,
    HTTP2H2cUpgradeCacheIT.class
})
public class HTTP2ProtocolErrorRegressionSuiteIT {
}
