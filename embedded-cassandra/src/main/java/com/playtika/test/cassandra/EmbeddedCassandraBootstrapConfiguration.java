/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Playtika
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.playtika.test.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;

import java.net.InetSocketAddress;

import static com.playtika.test.cassandra.CassandraProperties.BEAN_NAME_EMBEDDED_CASSANDRA;
import static com.playtika.test.common.utils.ContainerUtils.containerLogsConsumer;
import static com.playtika.test.common.utils.ContainerUtils.startAndLogTime;

@Slf4j
@Configuration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "embedded.cassandra.enabled", matchIfMissing = true)
@EnableConfigurationProperties(CassandraProperties.class)
@RequiredArgsConstructor
public class EmbeddedCassandraBootstrapConfiguration {


    @Bean(name = BEAN_NAME_EMBEDDED_CASSANDRA, destroyMethod = "stop")
    public GenericContainer cassandra(ConfigurableEnvironment environment,
                                  CassandraProperties properties) {

        log.info("Starting Cassandra cluster. Docker image: {}", properties.dockerImage);

        GenericContainer cassandra =
                new FixedHostPortGenericContainer(properties.dockerImage)
                        .withEnv("CASSANDRA_DC", properties.getDatacenter())
                        .withEnv("CASSANDRA_ENDPOINT_SNITCH", "GossipingPropertyFileSnitch")
                        .withExposedPorts(properties.port)
                        .withLogConsumer(containerLogsConsumer(log))
                        .withStartupTimeout(properties.getTimeoutDuration());
        startAndLogTime(cassandra);
        CassandraEnv cassandraEnv = registerCassandraEnvironment(environment, cassandra, properties);
        createKeySpace(cassandraEnv);
        log.info("Started Cassandra. Connection details: {}", cassandraEnv);
        return cassandra;
    }

    static CassandraEnv registerCassandraEnvironment(ConfigurableEnvironment environment,
                                                     GenericContainer cassandra,
                                                     CassandraProperties properties) {
        String host = cassandra.getContainerIpAddress();
        Integer mappedPort = cassandra.getMappedPort(properties.getPort());
        CassandraEnv cassandraEnv = new CassandraEnv(host, mappedPort, properties.datacenter, properties.keyspaceName);
        MapPropertySource propertySource = new MapPropertySource("embeddedCassandraInfo", cassandraEnv.toMap());
        environment.getPropertySources().addFirst(propertySource);
        return cassandraEnv;
    }

    static void createKeySpace(CassandraEnv cassandraEnv) {
        try (CqlSession session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(cassandraEnv.getHost(), cassandraEnv.getPort()))
                .withLocalDatacenter(cassandraEnv.getDatacenter())
                .build()) {

            String createKeyspaceQuery = "CREATE KEYSPACE " + cassandraEnv.getKeyspaceName()
                    + " WITH REPLICATION = { 'class':'SimpleStrategy', 'replication_factor' : 3 }";
            session.execute(createKeyspaceQuery);
        }
    }
}
