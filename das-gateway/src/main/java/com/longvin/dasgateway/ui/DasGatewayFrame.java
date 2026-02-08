package com.longvin.dasgateway.ui;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;

public class DasGatewayFrame extends JFrame {

    private final JLabel statusLabel = new JLabel("Disconnected");
    private final JTextArea logArea = new JTextArea();

    public DasGatewayFrame() {
        super("DAS Gateway");

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 600);

        logArea.setEditable(false);

        JPanel top = new JPanel(new BorderLayout());
        top.add(statusLabel, BorderLayout.WEST);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(logArea), BorderLayout.CENTER);
    }

    public void setConnected(boolean connected) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(connected ? "Connected" : "Disconnected"));
    }

    public void appendLine(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(line);
            logArea.append("\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
}
