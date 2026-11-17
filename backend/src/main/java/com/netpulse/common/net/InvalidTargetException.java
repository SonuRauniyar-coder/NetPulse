package com.netpulse.common.net;

import com.netpulse.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidTargetException extends ApiException {
    public InvalidTargetException(String detail) {
        super(HttpStatus.BAD_REQUEST, "Invalid target", detail);
    }
}
