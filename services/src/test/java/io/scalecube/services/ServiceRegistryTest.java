package io.scalecube.services;

import static io.scalecube.services.discovery.api.ServiceDiscoveryEvent.Type.ENDPOINT_ADDED;
import static io.scalecube.services.discovery.api.ServiceDiscoveryEvent.Type.ENDPOINT_LEAVING;
import static io.scalecube.services.discovery.api.ServiceDiscoveryEvent.Type.ENDPOINT_REMOVED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.scalecube.cluster.codec.jackson.JacksonMetadataCodec;
import io.scalecube.cluster.metadata.JdkMetadataCodec;
import io.scalecube.cluster.metadata.MetadataCodec;
import io.scalecube.net.Address;
import io.scalecube.services.discovery.ScalecubeServiceDiscovery;
import io.scalecube.services.discovery.api.ServiceDiscoveryEvent;
import io.scalecube.services.discovery.api.ServiceDiscoveryFactory;
import io.scalecube.services.sut.AnnotationService;
import io.scalecube.services.sut.AnnotationServiceImpl;
import io.scalecube.services.sut.GreetingServiceImpl;
import io.scalecube.services.transport.rsocket.RSocketServiceTransport;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;
import reactor.test.StepVerifier;

public class ServiceRegistryTest extends BaseTest {

  public static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static Stream<Arguments> metadataCodecSource() {
    return Stream.of(
        Arguments.of(new JdkMetadataCodec()), Arguments.of(new JacksonMetadataCodec()));
  }

  @ParameterizedTest
  @MethodSource("metadataCodecSource")
  public void test_added_removed_registration_events(MetadataCodec metadataCodec) {
    ReplayProcessor<ServiceDiscoveryEvent> events = ReplayProcessor.create();

    Microservices seed =
        Microservices.builder()
            .discovery("seed", defServiceDiscovery(metadataCodec))
            .transport(RSocketServiceTransport::new)
            .startAwait();

    seed.listenDiscovery().subscribe(events);

    Address seedAddress = seed.discovery("seed").address();

    Microservices ms1 =
        Microservices.builder()
            .discovery("ms1", defServiceDiscovery(seedAddress, metadataCodec))
            .transport(RSocketServiceTransport::new)
            .services(new GreetingServiceImpl())
            .startAwait();

    Microservices ms2 =
        Microservices.builder()
            .discovery("ms2", defServiceDiscovery(seedAddress, metadataCodec))
            .transport(RSocketServiceTransport::new)
            .services(new GreetingServiceImpl())
            .startAwait();

    StepVerifier.create(events)
        .assertNext(event -> assertEquals(ENDPOINT_ADDED, event.type()))
        .assertNext(event -> assertEquals(ENDPOINT_ADDED, event.type()))
        .then(() -> Mono.whenDelayError(ms1.shutdown(), ms2.shutdown()).block(TIMEOUT))
        .assertNext(event -> assertEquals(ENDPOINT_LEAVING, event.type()))
        .assertNext(event -> assertEquals(ENDPOINT_LEAVING, event.type()))
        .assertNext(event -> assertEquals(ENDPOINT_REMOVED, event.type()))
        .assertNext(event -> assertEquals(ENDPOINT_REMOVED, event.type()))
        .then(() -> seed.shutdown().block(TIMEOUT))
        .thenCancel()
        .verify(TIMEOUT);
  }

