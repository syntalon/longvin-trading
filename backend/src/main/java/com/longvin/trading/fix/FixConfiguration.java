package com.longvin.trading.fix;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FixClientProperties.class)
public class FixConfiguration {
}
