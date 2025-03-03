/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.eth.manager;

import tech.pegasys.pantheon.metrics.Counter;
import tech.pegasys.pantheon.metrics.MetricsSystem;
import tech.pegasys.pantheon.metrics.PantheonMetricCategory;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class MonitoredExecutors {

  public static ExecutorService newFixedThreadPool(
      final String name, final int workerCount, final MetricsSystem metricsSystem) {
    return newMonitoredExecutor(
        name,
        metricsSystem,
        (rejectedExecutionHandler, threadFactory) ->
            new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                threadFactory,
                rejectedExecutionHandler));
  }

  public static ExecutorService newCachedThreadPool(
      final String name, final MetricsSystem metricsSystem) {
    return newMonitoredExecutor(
        name,
        metricsSystem,
        (rejectedExecutionHandler, threadFactory) ->
            new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                threadFactory,
                rejectedExecutionHandler));
  }

  public static ScheduledExecutorService newScheduledThreadPool(
      final String name, final int corePoolSize, final MetricsSystem metricsSystem) {
    return newMonitoredExecutor(
        name,
        metricsSystem,
        (rejectedExecutionHandler, threadFactory) ->
            new ScheduledThreadPoolExecutor(corePoolSize, threadFactory, rejectedExecutionHandler));
  }

  private static <T extends ThreadPoolExecutor> T newMonitoredExecutor(
      final String name,
      final MetricsSystem metricsSystem,
      final BiFunction<RejectedExecutionHandler, ThreadFactory, T> creator) {

    final String metricName = name.toLowerCase(Locale.US).replace('-', '_');

    final T executor =
        creator.apply(
            new CountingAbortPolicy(metricName, metricsSystem),
            new ThreadFactoryBuilder().setNameFormat(name + "-%d").build());

    metricsSystem.createIntegerGauge(
        PantheonMetricCategory.EXECUTORS,
        metricName + "_queue_length_current",
        "Current number of tasks awaiting execution",
        executor.getQueue()::size);

    metricsSystem.createIntegerGauge(
        PantheonMetricCategory.EXECUTORS,
        metricName + "_active_threads_current",
        "Current number of threads executing tasks",
        executor::getActiveCount);

    metricsSystem.createIntegerGauge(
        PantheonMetricCategory.EXECUTORS,
        metricName + "_pool_size_current",
        "Current number of threads in the thread pool",
        executor::getPoolSize);

    metricsSystem.createLongGauge(
        PantheonMetricCategory.EXECUTORS,
        metricName + "_completed_tasks_total",
        "Total number of tasks executed",
        executor::getCompletedTaskCount);

    metricsSystem.createLongGauge(
        PantheonMetricCategory.EXECUTORS,
        metricName + "_submitted_tasks_total",
        "Total number of tasks executed",
        executor::getTaskCount);

    return executor;
  }

  private static class CountingAbortPolicy extends AbortPolicy {

    private final Counter rejectedTaskCounter;

    public CountingAbortPolicy(final String metricName, final MetricsSystem metricsSystem) {
      this.rejectedTaskCounter =
          metricsSystem.createCounter(
              PantheonMetricCategory.EXECUTORS,
              metricName + "_rejected_tasks_total",
              "Total number of tasks rejected by this executor");
    }

    @Override
    public void rejectedExecution(final Runnable r, final ThreadPoolExecutor e) {
      rejectedTaskCounter.inc();
      super.rejectedExecution(r, e);
    }
  }
}
