package ca.uhn.fhir.jpa.starter.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfig {
  @Bean(name = "aggExecutor")
  public ThreadPoolTaskExecutor aggExecutor() {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(4);
    ex.setMaxPoolSize(8);
    ex.setQueueCapacity(100);
    ex.setThreadNamePrefix("agg-");
    ex.initialize();
    return ex;
  }
}
