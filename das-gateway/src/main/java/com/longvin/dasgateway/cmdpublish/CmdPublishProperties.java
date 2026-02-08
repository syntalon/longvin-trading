package com.longvin.dasgateway.cmdpublish;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "das.cmd.publish")
public record CmdPublishProperties(
        boolean enabled
) {
}
