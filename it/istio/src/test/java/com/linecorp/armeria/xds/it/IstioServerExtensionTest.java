package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerConfigurator;

/**
 * Verifies that {@link IstioServerExtension} correctly deploys a server workload into the
 * K3s cluster using a {@link ServerConfigurator} class, and that the server is reachable
 * from a test pod running inside the same cluster.
 */
class IstioServerExtensionTest {

    private static final Logger logger = LoggerFactory.getLogger(IstioServerExtensionTest.class);

    @RegisterExtension
    @Order(1)
    static IstioClusterExtension cluster = new IstioClusterExtension();

    @RegisterExtension
    @Order(2)
    static IstioServerExtension echo = new IstioServerExtension(
            "echo-server", 8080, EchoConfigurator.class);

    @IstioPodTest
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void serverIsReachable() {
        final WebClient client = WebClient.of("http://" + echo.serviceName() + ":" + echo.port());
        final AggregatedHttpResponse response = client.get("/echo").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("hello");
    }

    @IstioPodTest
    void envoyStatsAreReachable() {
        final WebClient envoyAdmin = WebClient.of("http://localhost:15000");
        final AggregatedHttpResponse response = envoyAdmin.get("/stats").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).contains("server.state");
    }

    @IstioPodTest
    void envoyConfigDump() {
        final WebClient envoyAdmin = WebClient.of("http://localhost:15000");
        final AggregatedHttpResponse response = envoyAdmin.get("/config_dump").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        logger.info("Envoy config dump: {}", response.contentUtf8());
    }

    public static class EchoConfigurator implements ServerConfigurator {
        @Override
        public void reconfigure(ServerBuilder sb) {
            sb.service("/echo", (ctx, req) -> HttpResponse.of("hello"));
        }
    }
}
