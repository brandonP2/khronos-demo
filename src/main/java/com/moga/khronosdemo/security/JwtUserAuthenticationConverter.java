package com.moga.khronosdemo.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Turns a valid token back into the same SecurityUser principal that AccessGuard and
 * @AuthenticationPrincipal expect. The user is reloaded on every request, so a deactivated
 * worker or a changed role takes effect immediately instead of when the token expires.
 */
@Component
public class JwtUserAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final DatabaseUserDetailsService userDetailsService;

    public JwtUserAuthenticationConverter(DatabaseUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        if (!TokenService.ISSUER.equals(jwt.getClaimAsString("iss"))) {
            throw new InvalidBearerTokenException("Unknown token issuer");
        }
        Long userId;
        try {
            userId = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException ex) {
            throw new InvalidBearerTokenException("Invalid token subject");
        }

        SecurityUser user = userDetailsService.loadUserById(userId)
                .orElseThrow(() -> new InvalidBearerTokenException("The user of this token no longer exists"));

        if (!user.isEnabled()) {
            throw new DisabledException("This account is disabled");
        }

        // A password change revokes every token issued before it. Token times have
        // second precision, so the change time is truncated to compare like with like.
        Instant issuedAt = jwt.getIssuedAt();
        Instant changedAt = user.getCredentialsChangedAt();
        if (changedAt != null && (issuedAt == null || issuedAt.isBefore(changedAt.truncatedTo(ChronoUnit.SECONDS)))) {
            throw new CredentialsExpiredException("The password was changed after this token was issued");
        }

        return UsernamePasswordAuthenticationToken.authenticated(user, jwt, user.getAuthorities());
    }
}
