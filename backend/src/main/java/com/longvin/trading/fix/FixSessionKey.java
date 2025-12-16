package com.longvin.trading.fix;

import java.util.Objects;
import quickfix.SessionID;

public final class FixSessionKey {

    private final String connectionType;
    private final String beginString;
    private final String senderCompId;
    private final String targetCompId;
    private final String sessionQualifier;

    public FixSessionKey(String connectionType,
                         String beginString,
                         String senderCompId,
                         String targetCompId,
                         String sessionQualifier) {
        this.connectionType = normalize(connectionType);
        this.beginString = normalize(beginString);
        this.senderCompId = normalize(senderCompId);
        this.targetCompId = normalize(targetCompId);
        this.sessionQualifier = normalize(sessionQualifier);
    }

    public static FixSessionKey from(String connectionType, SessionID sessionID) {
        String qualifier = null;
        try {
            qualifier = sessionID.getSessionQualifier();
        } catch (Exception ignored) {
            qualifier = null;
        }
        return new FixSessionKey(connectionType,
                sessionID.getBeginString(),
                sessionID.getSenderCompID(),
                sessionID.getTargetCompID(),
                qualifier);
    }

    public String getConnectionType() { return connectionType; }
    public String getBeginString() { return beginString; }
    public String getSenderCompId() { return senderCompId; }
    public String getTargetCompId() { return targetCompId; }
    public String getSessionQualifier() { return sessionQualifier; }

    private static String normalize(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FixSessionKey that)) return false;
        return Objects.equals(connectionType, that.connectionType)
                && Objects.equals(beginString, that.beginString)
                && Objects.equals(senderCompId, that.senderCompId)
                && Objects.equals(targetCompId, that.targetCompId)
                && Objects.equals(sessionQualifier, that.sessionQualifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(connectionType, beginString, senderCompId, targetCompId, sessionQualifier);
    }

    @Override
    public String toString() {
        return "FixSessionKey{" +
                "connectionType='" + connectionType + '\'' +
                ", beginString='" + beginString + '\'' +
                ", senderCompId='" + senderCompId + '\'' +
                ", targetCompId='" + targetCompId + '\'' +
                ", sessionQualifier='" + sessionQualifier + '\'' +
                '}';
    }
}
