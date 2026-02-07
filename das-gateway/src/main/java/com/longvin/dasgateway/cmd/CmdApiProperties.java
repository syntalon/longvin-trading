package com.longvin.dasgateway.cmd;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "das.cmd")
public record CmdApiProperties(
        String host,
        int port,
        String trader,
        String password,
        String account,
        boolean watch
) {
}
