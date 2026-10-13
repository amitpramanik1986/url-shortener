package io.amitpramanik1986.url.shortener.api;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "T_LINK")
@SequenceGenerator(name = "linkEntitySequence", sequenceName = "SEQ_LINK_ID", allocationSize = 1)
@NoArgsConstructor
@Getter
class LinkEntity {

    LinkEntity(String fullLink, Long timeToLiveInSeconds){
        this.fullLink = fullLink;
        this.createdDate = LocalDateTime.now();
        if (timeToLiveInSeconds != null && timeToLiveInSeconds > 0) {
            this.expiredDate = this.createdDate.plusSeconds(timeToLiveInSeconds);
        }
    }

    @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "linkEntitySequence")
    @Column(name = "LINK_ID")
    private Long id;

    @Column(name = "FULL_LINK", updatable = false, nullable = false)
    private String fullLink;

    @Column(name = "CREATED_DATE", updatable = false, nullable = false)
    private LocalDateTime createdDate;

    @Column(name = "EXPIRED_DATE")
    @Setter
    private LocalDateTime expiredDate;


    public boolean isNotExpired() {
        if (expiredDate == null) {
            return true;
        }
        return expiredDate.isAfter(LocalDateTime.now());
    }
}
