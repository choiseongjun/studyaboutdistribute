package com.seongjun.distributesystem.utils;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.Lock;
import io.etcd.jetcd.lock.LockResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
public class EtcdDistributedLock implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(EtcdDistributedLock.class);
    private final Client client;
    private final Lock lockClient;
    private final Lease leaseClient;

    public EtcdDistributedLock(@Value("${etcd.endpoints:http://localhost:2379}") String endpoints) {
        logger.info("Initializing EtcdDistributedLock with endpoints: {}", endpoints);
        this.client = Client.builder()
                .endpoints(endpoints.split(","))
                .build();
        this.lockClient = client.getLockClient();
        this.leaseClient = client.getLeaseClient();
    }

    /**
     * 분산락을 획득하고 작업을 실행한 후 락을 해제합니다.
     *
     * @param lockKey  락 키
     * @param timeout  락 획득 타임아웃 (초)
     * @param leaseTime 리스 타임 (초)
     * @param supplier 락을 획득한 상태에서 실행할 작업
     * @return 작업 결과
     */
    public <T> T executeWithLock(String lockKey, long timeout, long leaseTime, Supplier<T> supplier) {
        ByteSequence lockKeyBS = ByteSequence.from(lockKey, StandardCharsets.UTF_8);
        long leaseId = 0;
        ByteSequence lockOwner = null;

        try {
            logger.info("Attempting to acquire lock: {} with timeout: {}s, leaseTime: {}s", lockKey, timeout, leaseTime);
            
            // 리스 생성 (타임아웃을 별도로 설정)
            leaseId = leaseClient.grant(leaseTime).get(5, TimeUnit.SECONDS).getID();
            logger.info("Lease granted with ID: {} for lock: {}", leaseId, lockKey);

            // 락 획득 시도
            CompletableFuture<LockResponse> lockFuture = lockClient.lock(lockKeyBS, leaseId);
            LockResponse lockResponse = lockFuture.get(timeout, TimeUnit.SECONDS);
            lockOwner = lockResponse.getKey();
            logger.info("Lock acquired: {} with key: {}", lockKey, lockOwner.toString(StandardCharsets.UTF_8));

            try {
                // 락을 획득한 상태에서 작업 실행
                return supplier.get();
            } finally {
                // 락 해제
                if (lockOwner != null) {
                    try {
                        lockClient.unlock(lockOwner).get(5, TimeUnit.SECONDS);
                        logger.info("Lock released: {}", lockKey);
                    } catch (Exception e) {
                        logger.error("Failed to release lock: {}", lockKey, e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while acquiring lock: {}", lockKey, e);
            throw new RuntimeException("Interrupted while acquiring lock: " + lockKey, e);
        } catch (ExecutionException e) {
            logger.error("Execution error while acquiring lock: {}", lockKey, e);
            throw new RuntimeException("Execution error while acquiring lock: " + lockKey, e);
        } catch (TimeoutException e) {
            logger.error("Timeout while acquiring lock: {} after {} seconds", lockKey, timeout, e);
            throw new RuntimeException("Timeout while acquiring lock: " + lockKey + " after " + timeout + " seconds", e);
        } finally {
            // 리스 해제
            if (leaseId > 0) {
                try {
                    leaseClient.revoke(leaseId).get(5, TimeUnit.SECONDS);
                    logger.info("Lease revoked: {} for lock: {}", leaseId, lockKey);
                } catch (Exception e) {
                    logger.error("Failed to revoke lease: {} for lock: {}", leaseId, lockKey, e);
                }
            }
        }
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.close();
            logger.info("Etcd client closed");
        }
    }
}