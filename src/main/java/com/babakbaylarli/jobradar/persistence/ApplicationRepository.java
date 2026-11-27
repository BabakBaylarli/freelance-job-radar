package com.babakbaylarli.jobradar.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, Long> {

    boolean existsByJobIdAndStatus(Long jobId, ApplicationStatus status);

    /** The open application of a job, if any (there is at most one at a time). */
    Optional<ApplicationEntity> findFirstByJobIdAndStatusInOrderByIdDesc(Long jobId, Collection<ApplicationStatus> statuses);

    Optional<ApplicationEntity> findFirstByDraftMessageIdOrderByIdDesc(Long draftMessageId);

    List<ApplicationEntity> findByStatusInOrderByIdDesc(Collection<ApplicationStatus> statuses, Pageable page);

    List<ApplicationEntity> findAllByOrderByIdDesc(Pageable page);

    /** Sends (by this bot) since the given instant: drives the daily limits. */
    long countByStatusAndSentAtGreaterThanEqual(ApplicationStatus status, Instant since);

    long countByStatusAndPlatformAndSentAtGreaterThanEqual(ApplicationStatus status, String platform, Instant since);

    @Query("select a.platform as platform, count(a) as sent from ApplicationEntity a where a.status = 'SENT' and a.sentAt >= :since group by a.platform")
    List<PlatformCount> sentPerPlatformSince(@Param("since") Instant since);

    Optional<ApplicationEntity> findFirstByStatusOrderBySentAtDesc(ApplicationStatus status);

    interface PlatformCount {
        String getPlatform();

        long getSent();
    }
}
