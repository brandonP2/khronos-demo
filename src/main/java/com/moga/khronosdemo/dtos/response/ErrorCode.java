package com.moga.khronosdemo.dtos.response;

/**
 * Stable reason sent as "code" in some error responses, so clients can tell these cases apart without
 * reading the message, whose wording may change. Responses for any other error leave the field out.
 * A 409 gets one, and the details that go with it, through BusinessRuleException.
 */
public enum ErrorCode {

    /** 401 at login: unknown username or wrong password, whatever the state of the account. */
    INVALID_CREDENTIALS,

    /** 401 at login with the right password, or on a request with a token, when the account is disabled. */
    ACCOUNT_DISABLED,

    /** 401 when the token has expired, was revoked by a password change or is otherwise not valid. */
    SESSION_EXPIRED,

    /** 401 when an endpoint that needs a token is called without one. */
    AUTHENTICATION_REQUIRED,

    /** 429 at login while the username and IP, or the IP alone, is blocked; Retry-After says for how long. */
    TOO_MANY_ATTEMPTS,

    /** 409 when the establishment already has a cash closing for that business day; details.date is the day. */
    CASH_CLOSING_EXISTS,

    /** 409 when a closed cash closing is edited, deleted or closed again. */
    CASH_CLOSING_CLOSED,

    /** 409 when a cash closing that is not closed is reopened. */
    CASH_CLOSING_NOT_CLOSED,

    /** 409 when a draft that was closed before, and then reopened, is deleted. */
    CASH_CLOSING_WAS_CLOSED,

    /** 409 when a day is closed with a cash difference over the threshold and no differenceReason. */
    DIFFERENCE_REASON_REQUIRED,

    /** 409 when a day is closed before its cash has been counted (countedCash is null). */
    CASH_NOT_COUNTED,

    /**
     * 409 when a cash expense is added, changed, moved or deleted on a day whose cash closing is closed;
     * details.date is that day.
     */
    CASH_DAY_CLOSED,

    /** 409 when a cash closing's business day or an expense's date is after today in the establishment's time zone. */
    FUTURE_DATE,

    /** 409 when another supplier of the establishment already has that name, whatever its case. */
    SUPPLIER_NAME_TAKEN
}
