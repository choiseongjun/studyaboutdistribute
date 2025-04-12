package com.seongjun.distributesystem.config;

import com.seongjun.distributesystem.utils.EtcdDistributedLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EtcdConfig {

    @Value("${etcd.endpoints:http://localhost:2379}")
    private String etcdEndpoints;

    @Bean
    public EtcdDistributedLock etcdDistributedLock() {
        // 직접 문자열을 전달하여 빈 생성
        return new EtcdDistributedLock(etcdEndpoints);
    }
}