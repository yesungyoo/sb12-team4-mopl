package com.mopl.core.domain.user.entity;

import com.mopl.core.common.entity.BaseEntity;
import com.mopl.core.common.enums.SocialProvider;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "social_accounts")
public class SocialAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "id",
            nullable = false,
            updatable = false,
            columnDefinition = "CHAR(36"
    )
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private SocialProvider provider;

    @Column(name = "provider_id", nullable = false, length = 255)
    private String providerId;

    protected SocialAccount() {}

    public SocialAccount(
            User user,
            SocialProvider provider,
            String providerId
    ) {
        this.user = user;
        this.provider = provider;
        this.providerId = providerId;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public SocialProvider getProvider() {
        return provider;
    }

    public String getProviderId() {
        return providerId;
    }

}
