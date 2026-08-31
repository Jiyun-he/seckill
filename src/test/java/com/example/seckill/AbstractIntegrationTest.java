package com.example.seckill;

import com.example.seckill.service.SeckillService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 集成测试基类：以 Testcontainers 拉起隔离的 MySQL / Redis / RabbitMQ，并保证每个用例
 * 从一致的干净状态开始。
 *
 * <p>容器在静态初始化块中启动一次、由 JVM 关闭钩子停止，整个测试套件共享一套中间件，
 * 生命周期完全独立于 JUnit 扩展与 Spring 上下文缓存，避免套件中途容器被提前停止。</p>
 *
 * <p>本机 docker.io 不可达，因此显式指定本地已有镜像 tag（mysql:8.0 / redis:6 /
 * rabbitmq:management），并在 testcontainers.properties 中禁用 Ryuk。</p>
 *
 * @author jiyunhe
 */
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("seckill_db")
            .withUsername("root")
            .withPassword("123456")
            .withInitScript("db/init.sql");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:6")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:management")
            // withUser 会通过 configure() 设置 RABBITMQ_DEFAULT_USER/PASS 真正建用户；
            // RabbitMQ 4.x 下其 rabbitmqadmin 校验命令会报错，但属冗余噪音，用户已由 env 创建，可忽略
            .withUser("seckill", "seckill123");

    static {
        MYSQL.start();
        REDIS.start();
        RABBITMQ.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            RABBITMQ.stop();
            REDIS.stop();
            MYSQL.stop();
        }));
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                        + "/seckill_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    @Autowired
    protected StringRedisTemplate stringRedisTemplate;
    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected SeckillService seckillService;

    /**
     * 每个用例前：清空 Redis、重建数据库 schema 与种子数据、重新预热秒杀库存到 Redis，
     * 保证用例之间互不污染。
     */
    @BeforeEach
    void resetState() throws Exception {
        try (var redisConn = stringRedisTemplate.getConnectionFactory().getConnection()) {
            redisConn.serverCommands().flushAll();
        }
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/init.sql"));
        }
        seckillService.loadSeckillStockToRedis();
    }
}
