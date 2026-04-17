package server;

import java.util.Arrays;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.r2dbc.OptionsCapableConnectionFactory;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import jakarta.annotation.PostConstruct;

@SpringBootApplication
@EnableR2dbcRepositories(entityOperationsRef = "r2dbcEntityTemplate")
@EnableConfigurationProperties(R2dbcProperties.class)
public class Main {
	
    @Autowired
    private ApplicationContext ctx;
    
    @PostConstruct
    public void printR2dbcTemplates() {
    	System.out.println("Start searching\n");
        Map<String, R2dbcEntityTemplate> beans = ctx.getBeansOfType(R2dbcEntityTemplate.class);
        System.out.println("Found R2dbcEntityTemplate beans: " + beans.keySet());
        beans.forEach((name, bean) -> System.out.println(name + " -> " + bean));
        System.out.println("Finished searching\n");
    }
	
	@Bean
	@Primary
    public R2dbcEntityTemplate r2dbcEntityTemplate(ConnectionFactory connectionFactory) {
        return new R2dbcEntityTemplate(connectionFactory);
    }
	
	@Value("${spring.r2dbc.username:postgres}")
	private String user;
	
	@Value("${spring.r2dbc.password:1417}")
	private String pwd;
	
	@Bean
	@Primary
    public ConnectionFactory connectionFactory () {
		return ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, "localhost")
                .option(ConnectionFactoryOptions.PORT, 5432)
                .option(ConnectionFactoryOptions.DATABASE, "filedb")
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
