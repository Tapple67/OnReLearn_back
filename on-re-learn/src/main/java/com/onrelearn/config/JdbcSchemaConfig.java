package com.onrelearn.config;

import java.util.Arrays;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Configuration
public class JdbcSchemaConfig {
    @Bean
    static BeanFactoryPostProcessor schemaBeforeJpa() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition("entityManagerFactory")) return;
            var definition = beanFactory.getBeanDefinition("entityManagerFactory");
            String[] current = definition.getDependsOn();
            definition.setDependsOn(Stream.concat(
                current == null ? Stream.empty() : Arrays.stream(current),
                Stream.of("onrelearnSchemaInitializer")
            ).distinct().toArray(String[]::new));
        };
    }

    @Bean
    InitializingBean onrelearnSchemaInitializer(DataSource dataSource) {
        return () -> new ResourceDatabasePopulator(
            new ClassPathResource("sql/onrelearn.sql")
        ).execute(dataSource);
    }
}
