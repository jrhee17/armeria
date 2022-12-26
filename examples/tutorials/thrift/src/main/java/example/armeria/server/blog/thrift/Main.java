package example.armeria.server.blog.thrift;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.thrift.THttpService;

import example.armeria.blog.thrift.BlogService;
import example.armeria.blog.thrift.CreateBlogPostRequest;

public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        final Server server = newServer(8080);

        server.closeOnJvmShutdown().thenRun(() -> {
            logger.info("Server has been stopped.");
        });

        server.start().join();

        logger.info("Server has been started. Serving DocService at http://127.0.0.1:{}/docs",
                    server.activeLocalPort());
    }

    private static Server newServer(int port) throws Exception {
        final THttpService tHttpService =
                THttpService.builder()
                            .addService(new BlogServiceImpl())
                            .build();

        final CreateBlogPostRequest request = new CreateBlogPostRequest()
                .setTitle("My first blog")
                .setContent("Hello Armeria!");
        final DocService docService = DocService
                .builder()
                .exampleRequests(ImmutableList.of(new BlogService.createBlogPost_args(request)))
                .build();
        return Server.builder()
                     .http(port)
                     .service("/thrift", tHttpService)
                     // You can access the documentation service at http://127.0.0.1:8080/docs.
                     // See https://armeria.dev/docs/server-docservice for more information.
                     .serviceUnder("/docs", docService)
                     .build();
    }

    private Main() {
    }
}
