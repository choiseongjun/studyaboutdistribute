package com.seongjun.distributesystem.utils;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.Lock;
import io.etcd.jetcd.lock.LockResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Slf4j
@Component
public class EtcdDistributedLock implements DisposableBean {
    private final Client client;
    private final Lock lockClient;
    private final Lease leaseClient;

    public EtcdDistributedLock(@Value("${etcd.endpoints:http://localhost:2379}") String endpoints) {
        log.info("Initializing EtcdDistributedLock with endpoints: {}", endpoints);
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
     * @param ttlSeconds 락 유지 시간 (초)
     * @param maxRetries 최대 재시도 횟수
     * @param supplier 락을 획득한 상태에서 실행할 작업
     * @return 작업 결과
     */
    public <T> T executeWithLock(String lockKey, int ttlSeconds, int maxRetries, Supplier<T> supplier) {
        ByteSequence lockKeyBS = ByteSequence.from(lockKey, StandardCharsets.UTF_8);
        long leaseId = 0;
        ByteSequence lockOwner = null;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                log.info("Attempting to acquire lock: {} (attempt {}/{})", lockKey, retryCount + 1, maxRetries);
                
                // Create lease
                leaseId = leaseClient.grant(ttlSeconds).get(5, TimeUnit.SECONDS).getID();
                log.info("Lease granted with ID: {} for lock: {}", leaseId, lockKey);

                // Try to acquire lock
                CompletableFuture<LockResponse> lockFuture = lockClient.lock(lockKeyBS, leaseId);
                LockResponse lockResponse = lockFuture.get(5, TimeUnit.SECONDS);
                lockOwner = lockResponse.getKey();
                log.info("Lock acquired: {} with key: {}", lockKey, lockOwner.toString(StandardCharsets.UTF_8));

                try {
                    // Execute the critical section
                    return supplier.get();
                } finally {
                    // Release the lock
                    if (lockOwner != null) {
                        try {
                            lockClient.unlock(lockOwner).get(5, TimeUnit.SECONDS);
                            log.info("Lock released: {}", lockKey);
                        } catch (Exception e) {
                            log.error("Failed to release lock: {}", lockKey, e);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while acquiring lock: {}", lockKey, e);
                retryCount++;
            } catch (ExecutionException | TimeoutException e) {
                log.error("Error while acquiring lock: {} (attempt {}/{})", lockKey, retryCount + 1, maxRetries, e);
                retryCount++;
            } finally {
                // Revoke lease
                if (leaseId > 0) {
                    try {
                        leaseClient.revoke(leaseId).get(5, TimeUnit.SECONDS);
                        log.info("Lease revoked: {} for lock: {}", leaseId, lockKey);
                    } catch (Exception e) {
                        log.error("Failed to revoke lease: {} for lock: {}", leaseId, lockKey, e);
                    }
                }
            }

            // Wait before retrying
            if (retryCount < maxRetries) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        throw new RuntimeException("Failed to acquire lock after " + maxRetries + " attempts");
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.close();
            log.info("Etcd client closed");
        }
    }
}