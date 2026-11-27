package com.babakbaylarli.jobradar.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<JobEntity, Long> {

    boolean existsBySourceAndExternalId(String source, String externalId);

    boolean existsByUrl(String url);

    /** Matches waiting to be delivered, best first. */
    List<JobEntity> findByDeliveryInAndUserStatusOrderByScoreDescIdDesc(
            Collection<Delivery> delivery, UserStatus userStatus, Pageable page);

    long countByDeliveryAndUserStatus(Delivery delivery, UserStatus userStatus);

    long countByDeliveryInAndUserStatus(Collection<Delivery> delivery, UserStatus userStatus);

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

    interface SourceStats {
        String getSource();

        long getSeen();

        long getMatched();
    }
}
