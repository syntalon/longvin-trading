package com.longvin.simulator.gui;

import com.longvin.simulator.config.SimulatorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import quickfix.Session;
import quickfix.SessionID;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Swing GUI for sending drop copy ExecutionReport messages.
 * This GUI allows users to manually send ExecutionReport messages through the simulator's initiator session.
 */
@Slf4j
@Component
public class DropCopyMessageGui implements CommandLineRunner {

    private final DropCopyMessageSender messageSender;
    private final SimulatorProperties properties;
    private JFrame frame;
    private JTextField orderIdField;
    private JTextField execIdField;
    private JTextField clOrdIdField;
    private JTextField origClOrdIdField;
    private JTextField symbolField;
    private JComboBox<DropCopyMessageSender.ExecutionReportData.ExecType> execTypeCombo;
    private JComboBox<DropCopyMessageSender.ExecutionReportData.OrdStatus> ordStatusCombo;
    private JComboBox<DropCopyMessageSender.ExecutionReportData.Side> sideCombo;
    private JComboBox<DropCopyMessageSender.ExecutionReportData.OrdType> ordTypeCombo;
    private JTextField orderQtyField;
    private JTextField priceField;
    private JTextField lastQtyField;
    private JTextField leavesQtyField;
    private JTextField cumQtyField;
    private JTextField avgPxField;
    private JTextField stopPxField;
    private JComboBox<DropCopyMessageSender.ExecutionReportData.TimeInForce> timeInForceCombo;
    private JTextField accountField;
    private JTextField textField;
    private JTextField quoteReqIdField;
    private JTextField exDestinationField;
    private JTextArea statusArea;
    private JComboBox<String> fillSampleTypeCombo;
    private JLabel sessionStatusLabel;
    private Timer sessionStatusTimer;

    @Autowired
    public DropCopyMessageGui(DropCopyMessageSender messageSender, SimulatorProperties properties) {
        this.messageSender = messageSender;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        if (!properties.isGuiEnabled()) {
            log.info("GUI is disabled via configuration. Set simulator.gui-enabled=true to enable.");
            return;
        }

        // Check if system supports GUI (not headless)
        if (GraphicsEnvironment.isHeadless()) {
            log.warn("GUI cannot be launched: system is running in headless mode. " +
                    "The simulator will continue running without GUI. " +
                    "To use the GUI, ensure you have a display available.");
            return;
        }

        // Launch GUI on EDT
        log.info("Launching Drop Copy Message GUI...");
        SwingUtilities.invokeLater(() -> {
            try {
                createAndShowGUI();
                log.info("Drop Copy Message GUI launched successfully");
            } catch (Exception e) {
                log.error("Failed to launch GUI", e);
            }
        });
    }

    private void createAndShowGUI() {
        // Set system look and feel for better appearance
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.debug("Could not set system look and feel: {}", e.getMessage());
        }

        frame = new JFrame("FIX Simulator - Drop Copy Message Sender");
        // Use EXIT_ON_CLOSE only if this is the main window, otherwise DISPOSE_ON_CLOSE
        // Since Spring Boot keeps the app running, DISPOSE_ON_CLOSE is fine
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(1000, 750);
        frame.setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Form panel
        JPanel formPanel = new JPanel(new GridBagLayout());
        // Border will be set on the scroll pane instead
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0.0; // Labels don't expand

        int row = 0;

        // Order ID
        addLabelAndField(formPanel, gbc, row++, "Order ID:", orderIdField = new JTextField(50));
        
        // Exec ID
        addLabelAndField(formPanel, gbc, row++, "Exec ID:", execIdField = new JTextField(50));
        
        // ClOrdID
        addLabelAndField(formPanel, gbc, row++, "ClOrdID:", clOrdIdField = new JTextField(30));
        
        // OrigClOrdID
        addLabelAndField(formPanel, gbc, row++, "OrigClOrdID:", origClOrdIdField = new JTextField(30));
        
        // Symbol (required)
        addLabelAndField(formPanel, gbc, row++, "Symbol *:", symbolField = new JTextField(30));
        
        // ExecType
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("ExecType *:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        execTypeCombo = new JComboBox<>(DropCopyMessageSender.ExecutionReportData.ExecType.values());
        execTypeCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.ExecType.NEW);
        formPanel.add(execTypeCombo, gbc);
        row++;
        