  @ParameterizedTest
  @MethodSource("metadataCodecSource")
  public void test_listen_to_discovery_events(MetadataCodec metadataCodec) {
    ReplayProcessor<ServiceDiscoveryEvent> processor = ReplayProcessor.create();

    List<Microservices> cluster = new CopyOnWriteArrayList<>();

    Microservices seed =
        Microservices.builder()
            .discovery("seed", defServiceDiscovery(metadataCodec))
            .transport(RSocketServiceTransport::new)
            .services(new AnnotationServiceImpl())
            .startAwait();
    cluster.add(seed);

    seed.listenDiscovery().subscribe(processor);

    Address seedAddress = seed.discovery("seed").address();

    StepVerifier.create(processor)
        .then(
            () -> {
              Microservices ms1 =
                  Microservices.builder()
                      .discovery("ms1", defServiceDiscovery(seedAddress, metadataCodec))
                      .transport(RSocketServiceTransport::new)
                      .services(new GreetingServiceImpl())
                      .startAwait();
              cluster.add(ms1);
            })
        .assertNext(event -> assertEquals(ENDPOINT_ADDED, event.type()))
        .then(
            () -> {
              Microservices ms2 =
                  Microservices.builder()
                      .discovery("ms2", defServiceDiscovery(seedAddress, metadataCodec))
                      .transport(RSocketServiceTransport::new)
                      .services(new GreetingServiceImpl())
                      .startAwait();
              cluster.add(ms2);
            })
        .assertNext(event -> assertEquals(ENDPOINT_ADDED, event.type()))
        .then(() -> cluster.remove(2).shutdown().block(TIMEOUT))
        .assertNext(event -> assertEquals(ENDPOINT_LEAVING, event.type()))
        .assertNext(event -> assertEquals(ENDPOINT_REMOVED, event.type()))
        .then(() -> cluster.remove(1).shutdown().block(TIMEOUT))
        .assertNext(event -> assertEquals(ENDPOINT_LEAVING, event.type()))
        .assertNext(event -> assertEquals(ENDPOINT_REMOVED, event.type()))
        .thenCancel()
        .verify(TIMEOUT);

    StepVerifier.create(seed.call().api(AnnotationService.class).serviceDiscoveryEventTypes())
        .assertNext(type -> assertEquals(ENDPOINT_ADDED, type))
        .assertNext(type -> assertEquals(ENDPOINT_ADDED, type))
        .assertNext(type -> assertEquals(ENDPOINT_LEAVING, type))
        .assertNext(type -> assertEquals(ENDPOINT_REMOVED, type))
        .assertNext(type -> assertEquals(ENDPOINT_LEAVING, type))
        .assertNext(type -> assertEquals(ENDPOINT_REMOVED, type))
        .thenCancel()
        .verify(TIMEOUT);

    Mono.whenDelayError(cluster.stream().map(Microservices::shutdown).toArray(Mono[]::new))
        .block(TIMEOUT);
  }

  @ParameterizedTest
  @MethodSource("metadataCodecSource")
  public void test_delayed_listen_to_discovery_events(MetadataCodec metadataCodec) {
    ReplayProcessor<ServiceDiscoveryEvent> processor = ReplayProcessor.create();

    List<Microservices> cluster = new CopyOnWriteArrayList<>();

    Microservices seed =
        Microservices.builder()
            .discovery("seed", defServiceDiscovery(metadataCodec))
            .transport(RSocketServiceTransport::new)
            .services(new GreetingServiceImpl())
            .startAwait();
    cluster.add(seed);

    seed.listenDiscovery().subscribe(processor);

    Address seedAddress = seed.discovery("seed").address();

    StepVerifier.create(processor)
        .then(
            () -> {
              Microservices ms1 =
                  Microservices.builder()
                      .discovery("ms1", defServiceDiscovery(seedAddress, metadataCodec))
                      .transport(RSocketServiceTransport::new)
                      .services(new GreetingServiceImpl(), new AnnotationServiceImpl())
                      .startAwait();
              cluster.add(ms1);
            })
        .assertNext(event -> assertEquals(ENDPOINT_ADDED, event.type()))
        .then(
            () -> {
              Microservices ms2 =
                  Microservices.builder()
                      .discovery("ms2", defServiceDiscovery(seedAddress, metadataCodec))
                      .transport(RSocketServiceTransport::new)
                      .services(new GreetingServiceImpl())
                      .startAwait();
              cluster.add(ms2);
            })
        .assertNext(event -> assertEquals(ENDPOINT_ADDED, event.type()))
        .thenCancel()
        .verify(TIMEOUT);

    StepVerifier.create(seed.call().api(AnnotationService.class).serviceDiscoveryEventTypes())
        .assertNext(type -> assertEquals(ENDPOINT_ADDED, type))
        .assertNext(type -> assertEquals(ENDPOINT_ADDED, type))
        .thenCancel()
        .verify(TIMEOUT);

    Mono.whenDelayError(cluster.stream().map(Microservices::shutdown).toArray(Mono[]::new))
        .block(TIMEOUT);
  }

  private ServiceDiscoveryFactory defServiceDiscovery(MetadataCodec metadataCodec) {
    return endpoint ->
        new ScalecubeServiceDiscovery(endpoint).options(cfg -> cfg.metadataCodec(metadataCodec));
  }

  private static ServiceDiscoveryFactory defServiceDiscovery(
      Address address, MetadataCodec metadataCodec) {
    return endpoint ->
        new ScalecubeServiceDiscovery(endpoint)
            .options(cfg -> cfg.metadataCodec(metadataCodec))
            .membership(cfg -> cfg.seedMembers(address));
  }
}
