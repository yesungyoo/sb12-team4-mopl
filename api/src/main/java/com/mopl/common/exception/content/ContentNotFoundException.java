package com.mopl.common.exception.content;

import com.mopl.common.exception.MoplException;

import java.rmi.MarshalException;

public class ContentNotFoundException extends MoplException {

    public ContentNotFoundException() {
        super(ContentErrorCode.CONTENT_NOT_FOUND);
    }
}
