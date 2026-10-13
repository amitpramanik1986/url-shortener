package io.amitpramanik1986.url.shortener.analytics;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "T_LINK_HIT_COUNT")
@NoArgsConstructor
@Getter
class LinkHitsEntity {

    LinkHitsEntity(String id){
        this.id = id;
        this.hitCount = 0L;
        this.updatedDate = LocalDateTime.now();
    }

    @Id
    @Column(name = "SHORT_LINK_ID", length = 10)
    private String id;

    @Column(name = "HIT_COUNT", nullable = false)
    private Long hitCount;

    @Column(name = "UPDATED_DATE", nullable = false)
    @Setter
    private LocalDateTime updatedDate;

    @Version
    private Long version;

}
