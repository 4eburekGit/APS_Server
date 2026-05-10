package server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;

import java.time.Duration;

@SpringBootApplication
@EnableR2dbcRepositories(entityOperationsRef = "r2dbcEntityTemplate")
@EnableConfigurationProperties(R2dbcProperties.class)
@EnableScheduling
public class Main {
	
	@Bean
	@Primary
    public R2dbcEntityTemplate r2dbcEntityTemplate(ConnectionFactory connectionFactory) {
        return new R2dbcEntityTemplate(connectionFactory);
    }
	
	@Value("${spring.r2dbc.driver:postgresql}")
	private String driver;
	
	@Value("${spring.r2dbc.host:localhost}")
	private String host;
	
	@Value("${spring.r2dbc.port:5432}")
	private Integer port;
	
	@Value("${spring.r2dbc.database:filedb}")
	private String database;
	
	@Value("${spring.r2dbc.username:postgres}")
	private String user;
	
	@Value("${spring.r2dbc.password:1417}")
	private String pwd;
	
	// Pool config — overridable via env / application.yml.
	// Defaults are sane for a single-node prototype under load-test
	// (target ~50 concurrent R2DBC operations, well below Postgres
	// max_connections=200 set in docker-compose).
	@Value("${spring.r2dbc.pool.initial-size:10}")
	private int poolInitialSize;

	@Value("${spring.r2dbc.pool.max-size:50}")
	private int poolMaxSize;

	@Value("${spring.r2dbc.pool.max-acquire-time:30s}")
	private Duration poolMaxAcquireTime;

	@Value("${spring.r2dbc.pool.max-idle-time:15m}")
	private Duration poolMaxIdleTime;

	@Value("${spring.r2dbc.pool.max-life-time:30m}")
	private Duration poolMaxLifeTime;

	@Bean
	@Primary
    public ConnectionFactory connectionFactory () {
		ConnectionFactory base = ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, driver)
                .option(ConnectionFactoryOptions.HOST, host)
                .option(ConnectionFactoryOptions.PORT, port)
                .option(ConnectionFactoryOptions.DATABASE, database)
                .option(ConnectionFactoryOptions.USER, user)
                .option(ConnectionFactoryOptions.PASSWORD, pwd)
                .build());

		ConnectionPoolConfiguration poolConfig = ConnectionPoolConfiguration.builder(base)
				.initialSize(poolInitialSize)
				.maxSize(poolMaxSize)
				.maxAcquireTime(poolMaxAcquireTime)
				.maxIdleTime(poolMaxIdleTime)
				.maxLifeTime(poolMaxLifeTime)
				.build();

		return new ConnectionPool(poolConfig);
    }
	
	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(Main.class, args);
		// printR2dbcTemplates();
/*
		String[] beanNames = context.getBeanDefinitionNames();
        System.out.println("BEANS REGISTERED\n\n");
        Arrays.sort(beanNames);
        for (String name : beanNames) {
            Object bean = context.getBean(name);
            System.out.println(name + " -> " + bean.getClass().getName()+'\n');
        }
*/
	}
}
