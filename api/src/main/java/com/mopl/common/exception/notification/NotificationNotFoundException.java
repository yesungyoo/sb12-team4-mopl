package com.mopl.common.exception.notification;

import com.mopl.common.exception.MoplException;

public class NotificationNotFoundException extends MoplException {

  public NotificationNotFoundException() {
    super(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
  }
}