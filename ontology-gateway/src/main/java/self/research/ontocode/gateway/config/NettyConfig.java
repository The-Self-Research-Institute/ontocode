package self.research.ontocode.gateway.config;

import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NettyConfig {

    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> nettyCustomizer() {
        return factory -> {

            factory.addServerCustomizers(httpServer ->
                httpServer.httpRequestDecoder(spec -> {

                    spec.maxInitialLineLength(16384);
                    spec.maxHeaderSize(32768);
                    spec.maxChunkSize(16384);
                    spec.validateHeaders(false);
                    return spec;
                })
            );
        };
    }
}
