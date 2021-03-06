/**
 * Copyright 2014 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.subjects;

import static org.junit.Assert.assertEquals;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.*;

import rx.*;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observer;
import rx.functions.*;
import rx.observers.TestSubscriber;
import rx.schedulers.Schedulers;

public class ReplaySubjectBoundedConcurrencyTest {

    public static void main(String args[]) {
        try {
            for (int i = 0; i < 100; i++) {
                new ReplaySubjectConcurrencyTest().testSubscribeCompletionRaceCondition();
                new ReplaySubjectConcurrencyTest().testReplaySubjectConcurrentSubscriptions();
                new ReplaySubjectConcurrencyTest().testReplaySubjectConcurrentSubscribersDoingReplayDontBlockEachOther();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Test(timeout = 4000)
    public void testReplaySubjectConcurrentSubscribersDoingReplayDontBlockEachOther() throws InterruptedException {
        final ReplaySubject<Long> replay = ReplaySubject.createUnbounded();
        Thread source = new Thread(new Runnable() {

            @Override
            public void run() {
                Observable.create(new OnSubscribe<Long>() {

                    @Override
                    public void call(Subscriber<? super Long> o) {
                        System.out.println("********* Start Source Data ***********");
                        for (long l = 1; l <= 10000; l++) {
                            o.onNext(l);
                        }
                        System.out.println("********* Finished Source Data ***********");
                        o.onCompleted();
                    }
                }).subscribe(replay);
            }
        });
        source.start();

        long v = replay.toBlocking().last();
        assertEquals(10000, v);

        // it's been played through once so now it will all be replays
        final CountDownLatch slowLatch = new CountDownLatch(1);
        Thread slowThread = new Thread(new Runnable() {

            @Override
            public void run() {
                Subscriber<Long> slow = new Subscriber<Long>() {

                    @Override
                    public void onCompleted() {
                        System.out.println("*** Slow Observer completed");
                        slowLatch.countDown();
                    }

                    @Override
                    public void onError(Throwable e) {
                    }

                    @Override
                    public void onNext(Long args) {
                        if (args == 1) {
                            System.out.println("*** Slow Observer STARTED");
                        }
                        try {
                            if (args % 10 == 0) {
                                Thread.sleep(1);
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                };
                replay.subscribe(slow);
                try {
                    slowLatch.await();
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        });
        slowThread.start();

        Thread fastThread = new Thread(new Runnable() {

            @Override
            public void run() {
                final CountDownLatch fastLatch = new CountDownLatch(1);
                Subscriber<Long> fast = new Subscriber<Long>() {

                    @Override
                    public void onCompleted() {
                        System.out.println("*** Fast Observer completed");
                        fastLatch.countDown();
                    }

                    @Override
                    public void onError(Throwable e) {
                    }

                    @Override
                    public void onNext(Long args) {
                        if (args == 1) {
                            System.out.println("*** Fast Observer STARTED");
                        }
                    }
                };
                replay.subscribe(fast);
                try {
                    fastLatch.await();
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        });
        fastThread.start();
        fastThread.join();

        // slow should not yet be completed when fast completes
        assertEquals(1, slowLatch.getCount());

        slowThread.join();
    }

    @Test
    public void unboundedReplaySubjectConcurrentSubscriptionsLoop() throws Exception {
        for (int i = 0; i < 50; i++) {
            System.out.println(i + " --------------------------------------------------------------- ");
            unboundedReplaySubjectConcurrentSubscriptions();
        }
    }
    
    @Test
    public void unboundedReplaySubjectConcurrentSubscriptions() throws InterruptedException {
        final ReplaySubject<Long> replay = ReplaySubject.createUnbounded();
        
        ReplaySubjectConcurrencyTest.concurrencyTest(replay);
    }

    @Test
    public void unboundedTimeReplaySubjectConcurrentSubscriptionsLoop() throws Exception {
        for (int i = 0; i < 50; i++) {
            System.out.println(i + " --------------------------------------------------------------- ");
            unboundedTimeReplaySubjectConcurrentSubscriptions();
        }
    }
    
    @Test
    public void unboundedTimeReplaySubjectConcurrentSubscriptions() throws InterruptedException {
        final ReplaySubject<Long> replay = ReplaySubject.createUnboundedTime();
        
        ReplaySubjectConcurrencyTest.concurrencyTest(replay);
    }

    /**
     * Can receive timeout if subscribe never receives an onError/onCompleted ... which reveals a race condition.
     */
    @Test(timeout = 10000)
    public void testSubscribeCompletionRaceCondition() {
        for (int i = 0; i < 50; i++) {
            final ReplaySubject<String> subject = ReplaySubject.createUnbounded();
            final AtomicReference<String> value1 = new AtomicReference<String>();

            subject.subscribe(new Action1<String>() {

                @Override
                public void call(String t1) {
                    try {
                        // simulate a slow observer
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    value1.set(t1);
                }

            });

            Thread t1 = new Thread(new Runnable() {

                @Override
                public void run() {
                    subject.onNext("value");
                    subject.onCompleted();
                }
            });

            SubjectObserverThread t2 = new SubjectObserverThread(subject);
            SubjectObserverThread t3 = new SubjectObserverThread(subject);
            SubjectObserverThread t4 = new SubjectObserverThread(subject);
            SubjectObserverThread t5 = new SubjectObserverThread(subject);

            t2.start();
            t3.start();
            t1.start();
            t4.start();
            t5.start();
            try {
                t1.join();
                t2.join();
                t3.join();
                t4.join();
                t5.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            assertEquals("value", value1.get());
            assertEquals("value", t2.value.get());
            assertEquals("value", t3.value.get());
            assertEquals("value", t4.value.get());
            assertEquals("value", t5.value.get());
        }

    }
    
    /**
     * https://github.com/ReactiveX/RxJava/issues/1147
     */
    @Test
    public void testRaceForTerminalState() {
        final List<Integer> expected = Arrays.asList(1);
        for (int i = 0; i < 100000; i++) {
            TestSubscriber<Integer> ts = new TestSubscriber<Integer>();
            Observable.just(1).subscribeOn(Schedulers.computation()).cache().subscribe(ts);
            ts.awaitTerminalEvent();
            ts.assertReceivedOnNext(expected);
            ts.assertTerminalEvent();
        }
    }

    private static class SubjectObserverThread extends Thread {

        private final ReplaySubject<String> subject;
        private final AtomicReference<String> value = new AtomicReference<String>();

        public SubjectObserverThread(ReplaySubject<String> subject) {
            this.subject = subject;
        }

        @Override
        public void run() {
            try {
                // a timeout exception will happen if we don't get a terminal state 
                String v = subject.timeout(2000, TimeUnit.MILLISECONDS).toBlocking().single();
                value.set(v);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    @Test
    public void testReplaySubjectEmissionSubscriptionRace() throws Exception {
        Scheduler s = Schedulers.io();
        Scheduler.Worker worker = Schedulers.io().createWorker();
        try {
            for (int i = 0; i < 50000; i++) {
                if (i % 1000 == 0) {
                    System.out.println(i);
                }
                final ReplaySubject<Object> rs = ReplaySubject.createWithSize(2);
                
                final CountDownLatch finish = new CountDownLatch(1); 
                final CountDownLatch start = new CountDownLatch(1); 
                
                worker.schedule(new Action0() {
                    @Override
                    public void call() {
                        try {
                            start.await();
                        } catch (Exception e1) {
                            e1.printStackTrace();
                        }
                        rs.onNext(1);
                    }
                });
                
                final AtomicReference<Object> o = new AtomicReference<Object>();
                
                rs.subscribeOn(s).observeOn(Schedulers.io())
                .subscribe(new Observer<Object>() {
    
                    @Override
                    public void onCompleted() {
                        o.set(-1);
                        finish.countDown();
                    }
    
                    @Override
                    public void onError(Throwable e) {
                        o.set(e);
                        finish.countDown();
                    }
    
                    @Override
                    public void onNext(Object t) {
                        o.set(t);
                        finish.countDown();
                    }
                    
                });
                start.countDown();
                
                if (!finish.await(5, TimeUnit.SECONDS)) {
                    System.out.println(o.get());
                    System.out.println(rs.hasObservers());
                    rs.onCompleted();
                    Assert.fail("Timeout @ " + i);
                    break;
                } else {
                    Assert.assertEquals(1, o.get());
                    worker.schedule(new Action0() {
                        @Override
                        public void call() {
                            rs.onCompleted();
                        }
                    });
                }
            }
        } finally {
            worker.unsubscribe();
        }
    }
    @Test(timeout = 5000)
    public void testConcurrentSizeAndHasAnyValue() throws InterruptedException {
        final ReplaySubject<Object> rs = ReplaySubject.createUnbounded();
        final CyclicBarrier cb = new CyclicBarrier(2);
        
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.await();
                } catch (InterruptedException e) {
                    return;
                } catch (BrokenBarrierException e) {
                    return;
                }
                for (int i = 0; i < 1000000; i++) {
                    rs.onNext(i);
                }
                rs.onCompleted();
                System.out.println("Replay fill Thread finished!");
            }
        });
        t.start();
        try {
            cb.await();
        } catch (InterruptedException e) {
            return;
        } catch (BrokenBarrierException e) {
            return;
        }
        int lastSize = 0;
        for (; !rs.hasThrowable() && !rs.hasCompleted();) {
            int size = rs.size();
            boolean hasAny = rs.hasAnyValue();
            Object[] values = rs.getValues();
            if (size < lastSize) {
                Assert.fail("Size decreased! " + lastSize + " -> " + size);
            }
            if ((size > 0) && !hasAny) {
                Assert.fail("hasAnyValue reports emptiness but size doesn't");
            }
            if (size > values.length) {
                Assert.fail("Got fewer values than size! " + size + " -> " + values.length);
            }
            for (int i = 0; i < values.length - 1; i++) {
                Integer v1 = (Integer)values[i];
                Integer v2 = (Integer)values[i + 1];
                assertEquals(1, v2 - v1);
            }
            lastSize = size;
        }
        
        t.join();
    }
    @Test(timeout = 5000)
    public void testConcurrentSizeAndHasAnyValueBounded() throws InterruptedException {
        final ReplaySubject<Object> rs = ReplaySubject.createWithSize(3);
        final CyclicBarrier cb = new CyclicBarrier(2);
        
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.await();
                } catch (InterruptedException e) {
                    return;
                } catch (BrokenBarrierException e) {
                    return;
                }
                for (int i = 0; i < 1000000; i++) {
                    rs.onNext(i);
                }
                rs.onCompleted();
                System.out.println("Replay fill Thread finished!");
            }
        });
        t.start();
        try {
            cb.await();
        } catch (InterruptedException e) {
            return;
        } catch (BrokenBarrierException e) {
            return;
        }
        for (; !rs.hasThrowable() && !rs.hasCompleted();) {
            rs.size(); // can't use value so just call to detect hangs
            rs.hasAnyValue(); // can't use value so just call to detect hangs
            Object[] values = rs.getValues();
            for (int i = 0; i < values.length - 1; i++) {
                Integer v1 = (Integer)values[i];
                Integer v2 = (Integer)values[i + 1];
                assertEquals(1, v2 - v1);
            }
        }
        
        t.join();
    }
    @Test(timeout = 10000)
    public void testConcurrentSizeAndHasAnyValueTimeBounded() throws InterruptedException {
        final ReplaySubject<Object> rs = ReplaySubject.createWithTime(1, TimeUnit.MILLISECONDS, Schedulers.computation());
        final CyclicBarrier cb = new CyclicBarrier(2);
        
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.await();
                } catch (InterruptedException e) {
                    return;
                } catch (BrokenBarrierException e) {
                    return;
                }
                for (int i = 0; i < 1000000; i++) {
                    rs.onNext(i);
                    if (i % 10000 == 0) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
                rs.onCompleted();
                System.out.println("Replay fill Thread finished!");
            }
        });
        t.start();
        try {
            cb.await();
        } catch (InterruptedException e) {
            return;
        } catch (BrokenBarrierException e) {
            return;
        }
        for (; !rs.hasThrowable() && !rs.hasCompleted();) {
            rs.size(); // can't use value so just call to detect hangs
            rs.hasAnyValue(); // can't use value so just call to detect hangs
            Object[] values = rs.getValues();
            for (int i = 0; i < values.length - 1; i++) {
                Integer v1 = (Integer)values[i];
                Integer v2 = (Integer)values[i + 1];
                assertEquals(1, v2 - v1);
            }
        }
        
        t.join();
    }
}