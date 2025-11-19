package com.longvin.trading.fix;

import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FixConfig { // Renamed from FixConfiguration to FixConfig

    @Bean
    public DocumentBuilderFactory documentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "all");
        return factory;
    }
}
