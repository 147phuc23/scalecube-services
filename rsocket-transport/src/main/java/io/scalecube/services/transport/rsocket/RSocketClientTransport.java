package io.scalecube.services.transport.rsocket;

import io.netty.channel.EventLoopGroup;
import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.util.ByteBufPayload;
import io.scalecube.services.codec.ServiceMessageCodec;
import io.scalecube.services.transport.api.ClientChannel;
import io.scalecube.services.transport.api.ClientTransport;
import io.scalecube.transport.Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.tcp.TcpClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RSocketClientTransport implements ClientTransport {

  private static final Logger LOGGER = LoggerFactory.getLogger(RSocketClientTransport.class);

  private final ThreadLocal<Map<Address, Mono<RSocket>>> rSockets = ThreadLocal.withInitial(ConcurrentHashMap::new);

  private final ServiceMessageCodec codec;
  private final EventLoopGroup eventLoopGroup;

  public RSocketClientTransport(ServiceMessageCodec codec, EventLoopGroup eventLoopGroup) {
    this.codec = codec;
    this.eventLoopGroup = eventLoopGroup;
  }

  @Override
  public ClientChannel create(Address address) {
    final Map<Address, Mono<RSocket>> monoMap = rSockets.get(); // keep reference for threadsafety
    Mono<RSocket> rSocket = monoMap.computeIfAbsent(address, address1 -> connect(address1, monoMap));
    return new RSocketServiceClientAdapter(rSocket, codec);
  }

  private Mono<RSocket> connect(Address address, Map<Address, Mono<RSocket>> monoMap) {
    TcpClient tcpClient =
        TcpClient.create(options -> options.disablePool()
            .eventLoopGroup(eventLoopGroup)
            .host(address.host())
            .port(address.port()));

    TcpClientTransport tcpClientTransport =
        TcpClientTransport.create(tcpClient);

    Mono<RSocket> rSocketMono = RSocketFactory.connect()
        .frameDecoder(frame -> ByteBufPayload.create(frame.sliceData().retain(), frame.sliceMetadata().retain()))
        .transport(tcpClientTransport)
        .start();

    return rSocketMono
        .doOnSuccess(rSocket -> {
          LOGGER.info("Connected successfully on {}", address);
          // setup shutdown hook
          rSocket.onClose().doOnTerminate(() -> {
            monoMap.remove(address);
            LOGGER.info("Connection closed on {} and removed from the pool", address);
          }).subscribe();
        })
        .doOnError(throwable -> {
          LOGGER.warn("Connect failed on {}, cause: {}", address, throwable);
          monoMap.remove(address);
        })
        .cache();
  }
}