package com.longvin.dasgateway.ui;

import com.longvin.dasgateway.cmd.CmdClient;
import com.longvin.dasgateway.cmd.CmdEventListener;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.swing.SwingUtilities;

@Component
public class GuiLauncher implements ApplicationRunner {

    private final CmdClient client;

    public GuiLauncher(CmdClient client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        DasGatewayFrame frame = new DasGatewayFrame();
        frame.setConnected(client.isConnected());

        client.addListener(new CmdEventListener() {
            @Override
            public void onConnected() {
                frame.setConnected(true);
                frame.appendLine("[system] connected");
            }

            @Override
            public void onDisconnected() {
                frame.setConnected(false);
                frame.appendLine("[system] disconnected");
            }

            @Override
            public void onInboundLine(String line) {
                frame.appendLine("DAS<- " + line);
            }

            @Override
            public void onOutboundLine(String line) {
                frame.appendLine("DAS-> " + line);
            }
        });

        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }
}
