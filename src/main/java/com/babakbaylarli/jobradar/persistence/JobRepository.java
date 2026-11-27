package com.babakbaylarli.jobradar.persistence;

import java.time.Instant;
import java.util.Collection;
import com.babakbaylarli.jobradar.source.Market;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<JobEntity, Long> {

    boolean existsBySourceAndExternalId(String source, String externalId);

    boolean existsByUrl(String url);

    /** Matches waiting to be delivered (in the markets the owner wants), best first. */
    List<JobEntity> findByDeliveryInAndUserStatusAndMarketInOrderByScoreDescIdDesc(
            Collection<Delivery> delivery, UserStatus userStatus, Collection<Market> markets, Pageable page);

    long countByDeliveryAndUserStatusAndMarketIn(Delivery delivery, UserStatus userStatus, Collection<Market> markets);

    long countByDeliveryInAndUserStatusAndMarketIn(Collection<Delivery> delivery, UserStatus userStatus, Collection<Market> markets);

    /** The earliest job with this cross-source key seen after the given instant (same job on another platform). */
    Optional<JobEntity> findFirstByDedupKeyAndFirstSeenAtAfterOrderByIdAsc(String dedupKey, Instant after);

    /** Jobs seen from one source (including sub-sources such as {@code email:upwork}) since the given instant. */
    @Query("select count(j) from JobEntity j where (j.source = :id or j.source like concat(:id, ':%')) and j.firstSeenAt >= :since")
    long countFromSourceSince(@Param("id") String id, @Param("since") Instant since);

    /** How many individual (live) messages went out since the given instant - drives the hourly limit. */
    long countByDeliveryAndNotifiedAtAfter(Delivery delivery, Instant since);

    List<JobEntity> findByUserStatusOrderByIdDesc(UserStatus userStatus, Pageable page);

    @Query("""
            select j.source as source,
                   count(j) as seen,
                   coalesce(sum(case when j.matched = true then 1 else 0 end), 0) as matched
            from JobEntity j
            where j.firstSeenAt >= :since
            group by j.source
            order by j.source
            """)
    List<SourceStats> statsSince(@Param("since") Instant since);

    @Query("""
            select j.market as market,
                   count(j) as seen,
                   coalesce(sum(case when j.matched = true then 1 else 0 end), 0) as matched
            from JobEntity j
            where j.firstSeenAt >= :since
            group by j.market
            """)
    List<MarketStats> marketStatsSince(@Param("since") Instant since);

    interface MarketStats {
        Market getMarket();

        long getSeen();

        long getMatched();
    }

    interface SourceStats {
        String getSource();

        long getSeen();

        long getMatched();
    }
}
