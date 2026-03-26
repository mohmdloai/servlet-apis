package com.loai.inventory.common.exception;

public class AppException extends RuntimeException{
    private final int statusCode;

    public AppException(int statusCode, String message){
        super(message);
        this.statusCode = statusCode;
    }
    public AppException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
    }
    public int getStatusCode() {
            return statusCode;
        }
}