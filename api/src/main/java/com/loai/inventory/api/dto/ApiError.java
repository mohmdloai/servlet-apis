package com.loai.inventory.api.dto;

/**
 * Uniform error response body for all non-2xx responses.
 *
 * JSON shape:
 * {
 *   "status":  404,
 *   "error":   "Not Found",
 *   "message": "Product not found: 3fa85f64-..."
 * }
 */
public class ApiError {

    private int    status;
    private String error;
    private String message;

    public ApiError(int status, String error, String message) {
        this.status  = status;
        this.error   = error;
        this.message = message;
    }

    public static ApiError of(int status, String message) {
        return new ApiError(status, httpPhrase(status), message);
    }

    public int    getStatus()  { return status; }
    public String getError()   { return error; }
    public String getMessage() { return message; }

    private static String httpPhrase(int code) {
        return switch (code) {
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 500 -> "Internal Server Error";
            default  -> "Error";
        };
    }
}