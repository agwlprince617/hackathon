package launchday;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import launchday.api.ReservationApi;
import launchday.client.AuthorityClient;
import launchday.repository.ItemRepository;
import launchday.repository.ReservationRepository;
import launchday.service.AuthorityMonitor;
import launchday.service.ItemService;
import launchday.service.ReservationService;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

public final class App {
    private App() {}

    public static void main(String[] args) throws Exception {
        HikariDataSource dataSource = dataSource();
        String authorityUrl = env("AUTHORITY_URL", "http://127.0.0.1:9000").replaceAll("/$", "");
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        AuthorityClient authorityClient = new AuthorityClient(httpClient, authorityUrl);
        ReservationRepository reservationRepository = new ReservationRepository(dataSource);
        ItemRepository itemRepository = new ItemRepository(dataSource);
        AuthorityMonitor authorityMonitor = new AuthorityMonitor(
                httpClient,
                authorityUrl,
                Long.parseLong(env("AUTHORITY_BREAKER_POLL_MS", "1000")),
                Integer.parseInt(env("AUTHORITY_BREAKER_HEALTHY_CHECKS", "3")));
        ReservationService reservationService = new ReservationService(
                dataSource,
                authorityClient,
                authorityMonitor,
                reservationRepository,
                itemRepository,
                Integer.parseInt(env("STANDIN_MAX_PER_ITEM", "10")));
        ItemService itemService = new ItemService(authorityClient, authorityMonitor, itemRepository);

        authorityMonitor.setRecoveryAction(reservationService::reconcileAfterRecovery);
        if (reservationRepository.hasPendingReservations()) authorityMonitor.forceStandin();
        authorityMonitor.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            authorityMonitor.close();
            dataSource.close();
        }));

        int port = Integer.parseInt(env("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        new ReservationApi(
                authorityMonitor, reservationService, reservationRepository, itemService)
                .register(server);
        server.start();
        System.out.println("reservation api (java student) :" + port);
    }

    private static HikariDataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env("DATABASE_URL", "jdbc:postgresql://localhost:5432/student"));
        config.setUsername(env("DATABASE_USER", "launchday"));
        config.setPassword(env("DATABASE_PASSWORD", "launchday"));
        config.setMaximumPoolSize(Integer.parseInt(env("DATABASE_POOL_SIZE", "24")));
        config.setMinimumIdle(4);
        config.setConnectionTimeout(5_000);
        config.setPoolName("reservation-db");
        return new HikariDataSource(config);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }
}