        // OrdStatus
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("OrdStatus *:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        ordStatusCombo = new JComboBox<>(DropCopyMessageSender.ExecutionReportData.OrdStatus.values());
        ordStatusCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.OrdStatus.FILLED);
        formPanel.add(ordStatusCombo, gbc);
        row++;
        
        // Side (required)
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("Side *:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        sideCombo = new JComboBox<>(DropCopyMessageSender.ExecutionReportData.Side.values());
        sideCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.Side.BUY);
        formPanel.add(sideCombo, gbc);
        row++;
        
        // OrdType
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("OrdType:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        ordTypeCombo = new JComboBox<>(DropCopyMessageSender.ExecutionReportData.OrdType.values());
        ordTypeCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.OrdType.MARKET);
        formPanel.add(ordTypeCombo, gbc);
        row++;
        
        // OrderQty
        addLabelAndField(formPanel, gbc, row++, "OrderQty:", orderQtyField = new JTextField(30));
        
        // Price
        addLabelAndField(formPanel, gbc, row++, "Price:", priceField = new JTextField(30));
        
        // LastQty
        addLabelAndField(formPanel, gbc, row++, "LastQty:", lastQtyField = new JTextField(30));
        
        // LeavesQty
        addLabelAndField(formPanel, gbc, row++, "LeavesQty:", leavesQtyField = new JTextField(30));
        
        // CumQty
        addLabelAndField(formPanel, gbc, row++, "CumQty:", cumQtyField = new JTextField(30));
        
        // AvgPx
        addLabelAndField(formPanel, gbc, row++, "AvgPx:", avgPxField = new JTextField(30));
        
        // StopPx
        addLabelAndField(formPanel, gbc, row++, "StopPx:", stopPxField = new JTextField(30));
        
        // TimeInForce
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("TimeInForce:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        timeInForceCombo = new JComboBox<>(DropCopyMessageSender.ExecutionReportData.TimeInForce.values());
        timeInForceCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.TimeInForce.DAY);
        formPanel.add(timeInForceCombo, gbc);
        row++;
        
        // Account
        addLabelAndField(formPanel, gbc, row++, "Account:", accountField = new JTextField(30));
        
        // QuoteReqID (for short locate)
        addLabelAndField(formPanel, gbc, row++, "QuoteReqID:", quoteReqIdField = new JTextField(30));
        
        // LastMkt (tag 30) - route/market field for ExecutionReport
        addLabelAndField(formPanel, gbc, row++, "LastMkt (Route):", exDestinationField = new JTextField(30));
        
        // Text
        addLabelAndField(formPanel, gbc, row++, "Text:", textField = new JTextField(30));

