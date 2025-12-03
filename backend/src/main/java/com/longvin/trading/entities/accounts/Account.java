package com.longvin.trading.entities.accounts;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Account entity representing a trading account.
 * An account belongs to one broker and can be associated with multiple DAS Login IDs.
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String accountNumber;

    @Column(length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broker_id", nullable = false)
    private Broker broker;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "account_das_login_ids",
        joinColumns = @JoinColumn(name = "account_id"),
        inverseJoinColumns = @JoinColumn(name = "das_login_id_id")
    )
    @Builder.Default
    private List<DasLoginId> dasLoginIds = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AccountType accountType = AccountType.SHADOW;

    @Column(length = 64)
    private String strategyKey; // e.g. "OPAL_GROUP_1"

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * Helper method to add a DAS Login ID to this account.
     */
    public void addDasLoginId(DasLoginId dasLoginId) {
        if (!dasLoginIds.contains(dasLoginId)) {
            dasLoginIds.add(dasLoginId);
            dasLoginId.getAccounts().add(this);
        }
    }

    /**
     * Helper method to remove a DAS Login ID from this account.
     */
    public void removeDasLoginId(DasLoginId dasLoginId) {
        if (dasLoginIds.remove(dasLoginId)) {
            dasLoginId.getAccounts().remove(this);
        }
    }
}

