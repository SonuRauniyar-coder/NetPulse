package com.netpulse.auth;

import com.netpulse.user.AppUser;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_token")
public class RefreshToken {
    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    /** SHA-256 of the raw token. A DB leak therefore does not leak usable refresh tokens. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    private Instant expiresAt;
    private Instant revokedAt;
    private Instant createdAt = Instant.now();
    private String createdIp;
    private String userAgent;

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public UUID getFamilyId() { return familyId; }
    public void setFamilyId(UUID familyId) { this.familyId = familyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCreatedIp() { return createdIp; }
    public void setCreatedIp(String createdIp) { this.createdIp = createdIp; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
