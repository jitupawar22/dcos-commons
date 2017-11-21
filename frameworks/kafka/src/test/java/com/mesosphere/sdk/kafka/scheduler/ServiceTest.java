package com.mesosphere.sdk.kafka.scheduler;

import org.junit.Test;

import com.mesosphere.sdk.testing.ServiceTestRunner;

public class ServiceTest {

    @Test
    public void testSpec() throws Exception {
        new ServiceTestRunner()
                .setPodEnv("kafka", "KAFKA_ZOOKEEPER_URI", "/path/to/zk") // set by our Main.java
                .setPodEnv("kafka", "ADVERTISED_LISTENERS", "advertised.listeners=FAKE") // set by setup-helper
                .setPodEnv("kafka", "LISTENERS", "listeners=FAKE") // set by setup-helper
                .run();
    }
}
