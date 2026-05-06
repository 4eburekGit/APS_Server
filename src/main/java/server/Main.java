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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;

@SpringBootApplication
@EnableR2dbcRepositories(entityOperationsRef = "r2dbcEntityTemplate")
@EnableConfigurationProperties(R2dbcProperties.class)
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
	
	@Bean
	@Primary
    public ConnectionFactory connectionFactory () {
		return ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, driver)
                .option(ConnectionFactoryOptions.HOST, host)
                .option(ConnectionFactoryOptions.PORT, port)
                .option(ConnectionFactoryOptions.DATABASE, database)
                .option(ConnectionFactoryOptions.USER, user)
                .option(ConnectionFactoryOptions.PASSWORD, pwd)
                .build());
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
