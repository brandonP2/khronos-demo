package com.moga.khronosdemo.dtos.response;

/**
 * Stable reason sent as "code" in some error responses, so clients can tell these cases apart without
 * reading the message, whose wording may change. Responses for any other error leave the field out.
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
    TOO_MANY_ATTEMPTS
}
