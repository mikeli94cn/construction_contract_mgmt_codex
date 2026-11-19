package com.construction.contracts.identity.domain;

import jakarta.persistence.*;
import java.util.Locale;

@Entity
@Table(name = "users")
public class Account {
    public enum Status { PENDING, ACTIVE, DISABLED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String email;
    private String displayName;
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    private Status status;
    @Version
    private long version;

    protected Account() {}

    public Account(String email, String displayName) {
        this.email = normalize(email);
        this.displayName = displayName.strip();
        this.status = Status.PENDING;
    }

    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public void activate(String hash) {
        if (status != Status.PENDING) {
            throw new IllegalStateException("Account is already provisioned");
        }
        passwordHash = hash;
        status = Status.ACTIVE;
    }

    public long id() { return id; }
    public String email() { return email; }
    public String displayName() { return displayName; }
    public String passwordHash() { return passwordHash; }
    public boolean active() { return status == Status.ACTIVE; }
    public boolean pending() { return status == Status.PENDING; }
}
