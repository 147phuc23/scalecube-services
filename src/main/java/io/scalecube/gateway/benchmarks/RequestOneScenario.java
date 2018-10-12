package io.scalecube.gateway.benchmarks;

import static io.scalecube.gateway.benchmarks.BenchmarksService.CLIENT_RECV_TIME;
import static io.scalecube.gateway.benchmarks.BenchmarksService.CLIENT_SEND_TIME;

import io.scalecube.benchmarks.BenchmarkSettings;
import io.scalecube.benchmarks.metrics.BenchmarkMeter;
import io.scalecube.gateway.clientsdk.ClientMessage;
import io.scalecube.gateway.clientsdk.ReferenceCountUtil;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

public final class RequestOneScenario {

  private static final Logger LOGGER = LoggerFactory.getLogger(RequestOneScenario.class);

  private static final String QUALIFIER = "/benchmarks/one";

  private static final int MULT_FACTOR = 2;

  private RequestOneScenario() {
    // Do not instantiate
  }

  /**
   * Runner function for benchmarks.
   *
   * @param args program arguments
   * @param benchmarkStateFactory producer function for {@link AbstractBenchmarkState}
   */
  public static void runWith(
      String[] args, Function<BenchmarkSettings, AbstractBenchmarkState<?>> benchmarkStateFactory) {

    int multFactor =
        Integer.parseInt(
            BenchmarkSettings.from(args).build().find("multFactor", String.valueOf(MULT_FACTOR)));

    int numOfThreads = Runtime.getRuntime().availableProcessors();
    int injectors = numOfThreads * multFactor;
    Duration rampUpDuration = Duration.ofSeconds(numOfThreads);

    BenchmarkSettings settings =
        BenchmarkSettings.from(args)
            .injectors(injectors)
            .messageRate(1) // workaround
            .rampUpDuration(rampUpDuration)
            .durationUnit(TimeUnit.MILLISECONDS)
            .build();

    AbstractBenchmarkState<?> benchmarkState = benchmarkStateFactory.apply(settings);

    benchmarkState.runWithRampUp(
        (rampUpTick, state) -> state.createClient(),
        state -> {
          LatencyHelper latencyHelper = new LatencyHelper(state);

          BenchmarkMeter clientToServiceMeter = state.meter("meter.client-to-service");
          BenchmarkMeter serviceToClientMeter = state.meter("meter.service-to-client");

          return client ->
              (executionTick, task) ->
                  Mono.defer(
                      () -> {
                        Scheduler taskScheduler = task.scheduler();
                        clientToServiceMeter.mark();
                        return client
                            .requestResponse(enrichRequest(), taskScheduler)
                            .map(RequestOneScenario::enrichResponse)
                            .doOnError(
                                th -> LOGGER.warn("Exception occured on requestResponse: " + th))
                            .doOnNext(
                                msg -> {
                                  Optional.ofNullable(msg.data())
                                      .ifPresent(ReferenceCountUtil::safestRelease);
                                  latencyHelper.calculate(msg);
                                  serviceToClientMeter.mark();
                                })
                            .doOnTerminate(() -> taskScheduler.schedule(task));
                      });
        },
        (state, client) -> client.close());
  }

  private static ClientMessage enrichResponse(ClientMessage msg) {
    return ClientMessage.from(msg).header(CLIENT_RECV_TIME, System.currentTimeMillis()).build();
  }

  private static ClientMessage enrichRequest() {
    return ClientMessage.builder()
        .qualifier(QUALIFIER)
        .header(CLIENT_SEND_TIME, System.currentTimeMillis())
        .build();
  }
}
