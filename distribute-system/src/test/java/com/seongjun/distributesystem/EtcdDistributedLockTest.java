package com.seongjun.distributesystem;

import com.seongjun.distributesystem.utils.EtcdDistributedLock;
import io.etcd.jetcd.Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class EtcdDistributedLockTest {

    private static EtcdDistributedLock distributedLock;
    private static final String ETCD_ENDPOINTS = "http://localhost:2379";

    @BeforeAll
    public static void setup() {
        distributedLock = new EtcdDistributedLock(ETCD_ENDPOINTS);
    }

//    @AfterAll
//    public static void cleanup() {
//        distributedLock.close();
//    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        // 테스트 설정
        final int threadCount = 10;
        final String lockKey = "test-lock-key";
        final AtomicInteger counter = new AtomicInteger(0);
        final List<String> executionOrder = new ArrayList<>();
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(threadCount);

        // 스레드 풀 생성
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        // 각 스레드가 동일한 락을 획득하려고 시도
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    // 모든 스레드가 동시에 시작하도록 대기
                    startLatch.await();

                    // 분산락 획득 시도 및 작업 수행
                    String result = distributedLock.executeWithLock(lockKey, 10, 30, () -> {
                        // 락을 획득한 후 실행되는 코드
                        String threadName = "Thread-" + threadId;
                        System.out.println(threadName + " acquired the lock");

                        // 카운터 증가 (이 작업은 원자적이어야 함)
                        int currentValue = counter.incrementAndGet();

                        // 실행 순서 기록
                        synchronized (executionOrder) {
                            executionOrder.add(threadName + ": " + currentValue);
                        }

                        try {
                            // 작업 시뮬레이션 (랜덤 지연)
                            Thread.sleep((long) (Math.random() * 100));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }

                        System.out.println(threadName + " releasing the lock");
                        return threadName + " completed";
                    });

                    System.out.println("Result: " + result);
                } catch (Exception e) {
                    System.err.println("Error in thread " + threadId + ": " + e.getMessage());
                } finally {
                    // 작업 완료 신호
                    endLatch.countDown();
                }
            });
        }

        // 모든 스레드 동시에 시작
        startLatch.countDown();

        // 모든 스레드가 완료될 때까지 대기
        endLatch.await();
        executorService.shutdown();

        // 결과 확인
        System.out.println("All threads completed");
        System.out.println("Execution order: " + executionOrder);

        // 카운터가 정확히 증가했는지 확인
        assertEquals(threadCount, counter.get(), "Counter should be equal to the number of threads");

        // 각 스레드가 순차적으로 락을 획득했는지 확인
        for (int i = 0; i < threadCount; i++) {
            int value = i + 1;
            boolean found = false;
            for (String execution : executionOrder) {
                if (execution.endsWith(": " + value)) {
                    found = true;
                    break;
                }
            }
            assertEquals(true, found, "Value " + value + " should be in the execution order");
        }
    }

    @Test
    public void testDifferentKeysParallelAccess() throws InterruptedException {
        // 다른 락 키를 사용하는 병렬 접근 테스트
        final int threadCount = 5;
        final AtomicInteger counter = new AtomicInteger(0);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(threadCount * 2);

        // 스레드 풀 생성
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount * 2);

        // 서로 다른 락 키를 사용하는 두 그룹의 스레드 생성
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;

            // 첫 번째 그룹 (key1 사용)
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    distributedLock.executeWithLock("key1", 10, 30, () -> {
                        System.out.println("Group 1 - Thread " + threadId + " acquired lock for key1");
                        counter.incrementAndGet();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        System.out.println("Group 1 - Thread " + threadId + " releasing lock for key1");
                        return null;
                    });
                } catch (Exception e) {
                    System.err.println("Error in Group 1 thread " + threadId + ": " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });

            // 두 번째 그룹 (key2 사용)
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    distributedLock.executeWithLock("key2", 10, 30, () -> {
                        System.out.println("Group 2 - Thread " + threadId + " acquired lock for key2");
                        counter.incrementAndGet();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        System.out.println("Group 2 - Thread " + threadId + " releasing lock for key2");
                        return null;
                    });
                } catch (Exception e) {
                    System.err.println("Error in Group 2 thread " + threadId + ": " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 모든 스레드 동시에 시작
        startLatch.countDown();

        // 모든 스레드가 완료될 때까지 대기
        endLatch.await();
        executorService.shutdown();

        // 결과 확인
        System.out.println("All threads completed in different keys test");
        assertEquals(threadCount * 2, counter.get(), "Counter should be equal to the number of threads");
    }
}