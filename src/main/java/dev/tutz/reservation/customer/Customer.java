package dev.tutz.reservation.customer;

import dev.tutz.reservation.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(nullable = false)
    private Instant createdAt;

    protected Customer() {
    }

    private Customer(String email, String name, String passwordHash, Role role, Instant now) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = now;
    }

    /** @param passwordHash already hashed: a plain password never reaches the entity */
    public static Customer register(String email, String name, String passwordHash, Role role, Instant now) {
        return new Customer(normalize(email), name.strip(), passwordHash, role, now);
    }

    /** Ana@Mail.com and ana@mail.com are the same person, so e-mails are compared and stored in lower case. */
    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