        // Buttons panel
        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton sendButton = new JButton("Send ExecutionReport");
        sendButton.addActionListener(new SendButtonListener());
        buttonPanel.add(sendButton);
        
        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> clearFields());
        buttonPanel.add(clearButton);
        
        JButton reconnectButton = new JButton("Reconnect Session");
        reconnectButton.addActionListener(e -> reconnectSession());
        reconnectButton.setToolTipText("Disconnect and reconnect to reset sequence numbers");
        buttonPanel.add(reconnectButton);
        
        // Fill Sample dropdown
        JLabel fillSampleLabel = new JLabel("Fill Sample:");
        buttonPanel.add(fillSampleLabel);
        String[] fillSampleTypes = {
            "Select sample type...",
            "Short Sell Filled",
            "Long Buy Filled",
            "Short Locate New Order"
        };
        fillSampleTypeCombo = new JComboBox<>(fillSampleTypes);
        fillSampleTypeCombo.setSelectedIndex(0);
        fillSampleTypeCombo.addActionListener(e -> {
            String selected = (String) fillSampleTypeCombo.getSelectedItem();
            if (selected != null && !selected.equals("Select sample type...")) {
                fillSampleDataByType(selected);
                fillSampleTypeCombo.setSelectedIndex(0); // Reset to default after filling
            }
        });
        buttonPanel.add(fillSampleTypeCombo);

        // Status area
        statusArea = new JTextArea(8, 50);
        statusArea.setEditable(false);
        statusArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane statusScroll = new JScrollPane(statusArea);
        statusScroll.setBorder(BorderFactory.createTitledBorder("Status"));

        // Session status
        sessionStatusLabel = new JLabel();
        updateSessionStatus(sessionStatusLabel);
        // Start timer to periodically update session status
        startSessionStatusTimer();
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(sessionStatusLabel, BorderLayout.NORTH);
        statusPanel.add(statusScroll, BorderLayout.CENTER);

        // Wrap form panel in scroll pane for vertical scrolling
        JScrollPane formScroll = new JScrollPane(formPanel);
        formScroll.setBorder(BorderFactory.createTitledBorder("ExecutionReport Fields"));
        formScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        formScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // Remove border from formPanel since scroll pane has it
        formPanel.setBorder(null);
        
        // Use JSplitPane to make both panels resizable
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, formScroll, statusPanel);
        splitPane.setResizeWeight(0.6); // Give 60% of space to form panel, 40% to status panel
        splitPane.setDividerLocation(600); // Initial divider position
        splitPane.setOneTouchExpandable(true); // Add buttons to quickly collapse/expand
        
        mainPanel.add(splitPane, BorderLayout.CENTER);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        frame.add(mainPanel);
        frame.setVisible(true);
        
        // Add window listener to clean up timer when window closes
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                stopSessionStatusTimer();
            }
        });
        
        appendStatus("GUI initialized. Ready to send ExecutionReport messages.");
    }

    private void startSessionStatusTimer() {
        // Update session status every 2 seconds
        sessionStatusTimer = new Timer("SessionStatusTimer", true);
        sessionStatusTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (sessionStatusLabel != null) {
                    SwingUtilities.invokeLater(() -> updateSessionStatus(sessionStatusLabel));
                }
            }
        }, 0, 2000); // Initial delay 0ms, repeat every 2000ms (2 seconds)
    }

    private void stopSessionStatusTimer() {
        if (sessionStatusTimer != null) {
            sessionStatusTimer.cancel();
            sessionStatusTimer = null;
        }
    }

    private void addLabelAndField(JPanel panel, GridBagConstraints gbc, int row, String label, JTextField field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0; // Labels don't expand
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0; // Text fields expand horizontally
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, gbc);
    }

    private void updateSessionStatus(JLabel label) {
        SessionID sessionID = findInitiatorSession();
        if (sessionID != null) {
            Session session = Session.lookupSession(sessionID);
            if (session != null && session.isLoggedOn()) {
                label.setText("Session: " + sessionID + " (Logged On)");
                label.setForeground(Color.GREEN);
            } else {
                label.setText("Session: " + sessionID + " (Not Logged On)");
                label.setForeground(Color.RED);
            }
        } else {
            label.setText("Session: Not Found");
            label.setForeground(Color.RED);
        }
    }

    private SessionID findInitiatorSession() {
        // Construct the SessionID based on simulator configuration
        // Initiator session: SIM-DAST -> SIM-OS111
        SessionID sessionID = new SessionID("FIX.4.2", "SIM-DAST", "SIM-OS111");
        Session session = Session.lookupSession(sessionID);
        if (session != null) {
            return sessionID;
        }
        return null;
    }

    private void clearFields() {
        orderIdField.setText("");
        execIdField.setText("");
        clOrdIdField.setText("");
        origClOrdIdField.setText("");
        symbolField.setText("");
        execTypeCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.ExecType.NEW);
        ordStatusCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.OrdStatus.FILLED);
        sideCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.Side.BUY);
        ordTypeCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.OrdType.MARKET);
        orderQtyField.setText("");
        priceField.setText("");
        lastQtyField.setText("");
        leavesQtyField.setText("");
        cumQtyField.setText("");
        avgPxField.setText("");
        stopPxField.setText("");
        timeInForceCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.TimeInForce.DAY);
        accountField.setText("");
        quoteReqIdField.setText("");
        exDestinationField.setText("");
        textField.setText("");
        appendStatus("Fields cleared.");
    }

    private void fillSampleDataByType(String sampleType) {
        switch (sampleType) {
            case "Short Sell Filled":
                fillShortSellFilledSample();
                break;
            case "Long Buy Filled":
                fillLongBuyFilledSample();
                break;
            case "Short Locate New Order":
                fillShortLocateNewOrderSample();
                break;
            default:
                appendStatus("Unknown sample type: " + sampleType);
        }
    }

    private void fillShortSellFilledSample() {
        long timestamp = System.currentTimeMillis();
        orderIdField.setText("SIM-SHORT-" + timestamp);
        execIdField.setText("EXEC-SHORT-" + timestamp);
        clOrdIdField.setText("CLORD-SHORT-" + timestamp);
        symbolField.setText("AAPL");
        execTypeCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.ExecType.FILLED);
        ordStatusCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.OrdStatus.FILLED);
        sideCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.Side.SELL_SHORT);
        ordTypeCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.OrdType.MARKET);
        orderQtyField.setText("100");
        priceField.setText("150.50");
        lastQtyField.setText("100");
        leavesQtyField.setText("0");
        cumQtyField.setText("100");
        avgPxField.setText("150.50");
        timeInForceCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.TimeInForce.DAY);
        accountField.setText("PRIMARY_ACCOUNT");
        quoteReqIdField.setText("");
        exDestinationField.setText("ARCA"); // Tag 30 (LastMkt) - route/market
        textField.setText("");
        appendStatus("Short Sell Filled sample data filled.");
    }

    private void fillLongBuyFilledSample() {
        long timestamp = System.currentTimeMillis();
        orderIdField.setText("SIM-LONG-" + timestamp);
        execIdField.setText("EXEC-LONG-" + timestamp);
        clOrdIdField.setText("CLORD-LONG-" + timestamp);
        symbolField.setText("AAPL");
        execTypeCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.ExecType.FILLED);
        ordStatusCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.OrdStatus.FILLED);
        sideCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.Side.BUY);
        ordTypeCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.OrdType.MARKET);
        orderQtyField.setText("100");
        priceField.setText("150.50");
        lastQtyField.setText("100");
        leavesQtyField.setText("0");
        cumQtyField.setText("100");
        avgPxField.setText("150.50");
        timeInForceCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.TimeInForce.DAY);
        accountField.setText("PRIMARY_ACCOUNT");
        quoteReqIdField.setText("");
        exDestinationField.setText("ARCA"); // Tag 30 (LastMkt) - route/market
        textField.setText("");
        appendStatus("Long Buy Filled sample data filled.");
    }

    private void fillShortLocateNewOrderSample() {
        long timestamp = System.currentTimeMillis();
        // Short locate orders use pattern LOC-XXX-1 for ClOrdID
        String clOrdId = "LOC-" + (timestamp % 10000) + "-1";
        clOrdIdField.setText(clOrdId);
        // OrderID is typically the numeric part from ClOrdID
        orderIdField.setText(String.valueOf(timestamp % 10000));
        execIdField.setText(clOrdId); // ExecID matches ClOrdID for locate orders
        String quoteReqId = "QUOTE-" + timestamp;
        quoteReqIdField.setText(quoteReqId);
        symbolField.setText("NBY"); // Common symbol for locate orders in production
        execTypeCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.ExecType.FILLED);
        ordStatusCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.OrdStatus.FILLED);
        sideCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.Side.BUY); // Short locate orders are BUY
        ordTypeCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.OrdType.MARKET);
        orderQtyField.setText("100");
        priceField.setText("0.01"); // Typical locate execution price
        lastQtyField.setText("100");
        leavesQtyField.setText("0"); // Filled orders have 0 leaves
        cumQtyField.setText("100");
        avgPxField.setText("0.01");
        timeInForceCombo.setSelectedItem(DropCopyMessageSender.ExecutionReportData.TimeInForce.DAY);
        accountField.setText("PRIMARY_ACCOUNT");
        exDestinationField.setText("TESTSL"); // Tag 30 (LastMkt) - locate route
        textField.setText("");
        appendStatus("Short Locate Filled sample data filled (Side=BUY, ExecType=FILLED, OrdStatus=FILLED).");
    }

    private void reconnectSession() {
        try {
            SessionID sessionID = findInitiatorSession();
            if (sessionID == null) {
                appendStatus("ERROR: Cannot reconnect - session not found");
                JOptionPane.showMessageDialog(frame, "Session not found. Cannot reconnect.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            Session session = Session.lookupSession(sessionID);
            if (session == null) {
                appendStatus("ERROR: Cannot reconnect - session object not found");
                JOptionPane.showMessageDialog(frame, "Session object not found. Cannot reconnect.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            appendStatus("Disconnecting session to reset sequence numbers...");
            session.logout();
            
            // Wait a bit then reconnect
            new Thread(() -> {
                try {
                    Thread.sleep(2000); // Wait 2 seconds
                    SwingUtilities.invokeLater(() -> {
                        appendStatus("Reconnecting session...");
                        session.logon();
                        appendStatus("Reconnection initiated. Session will reconnect automatically.");
                        JOptionPane.showMessageDialog(frame, 
                            "Session reconnection initiated.\nSequence numbers will be reset on next logon.", 
                            "Reconnect", JOptionPane.INFORMATION_MESSAGE);
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
            
        } catch (Exception e) {
            appendStatus("ERROR during reconnect: " + e.getMessage());
            log.error("Error reconnecting session", e);
            JOptionPane.showMessageDialog(frame, "Error reconnecting: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void appendStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            statusArea.append("[" + java.time.LocalDateTime.now() + "] " + message + "\n");
            statusArea.setCaretPosition(statusArea.getDocument().getLength());
        });
    }

    private class SendButtonListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            try {
                // Validate required fields
                if (symbolField.getText().trim().isEmpty()) {
                    JOptionPane.showMessageDialog(frame, "Symbol is required", "Validation Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (execTypeCombo.getSelectedItem() == null) {
                    JOptionPane.showMessageDialog(frame, "ExecType is required", "Validation Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (ordStatusCombo.getSelectedItem() == null) {
                    JOptionPane.showMessageDialog(frame, "OrdStatus is required", "Validation Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (sideCombo.getSelectedItem() == null) {
                    JOptionPane.showMessageDialog(frame, "Side is required", "Validation Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // Build data object
                DropCopyMessageSender.ExecutionReportData data = new DropCopyMessageSender.ExecutionReportData();
                data.setOrderId(orderIdField.getText().trim());
                data.setExecId(execIdField.getText().trim());
                data.setClOrdId(clOrdIdField.getText().trim());
                data.setOrigClOrdId(origClOrdIdField.getText().trim());
                data.setSymbol(symbolField.getText().trim());
                data.setExecType((DropCopyMessageSender.ExecutionReportData.ExecType) execTypeCombo.getSelectedItem());
                data.setOrdStatus((DropCopyMessageSender.ExecutionReportData.OrdStatus) ordStatusCombo.getSelectedItem());
                data.setSide((DropCopyMessageSender.ExecutionReportData.Side) sideCombo.getSelectedItem());
                data.setOrdType((DropCopyMessageSender.ExecutionReportData.OrdType) ordTypeCombo.getSelectedItem());
                
                if (!orderQtyField.getText().trim().isEmpty()) {
                    data.setOrderQty(new BigDecimal(orderQtyField.getText().trim()));
                }
                if (!priceField.getText().trim().isEmpty()) {
                    data.setPrice(new BigDecimal(priceField.getText().trim()));
                }
                if (!lastQtyField.getText().trim().isEmpty()) {
                    data.setLastQty(new BigDecimal(lastQtyField.getText().trim()));
                }
                if (!leavesQtyField.getText().trim().isEmpty()) {
                    data.setLeavesQty(new BigDecimal(leavesQtyField.getText().trim()));
                }
                if (!cumQtyField.getText().trim().isEmpty()) {
                    data.setCumQty(new BigDecimal(cumQtyField.getText().trim()));
                }
                if (!avgPxField.getText().trim().isEmpty()) {
                    data.setAvgPx(new BigDecimal(avgPxField.getText().trim()));
                }
                if (!stopPxField.getText().trim().isEmpty()) {
                    data.setStopPx(new BigDecimal(stopPxField.getText().trim()));
                }
                
                data.setTimeInForce((DropCopyMessageSender.ExecutionReportData.TimeInForce) timeInForceCombo.getSelectedItem());
                data.setAccount(accountField.getText().trim());
                data.setText(textField.getText().trim());
                data.setTransactTime(LocalDateTime.now(ZoneOffset.UTC));
                
                // Set QuoteReqID if provided (for short locate)
                String quoteReqId = quoteReqIdField.getText().trim();
                if (!quoteReqId.isEmpty()) {
                    data.setQuoteReqId(quoteReqId);
                }
                
                // Set ExDestination if provided
                String exDestination = exDestinationField.getText().trim();
                if (!exDestination.isEmpty()) {
                    data.setExDestination(exDestination);
                }

                // Send message
                boolean success = messageSender.sendExecutionReport(data);
                if (success) {
                    appendStatus("ExecutionReport sent successfully!");
                    JOptionPane.showMessageDialog(frame, "ExecutionReport sent successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    appendStatus("Failed to send ExecutionReport. Check logs for details.");
                    JOptionPane.showMessageDialog(frame, "Failed to send ExecutionReport. Check logs for details.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (Exception ex) {
                appendStatus("Error: " + ex.getMessage());
                log.error("Error sending ExecutionReport", ex);
                JOptionPane.showMessageDialog(frame, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}

