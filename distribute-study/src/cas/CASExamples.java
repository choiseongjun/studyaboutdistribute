package cas;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CASExamples {
    public static class SimpleCAS {
        private volatile int value;

        public SimpleCAS(int initialValue) {
            this.value = initialValue;
        }

        // 값을 읽는 메서드
        public int get() {
            return value;
        }

        // CAS 연산 직접 구현 (실제로는 JVM에서 native 코드로 구현됨)
        public synchronized boolean compareAndSet(int expectedValue, int newValue) {
            // 현재 값이 예상 값과 같은지 확인
            if (value == expectedValue) {
                // 같으면 새 값으로 변경하고 true 반환
                value = newValue;
                return true;
            }
            // 다르면 변경하지 않고 false 반환
            return false;
        }

        // CAS를 활용한 증가 연산 (increment)
        public int incrementAndGet() {
            int current;
            int next;
            do {
                // 현재 값 읽기
                current = get();
                // 새 값 계산 (현재 값 + 1)
                next = current + 1;
                // CAS로 업데이트 시도, 성공할 때까지 반복
            } while (!compareAndSet(current, next));

            return next;
        }
    }

    // 예제 2: Java의 AtomicInteger를 사용한 구현
    public static class Counter {
        private AtomicInteger count = new AtomicInteger(0);

        // 단순 증가
        public void increment() {
            count.incrementAndGet();
        }

        // CAS 패턴을 사용한 조건부 업데이트
        public void conditionalIncrement(int threshold) {
            int current;
            do {
                current = count.get();
                if (current >= threshold) {
                    // 임계값에 도달하면 업데이트하지 않음
                    return;
                }
                // 현재 값이 임계값보다 작으면 증가 시도
            } while (!count.compareAndSet(current, current + 1));
        }

        public int getCount() {
            return count.get();
        }
    }

    // 예제 3: AtomicReference를 사용한 객체 업데이트
    public static class ConcurrentStack<E> {
        private AtomicReference<Node<E>> top = new AtomicReference<>();

        // 노드 클래스 정의
        private static class Node<E> {
            final E item;
            Node<E> next;

            Node(E item) {
                this.item = item;
            }
        }

        // 스택에 요소 추가
        public void push(E item) {
            Node<E> newHead = new Node<>(item);
            Node<E> oldHead;

            do {
                oldHead = top.get();
                newHead.next = oldHead;
                // CAS 연산으로 스택 top 업데이트 시도
            } while (!top.compareAndSet(oldHead, newHead));
        }

        // 스택에서 요소 제거
        public E pop() {
            Node<E> oldHead;
            Node<E> newHead;

            do {
                oldHead = top.get();
                if (oldHead == null) {
                    return null; // 스택이 비어있음
                }
                newHead = oldHead.next;
                // CAS 연산으로 스택 top 업데이트 시도
            } while (!top.compareAndSet(oldHead, newHead));

            return oldHead.item;
        }
    }
    public static void main(String[] args) throws InterruptedException {
        // AtomicInteger 예제 테스트
        final Counter counter = new Counter();

        // 여러 스레드에서 동시에 카운터 증가시키기
        Thread[] threads = new Thread[5];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 1000; j++) {
                    counter.increment();
                }
            });
            threads[i].start();
        }

        // 모든 스레드가 완료될 때까지 대기
        for (Thread thread : threads) {
            thread.join();
        }

        // 결과 출력 - 5000이 되어야 함 (5 스레드 x 1000 증가)
        System.out.println("최종 카운터 값: " + counter.getCount());

        // ConcurrentStack 예제 테스트
        ConcurrentStack<Integer> stack = new ConcurrentStack<>();

        // 여러 스레드에서 동시에 스택 조작하기
        Thread[] stackThreads = new Thread[2];
        stackThreads[0] = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                stack.push(i);
            }
        });

        stackThreads[1] = new Thread(() -> {
            int count = 0;
            for (int i = 0; i < 500; i++) {
                if (stack.pop() != null) {
                    count++;
                }
            }
            System.out.println("팝 성공 횟수: " + count);
        });

        for (Thread thread : stackThreads) {
            thread.start();
        }

        for (Thread thread : stackThreads) {
            thread.join();
        }

        // 최종 스택에서 값 꺼내기
        Integer value;
        int remainingCount = 0;
        while ((value = stack.pop()) != null) {
            remainingCount++;
        }
        System.out.println("남은 스택 요소 수: " + remainingCount);
    }
}
