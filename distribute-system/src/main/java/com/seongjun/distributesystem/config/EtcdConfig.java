package com.seongjun.distributesystem.config;

import io.etcd.jetcd.Client;
import io.etcd.jetcd.ClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration
public class EtcdConfig {

    @Value("${etcd.endpoints:http://localhost:2379}")
    private String etcdEndpoints;

    @Value("${etcd.connection.timeout:5000}")
    private int connectionTimeout;

    @Bean
    public Client etcdClient() {
        return Client.builder()
                .endpoints(etcdEndpoints)
                .connectTimeout(Duration.ofMillis(connectionTimeout))
                .build();
    }
}