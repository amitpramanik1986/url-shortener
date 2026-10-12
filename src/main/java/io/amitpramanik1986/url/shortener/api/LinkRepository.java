package io.amitpramanik1986.url.shortener.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
interface LinkRepository extends JpaRepository<LinkEntity, Long> {
}
