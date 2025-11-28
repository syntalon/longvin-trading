package com.longvin.trading.fix;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to verify the acceptor session is listening and can accept connections.
 */
public class AcceptorSessionTest {

    @Test
    public void testAcceptorSessionConnection() throws Exception {
        // Connect to the acceptor
        try (Socket socket = new Socket("localhost", 9877)) {
            assertTrue(socket.isConnected(), "Should be able to connect to acceptor on port 9877");
            System.out.println("✓ Successfully connected to acceptor on port 9877");
        }
    }

    /**
     * Manual test method - can be run to verify the acceptor is listening.
     * Note: This requires the acceptor to be running.
     */
    public static void main(String[] args) throws Exception {
        System.out.println("Testing acceptor session connection...");
        
        // Test 1: Basic TCP connection
        try (Socket socket = new Socket("localhost", 9877)) {
            System.out.println("✓ TCP connection successful on port 9877");
            System.out.println("  Local address: " + socket.getLocalAddress());
            System.out.println("  Remote address: " + socket.getRemoteSocketAddress());
        } catch (IOException e) {
            System.err.println("✗ Failed to connect: " + e.getMessage());
            System.err.println("  Make sure the acceptor session is running!");
            return;
        }
        
        System.out.println("\nFor full FIX protocol testing:");
        System.out.println("1. Monitor logs: tail -f backend/target/quickfix/log/*.log");
        System.out.println("2. Wait for DAS Trader to connect (they confirmed it's configured)");
        System.out.println("3. Check application logs for 'onLogon' events");
    }
}

