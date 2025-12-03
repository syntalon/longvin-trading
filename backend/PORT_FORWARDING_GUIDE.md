# Port Forwarding Guide for Drop Copy Acceptor

## The Problem

Your MacBook is behind a router with:
- **Local IP**: `192.168.1.77` (private network)
- **Public IP**: `173.69.33.84` (router's public IP)
- **Port**: `9877` (FIX acceptor)

When DAS Trader tries to connect to `173.69.33.84:9877`, the router doesn't know where to forward the connection unless port forwarding is configured.

## Solution: Configure Port Forwarding

### Step 1: Find Your Router's Admin Interface

1. Find your router's IP (usually the default gateway):
   ```bash
   netstat -rn | grep default
   ```
   Usually something like `192.168.1.1` or `192.168.0.1`

2. Open router admin in browser:
   - `http://192.168.1.1` (or your gateway IP)
   - Login with admin credentials

### Step 2: Configure Port Forwarding

Look for these sections (varies by router):
- **Port Forwarding**
- **Virtual Server**
- **NAT Forwarding**
- **Applications & Gaming**

Configure:
- **External Port**: `9877`
- **Internal IP**: `192.168.1.77` (your MacBook)
- **Internal Port**: `9877`
- **Protocol**: `TCP`
- **Name/Description**: `FIX Drop Copy` (optional)

### Step 3: Set Static IP for MacBook (Important!)

Your MacBook needs a **static IP** so port forwarding always works:

**macOS:**
1. System Settings → Network
2. Select your network connection (WiFi/Ethernet)
3. Click "Details" → "TCP/IP"
4. Change from "Using DHCP" to "Manually"
5. Set IP: `192.168.1.77`
6. Subnet: `255.255.255.0`
7. Router: `192.168.1.1` (or your gateway)

**Or use DHCP Reservation** (if router supports it):
- Reserve IP `192.168.1.77` for your MacBook's MAC address

### Step 4: Test Port Forwarding

**From outside your network:**
```bash
# Use an online port checker
# Visit: https://www.yougetsignal.com/tools/open-ports/
# Enter: 173.69.33.84 and port 9877
```

**Or from command line (if you have access to external server):**
```bash
nc -zv 173.69.33.84 9877
```

## Alternative Solutions

### Option 1: Use VPN (Recommended for Security)

Instead of exposing port 9877 to the internet:

1. Set up a VPN server on your network
2. DAS Trader connects through VPN
3. Use private IP: `192.168.1.77:9877`
4. More secure than port forwarding

### Option 2: Deploy to Cloud Server

If `173.69.33.84` is a cloud server:
- Deploy the application to that server
- Server has public IP directly
- No port forwarding needed
- More reliable for production

### Option 3: Use SSH Tunnel (For Testing)

For temporary testing:
```bash
# On a server with public IP (173.69.33.84)
ssh -R 9877:localhost:9877 user@173.69.33.84

# Then DAS Trader connects to localhost:9877 on the server
# Which tunnels to your MacBook
```

### Option 4: Use ngrok (For Development/Testing)

Temporary public tunnel:
```bash
# Install ngrok: https://ngrok.com/
ngrok tcp 9877

# This gives you a public URL like: tcp://0.tcp.ngrok.io:12345
# DAS Trader connects to that URL
# Note: Free tier has limitations
```

## Testing Checklist

- [ ] Router port forwarding configured: `9877 → 192.168.1.77:9877`
- [ ] MacBook has static IP: `192.168.1.77`
- [ ] Application is running and listening on port 9877
- [ ] Test from local network: `nc -zv 192.168.1.77 9877`
- [ ] Test from internet: Use online port checker
- [ ] Verify DAS Trader can connect

## Router-Specific Instructions

### Common Router Brands:

**Netgear:**
- Advanced → Port Forwarding / Port Triggering
- Add Custom Service

**Linksys:**
- Connectivity → Router Settings → Port Forwarding

**TP-Link:**
- Advanced → NAT Forwarding → Virtual Servers

**ASUS:**
- WAN → Virtual Server / Port Forwarding

**Apple AirPort:**
- Network → Port Mappings

## Security Considerations

⚠️ **Warning**: Exposing port 9877 to the internet has security implications:

1. **Use VPN instead** (more secure)
2. **Restrict source IPs** (if router supports it):
   - Only allow connections from DAS Trader's IP
3. **Monitor connections**:
   - Check router logs for suspicious activity
4. **Consider firewall rules**:
   - Even with port forwarding, you can add firewall rules

## Quick Verification

After setting up port forwarding:

```bash
# 1. Verify app is listening
lsof -i :9877

# 2. Test from local network
nc -zv 192.168.1.77 9877

# 3. Test from internet (use online tool)
# https://www.yougetsignal.com/tools/open-ports/
```

## Current Status

✅ **Firewall**: Not blocking (disabled)
✅ **Application**: Listening on port 9877
✅ **Local IP**: 192.168.1.77
✅ **Public IP**: 173.69.33.84
❌ **Port Forwarding**: Needs to be configured

Once port forwarding is set up, DAS Trader should be able to connect to `173.69.33.84:9877` and it will be forwarded to your MacBook at `192.168.1.77:9877`.

