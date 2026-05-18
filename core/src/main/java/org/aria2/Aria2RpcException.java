// Aria2RpcException.java
package org.aria2;

public class Aria2RpcException extends Exception {
    private final int code;
    private final String message;

    public Aria2RpcException(int code, String message) {
        super("Aria2 RPC Error " + code + ": " + message);
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}