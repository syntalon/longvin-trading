package com.longvin.trading.entities.accounts;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * DAS Login ID entity representing a DAS Trader login identifier.
 * This is used to associate accounts with DAS Trader login credentials.
 */
@Entity
@Table(name = "das_login_ids")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DasLoginId {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String loginId;

    @Column(length = 500)
    private String description;

    @ManyToMany(mappedBy = "dasLoginIds")
    @Builder.Default
    private List<Account> accounts = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
}

