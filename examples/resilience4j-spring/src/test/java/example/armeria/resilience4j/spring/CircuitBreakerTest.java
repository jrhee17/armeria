package example.armeria.resilience4j.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerMapping;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.FailFastException;
import com.linecorp.armeria.client.retrofit2.ArmeriaRetrofit;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.resilience4j.circuitbreaker.Resilience4jCircuitBreaker;
import com.linecorp.armeria.server.Server;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retrofit.CircuitBreakerCallAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import retrofit2.HttpException;
import retrofit2.http.GET;

@SpringBootTest(classes = Main.class)
public class CircuitBreakerTest {

    @Inject
    CircuitBreakerRegistry circuitBreakerRegistry;

    @Inject
    private Server server;

    @Inject
    private SimpleMeterRegistry meterRegistry;

    @Test
    void circuitBreakerRegistry() {
        assertThat(circuitBreakerRegistry).isNotNull();
        assertThat(circuitBreakerRegistry.circuitBreaker("backendA")
                                         .getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(3);
        assertThat(circuitBreakerRegistry.circuitBreaker("backendA")
                                         .getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(60);
        assertThat(circuitBreakerRegistry.circuitBreaker("randomA", "defaultA")
                                         .getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(3);
        assertThat(circuitBreakerRegistry.circuitBreaker("randomB", "defaultB")
                                         .getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(5);
    }

    @Test
    void testWebClient() {
        final CircuitBreakerMapping mapping = CircuitBreakerMapping
                .builder().perHost().perMethod().perPath()
                .build((host, method, path) -> {
                    final String key = String.format("%s#%s#%s", host, method, path);
                    final CircuitBreaker r4jCircuitBreaker =
                            circuitBreakerRegistry.circuitBreaker(key, "defaultA");
                    return Resilience4jCircuitBreaker.of(r4jCircuitBreaker);
                });
        final WebClient client = WebClient.builder("http://localhost:" + server.activeLocalPort())
                .decorator(CircuitBreakerClient.newDecorator(mapping, CircuitBreakerRule.onStatus(
                        HttpStatus.INTERNAL_SERVER_ERROR)))
                                          .build();
        final int windowSize = circuitBreakerRegistry.getConfiguration("defaultA")
                                                     .orElseThrow().getSlidingWindowSize();

        for (int i = 0; i < windowSize; i++) {
            assertThat(client.blocking().get("500-1").status().code()).isEqualTo(500);
        }
        assertThatThrownBy(() -> client.blocking().get("500-1")).isInstanceOf(FailFastException.class);

        // a separate circuitbreaker is instantiated for different paths
        for (int i = 0; i < windowSize; i++) {
            assertThat(client.blocking().get("500-2").status().code()).isEqualTo(500);
        }
        assertThatThrownBy(() -> client.blocking().get("500-2")).isInstanceOf(FailFastException.class);
    }

    interface Service {

        @GET("/500-1")
        CompletableFuture<Void> serverError1();
    }

    @Test
    void testRetrofitClient() {
        final WebClient client = WebClient.of("http://localhost:" + server.activeLocalPort());
        final CircuitBreaker r4jCircuitBreaker =
                circuitBreakerRegistry.circuitBreaker("retrofit", "defaultA");
        final Service service = ArmeriaRetrofit
                .builder(client)
                .addCallAdapterFactory(CircuitBreakerCallAdapter.of(r4jCircuitBreaker))
                .build()
                .create(Service.class);

        final int windowSize = circuitBreakerRegistry.getConfiguration("defaultA")
                                                     .orElseThrow().getSlidingWindowSize();
        for (int i = 0; i < windowSize; i++) {
            assertThatThrownBy(() -> service.serverError1().join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(HttpException.class);
        }
        assertThatThrownBy(() -> service.serverError1().join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void testMicrometerIntegration() {
        final CircuitBreaker r4jCircuitBreaker =
                circuitBreakerRegistry.circuitBreaker("micrometer", "defaultA");
        r4jCircuitBreaker.onError(1, TimeUnit.SECONDS, new RuntimeException());
        r4jCircuitBreaker.onError(1, TimeUnit.SECONDS, new RuntimeException());
        r4jCircuitBreaker.onSuccess(1, TimeUnit.SECONDS);
        final String failed = "resilience4j.circuitbreaker.calls#count" +
                              "{kind=failed,my-tag=custom-tag,name=micrometer}";
        final String successful = "resilience4j.circuitbreaker.calls#count" +
                                  "{kind=successful,my-tag=custom-tag,name=micrometer}";
        assertThat(MoreMeters.measureAll(meterRegistry))
                .containsAnyOf(entry(successful, 1.0), entry(failed, 2.0));
    }

    @Test
    void testActuator() {
        final CircuitBreaker r4jCircuitBreaker =
                circuitBreakerRegistry.circuitBreaker("actuator");
        r4jCircuitBreaker.onError(1, TimeUnit.SECONDS, new RuntimeException());
        r4jCircuitBreaker.onError(1, TimeUnit.SECONDS, new RuntimeException());

        final WebClient client = WebClient.of("http://localhost:" + server.activeLocalPort());

        // check metrics for circuitbreaker is registered
        String contentUtf8 = client.blocking().get("/internal/metrics").contentUtf8();
        String name = "resilience4j_circuitbreaker_state{my_tag=\"custom-tag\",name=\"actuator\"";
        assertThat(contentUtf8).contains(name);

        // check health indicator includes circuitbreaker information
        contentUtf8 = client.blocking().get("/actuator/health").contentUtf8();
        name = "circuitBreakers";
        assertThat(contentUtf8).contains(name);
    }
}