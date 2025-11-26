package com.longvin.trading.service.order;

/**
 * Response for placing a DAS FIX NewOrderSingle.
 */
public class NewOrderSingleResponse {

    public enum Status {
        ACCEPTED_FOR_SENDING,
        VALIDATION_FAILED,
        SESSION_OFFLINE,
        SEND_FAILED
    }

    private Status status;
    private String senderCompId;
    private String clOrdId;
    private String sessionId;
    private String errorMessage;

    public static NewOrderSingleResponse success(String senderCompId, String clOrdId, String sessionId) {
        NewOrderSingleResponse r = new NewOrderSingleResponse();
        r.status = Status.ACCEPTED_FOR_SENDING;
        r.senderCompId = senderCompId;
        r.clOrdId = clOrdId;
        r.sessionId = sessionId;
        return r;
    }

    public static NewOrderSingleResponse failure(Status status, String senderCompId, String clOrdId, String errorMessage) {
        NewOrderSingleResponse r = new NewOrderSingleResponse();
        r.status = status;
        r.senderCompId = senderCompId;
        r.clOrdId = clOrdId;
        r.errorMessage = errorMessage;
        return r;
    }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getSenderCompId() { return senderCompId; }
    public void setSenderCompId(String senderCompId) { this.senderCompId = senderCompId; }

    public String getClOrdId() { return clOrdId; }
    public void setClOrdId(String clOrdId) { this.clOrdId = clOrdId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}

