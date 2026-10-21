package kr.ac.khu.graffiti.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A school account. The email is only for admins (D-22) and never leaves the server otherwise. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String googleSub;

    private String email;

    private String nickname;

    @Column(columnDefinition = "CHAR(7)")
    private String cursorColor;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(columnDefinition = "DATETIME(3)")
    private Instant privacyConsentedAt;

    @Column(columnDefinition = "DATETIME(3)")
    private Instant bannedAt;

    private String banReason;

    @Column(columnDefinition = "DATETIME(3)")
    private Instant lastPostedAt;

    @Column(columnDefinition = "DATETIME(3)")
    private Instant createdAt;

    protected User() {
    }

    User(String googleSub, String email, String nickname, String cursorColor, Role role, Instant createdAt) {
        this.googleSub = googleSub;
        this.email = email;
        this.nickname = nickname;
        this.cursorColor = cursorColor;
        this.role = role;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getGoogleSub() {
        return googleSub;
    }

    public String getEmail() {
        return email;
    }

    public String getNickname() {
        return nickname;
    }

    public String getCursorColor() {
        return cursorColor;
    }

    public Role getRole() {
        return role;
    }

    public Instant getPrivacyConsentedAt() {
        return privacyConsentedAt;
    }

    public Instant getBannedAt() {
        return bannedAt;
    }

    public Instant getLastPostedAt() {
        return lastPostedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean hasConsented() {
        return privacyConsentedAt != null;
    }

    public boolean isBanned() {
        return bannedAt != null;
    }

    void changeRole(Role newRole) {
        this.role = newRole;
    }

    /** Records the first consent only; later calls keep the original time. */
    public void consent(Instant now) {
        if (privacyConsentedAt == null) {
            privacyConsentedAt = now;
        }
    }
}
