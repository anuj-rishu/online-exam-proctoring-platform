package in.srminsider.labs.config;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import in.srminsider.labs.proctoring.JwtHandshakeInterceptor;
import in.srminsider.labs.proctoring.SignalingHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final String WS_BUFFER_BYTES = String.valueOf(512 * 1024);

    private final SignalingHandler signalingHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    public WebSocketConfig(SignalingHandler signalingHandler, JwtHandshakeInterceptor jwtHandshakeInterceptor) {
        this.signalingHandler = signalingHandler;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(signalingHandler, "/ws-signaling")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins("*");
    }

    
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> webSocketBufferCustomizer() {
        return factory -> factory.addContextCustomizers(context -> {
            context.addParameter("org.apache.tomcat.websocket.textBufferSize", WS_BUFFER_BYTES);
            context.addParameter("org.apache.tomcat.websocket.binaryBufferSize", WS_BUFFER_BYTES);
        });
    }
}
