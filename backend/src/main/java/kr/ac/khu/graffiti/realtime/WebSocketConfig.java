package kr.ac.khu.graffiti.realtime;

import kr.ac.khu.graffiti.config.GraffitiProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over native WebSocket at {@code /ws} (API.md §6). The handshake must come from an allowed
 * Origin (SEC-03); its HTTP session decides the connection's user. One in-memory broker (D-01).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final String HEARTBEAT_SCHEDULER = "wsHeartbeatScheduler";

    private final GraffitiProperties props;
    private final RealtimeInterceptor interceptor;
    private final SubscribeReceipts receipts;
    private final TaskScheduler heartbeatScheduler;

    WebSocketConfig(GraffitiProperties props, RealtimeInterceptor interceptor, SubscribeReceipts receipts,
            @Qualifier(HEARTBEAT_SCHEDULER) TaskScheduler heartbeatScheduler) {
        this.props = props;
        this.interceptor = interceptor;
        this.receipts = receipts;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(props.web().allowedOrigins().toArray(String[]::new));
        registry.setErrorHandler(new StompErrorHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long heartbeat = props.realtime().heartbeat().toMillis();
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {heartbeat, heartbeat})
                .setTaskScheduler(heartbeatScheduler);
        // Events reach each connection in publish order (published before removed, INV-09 merge).
        registry.setPreservePublishOrder(true);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(interceptor, receipts);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(props.realtime().inboundMessageMaxBytes());
    }
}
