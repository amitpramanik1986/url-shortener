package io.amitpramanik1986.url.shortener.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
interface LinkHitsRepository extends JpaRepository<LinkHitsEntity, String> {

    @NativeQuery("""
            SELECT SHORT_LINK_ID AS linkId,
                   HIT_COUNT AS totalHits,
                   UPDATED_DATE AS lastAccessed
            FROM T_LINK_HIT_COUNT
            WHERE SHORT_LINK_ID = :linkId
            """)
    Optional<LinkStatistics> findLinkStatisticsByLinkId(String linkId);
}
