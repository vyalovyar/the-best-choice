package com.moviechoice.voting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZonedDateTime;

@Entity
@Table(name = "catalog_state")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogState {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "next_page", nullable = false)
    private Integer nextPage;

    @Column(name = "exhausted", nullable = false)
    private Boolean exhausted;

    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;
}
