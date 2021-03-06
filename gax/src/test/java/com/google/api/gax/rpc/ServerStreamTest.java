/*
 * Copyright 2017, Google LLC All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google LLC nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.google.api.gax.rpc;

import com.google.api.core.SettableApiFuture;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.truth.Truth;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ServerStreamTest {
  private TestStreamController controller;
  private ServerStream<Integer> stream;
  private ExecutorService executor;

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Before
  public void setUp() throws Exception {
    controller = new TestStreamController();
    stream = new ServerStream<>();

    stream.observer().onStart(controller);
    executor = Executors.newCachedThreadPool();
  }

  @After
  public void tearDown() throws Exception {
    executor.shutdownNow();
  }

  @Test
  public void testEmptyStream() {
    stream.observer().onStart(controller);
    stream.observer().onComplete();

    Truth.assertThat(Lists.newArrayList(stream)).isEmpty();
  }

  @Test
  public void testMultipleItemStream() throws Exception {
    Future<Void> producerFuture =
        executor.submit(
            new Callable<Void>() {
              @Override
              public Void call() throws InterruptedException {
                for (int i = 0; i < 5; i++) {
                  int requestCount = controller.requests.poll(1, TimeUnit.SECONDS);

                  Truth.assertWithMessage("ServerStream should request one item at a time")
                      .that(requestCount)
                      .isEqualTo(1);

                  stream.observer().onResponse(i);
                }
                stream.observer().onComplete();
                return null;
              }
            });

    Future<List<Integer>> consumerFuture =
        executor.submit(
            new Callable<List<Integer>>() {
              @Override
              public List<Integer> call() throws Exception {
                return Lists.newArrayList(stream);
              }
            });

    producerFuture.get(60, TimeUnit.SECONDS);
    List<Integer> results = consumerFuture.get();
    Truth.assertThat(results).containsExactly(0, 1, 2, 3, 4);
  }

  @Test
  public void testEarlyTermination() throws Exception {
    Future<Void> taskFuture =
        executor.submit(
            new Callable<Void>() {
              @Override
              public Void call() throws InterruptedException, ExecutionException, TimeoutException {
                int i = 0;
                while (controller.requests.poll(500, TimeUnit.MILLISECONDS) != null) {
                  stream.observer().onResponse(i++);
                }
                Throwable cancelException = controller.cancelFuture.get(1, TimeUnit.SECONDS);
                stream.observer().onError(cancelException);
                return null;
              }
            });

    List<Integer> results = Lists.newArrayList();
    for (Integer result : stream) {
      results.add(result);

      if (result == 1) {
        stream.cancel();
      }
    }

    taskFuture.get(30, TimeUnit.SECONDS);

    Truth.assertThat(results).containsExactly(0, 1);
  }

  @Test
  public void testErrorPropagation() {
    ClassCastException e = new ClassCastException("fake error");

    stream.observer().onError(e);
    expectedException.expectMessage(e.getMessage());
    expectedException.expect(ClassCastException.class);

    Lists.newArrayList(stream);
  }

  @Test
  public void testNoErrorsBetweenHasNextAndNext() throws InterruptedException {
    Iterator<Integer> it = stream.iterator();

    controller.requests.poll(1, TimeUnit.SECONDS);
    stream.observer().onResponse(1);

    Truth.assertThat(it.hasNext()).isTrue();

    RuntimeException fakeError = new RuntimeException("fake");
    stream.observer().onError(fakeError);
    Truth.assertThat(it.next()).isEqualTo(1);

    // Now the error should be thrown
    try {
      it.next();
      throw new RuntimeException("ServerStream never threw an error!");
    } catch (RuntimeException e) {
      Truth.assertThat(e).isSameAs(fakeError);
    }
  }

  @Test
  public void testReady() throws InterruptedException {
    Iterator<Integer> it = stream.iterator();
    Truth.assertThat(stream.isReady()).isFalse();

    controller.requests.poll(1, TimeUnit.SECONDS);
    stream.observer().onResponse(1);
    Truth.assertThat(stream.isReady()).isTrue();

    it.next();
    Truth.assertThat(stream.isReady()).isFalse();
  }

  @Test
  public void testNextAfterEOF() {
    Iterator<Integer> it = stream.iterator();
    stream.observer().onComplete();

    // Precondition
    Truth.assertThat(it.hasNext()).isFalse();

    expectedException.expect(NoSuchElementException.class);
    it.next();
  }

  @Test
  public void testAfterError() {
    Iterator<Integer> it = stream.iterator();

    RuntimeException expectError = new RuntimeException("my upstream error");
    stream.observer().onError(expectError);

    Throwable actualError = null;

    try {
      it.hasNext();
    } catch (Throwable t) {
      actualError = t;
    }

    Truth.assertThat(actualError).isEqualTo(expectError);

    try {
      it.next();
    } catch (Throwable t) {
      actualError = t;
    }
    Truth.assertThat(actualError).isEqualTo(expectError);
  }

  private static class TestStreamController implements StreamController {
    SettableApiFuture<Throwable> cancelFuture = SettableApiFuture.create();
    BlockingQueue<Integer> requests = Queues.newLinkedBlockingDeque();
    boolean autoFlowControl = true;

    @Override
    public void cancel() {
      cancelFuture.set(new CancellationException("User cancelled stream"));
    }

    @Override
    public void disableAutoInboundFlowControl() {
      autoFlowControl = false;
    }

    @Override
    public void request(int count) {
      requests.add(count);
    }
  }
}
