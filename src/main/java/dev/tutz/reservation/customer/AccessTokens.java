package dev.tutz.reservation.customer;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Signs the access tokens this service accepts. The subject is the customer id and the roles travel in the
 * token, so a request is authorized without a database lookup.
 */
@Component
public class AccessTokens {

    static final String ISSUER = "seat-reservation";
    static final String ROLES_CLAIM = "roles";

    private final JwtEncoder encoder;
    private final SecurityProperties properties;
    private final Clock clock;

    AccessTokens(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public TokenResponse issue(Customer customer) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(customer.getId().toString())
                .claim(ROLES_CLAIM, List.of(customer.getRole().name()))
                .issuedAt(now)
                .expiresAt(now.plus(properties.tokenTtl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new TokenResponse(token, "Bearer", properties.tokenTtl().toSeconds());
    }
}
