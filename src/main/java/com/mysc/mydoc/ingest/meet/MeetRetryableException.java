package com.mysc.mydoc.ingest.meet;

public class MeetRetryableException extends RuntimeException {
    public MeetRetryableException(String message) {
        super(message);
    }

    public MeetRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
