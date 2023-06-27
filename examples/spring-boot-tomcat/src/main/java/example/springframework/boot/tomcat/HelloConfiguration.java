package example.springframework.boot.tomcat;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.UpgradeProtocol;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatContextCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.server.tomcat.TomcatService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

import jakarta.servlet.Servlet;

/**
 * Configures an Armeria {@link Server} to redirect the incoming requests to the Tomcat instance provided by
 * Spring Boot. It also sets up a {@link HealthChecker} so that it works well with a load balancer.
 */
@Configuration
public class HelloConfiguration {

    /**
     * Extracts a Tomcat {@link Connector} from Spring webapp context.
     */
    public static Connector getConnector(ServletWebServerApplicationContext applicationContext) {
        final TomcatWebServer container = (TomcatWebServer) applicationContext.getWebServer();

        // Start the container to make sure all connectors are available.
//        container.start();
        return container.getTomcat().getConnector();
    }

    /**
     * Returns a new {@link HealthChecker} that marks the server as unhealthy when Tomcat becomes unavailable.
     */
    @Bean
    public HealthChecker tomcatConnectorHealthChecker(ServletWebServerApplicationContext applicationContext) {
        final Connector connector = getConnector(applicationContext);
        return () -> connector.getState().isAvailable();
    }

    /**
     * Returns a new {@link TomcatService} that redirects the incoming requests to the Tomcat instance
     * provided by Spring Boot.
     */
    @Bean
    public TomcatService tomcatService(ServletWebServerApplicationContext applicationContext) {
        return TomcatService.of(getConnector(applicationContext));
    }

    /**
     * Returns a new {@link ArmeriaServerConfigurator} that is responsible for configuring a {@link Server}
     * using the given {@link ServerBuilder}.
     */
    @Bean
    public ArmeriaServerConfigurator armeriaServiceInitializer(TomcatService tomcatService) {
        return sb -> sb.serviceUnder("/", tomcatService);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ Servlet.class, Tomcat.class, UpgradeProtocol.class })
    @ConditionalOnMissingBean(value = ServletWebServerFactory.class, search = SearchStrategy.CURRENT)
    static class EmbeddedTomcat {

        @Bean
        TomcatServletWebServerFactory tomcatServletWebServerFactory(
                ObjectProvider<TomcatConnectorCustomizer> connectorCustomizers,
                ObjectProvider<TomcatContextCustomizer> contextCustomizers,
                ObjectProvider<TomcatProtocolHandlerCustomizer<?>> protocolHandlerCustomizers) {
            NoPortTomcatServletWebServerFactory factory = new NoPortTomcatServletWebServerFactory();
            factory.doSetPort();
            factory.getTomcatConnectorCustomizers().addAll(connectorCustomizers.orderedStream().toList());
            factory.getTomcatContextCustomizers().addAll(contextCustomizers.orderedStream().toList());
            factory.getTomcatProtocolHandlerCustomizers().addAll(
                    protocolHandlerCustomizers.orderedStream().toList());
            return factory;
        }

        private static class NoPortTomcatServletWebServerFactory extends TomcatServletWebServerFactory {
            @Override
            public void setPort(int port) {
                // no-op
            }

            void doSetPort() {
                super.setPort(-1);
            }

        }
    }
}
