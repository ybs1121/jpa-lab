package com.example.jpalab.member;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    private final MemberRepository memberRepository;
    private final EntityManager entityManager;

    public MemberService(MemberRepository memberRepository, EntityManager entityManager) {
        this.memberRepository = memberRepository;
        this.entityManager = entityManager;
    }

    @Transactional
    public void registerDuplicate() {
        log.info("[checkpoint] service started");

        try {
            Member member = memberRepository.save(new Member("duplicate@example.com"));
            log.info("[checkpoint] after save: id={}", member.getId());
        } catch (DataIntegrityViolationException exception) {
            log.info("[checkpoint] caught inside service");
        }

        log.info("[checkpoint] service method ending");
    }

    @Transactional
    public void registerDuplicateWithFlush() {
        log.info("[checkpoint] service started");

        try {
            Member member = memberRepository.save(new Member("duplicate@example.com"));
            log.info("[checkpoint] after save: id={}", member.getId());

            memberRepository.flush();
            log.info("[checkpoint] after flush");
        } catch (DataIntegrityViolationException exception) {
            log.info("[checkpoint] caught inside service");
        }

        log.info("[checkpoint] service method ending");
    }

    @Transactional
    public void changeEmail(Long memberId) {
        log.info("[checkpoint] service started");

        Member member = memberRepository.findById(memberId).orElseThrow();
        log.info("[checkpoint] after find");

        member.changeEmail("changed@example.com");
        log.info("[checkpoint] after change");
        log.info("[checkpoint] service method ending");
    }

    @Transactional
    public void changeEmailAndCount(Long memberId) {
        log.info("[checkpoint] service started");

        Member member = memberRepository.findById(memberId).orElseThrow();
        log.info("[checkpoint] after find");

        member.changeEmail("changed@example.com");
        log.info("[checkpoint] after change");

        log.info("[checkpoint] before count query");
        long count = memberRepository.countByEmail("changed@example.com");
        log.info("[checkpoint] after count query: count={}", count);
        log.info("[checkpoint] service method ending");
    }

    @Transactional
    public void changeEmailAndFail(Long memberId) {
        log.info("[checkpoint] service started");

        Member member = memberRepository.findById(memberId).orElseThrow();
        log.info("[checkpoint] after find");

        member.changeEmail("changed@example.com");
        log.info("[checkpoint] after change");

        log.info("[checkpoint] before count query");
        long count = memberRepository.countByEmail("changed@example.com");
        log.info("[checkpoint] after count query: count={}", count);

        throw new IllegalStateException("failure after flush");
    }

    @Transactional
    public BulkUpdateObservation changeEmailWithBulk(Long memberId) {
        Member first = memberRepository.findById(memberId).orElseThrow();
        log.info("[checkpoint] before bulk: {}", first.getEmail());

        int updatedRows = memberRepository.bulkChangeEmail(
                memberId,
                "bulk@example.com"
        );

        log.info("[checkpoint] updated rows: {}", updatedRows);
        log.info("[checkpoint] first after bulk: {}", first.getEmail());

        Member second = memberRepository.findById(memberId).orElseThrow();

        log.info("[checkpoint] second: {}", second.getEmail());
        log.info("[checkpoint] same instance: {}", first == second);

        return new BulkUpdateObservation(
                updatedRows,
                first.getEmail(),
                second.getEmail(),
                first == second
        );
    }

    @Transactional
    public BulkUpdateObservation changeEmailWithBulkAndClear(Long memberId) {
        Member first = memberRepository.findById(memberId).orElseThrow();
        log.info("[checkpoint] before bulk: {}", first.getEmail());

        int updatedRows = memberRepository.bulkChangeEmailAndClear(
                memberId,
                "bulk@example.com"
        );

        log.info("[checkpoint] first after clear: {}", first.getEmail());

        Member second = memberRepository.findById(memberId).orElseThrow();

        log.info("[checkpoint] second: {}", second.getEmail());
        log.info("[checkpoint] same instance: {}", first == second);

        return new BulkUpdateObservation(
                updatedRows,
                first.getEmail(),
                second.getEmail(),
                first == second
        );
    }

    @Transactional
    public RefreshObservation changeEmailWithBulkAndRefresh(Long memberId) {
        Member first = memberRepository.findById(memberId).orElseThrow();
        log.info("[checkpoint] before bulk: {}", first.getEmail());

        int updatedRows = memberRepository.bulkChangeEmail(
                memberId,
                "bulk@example.com"
        );

        String beforeRefreshEmail = first.getEmail();
        log.info("[checkpoint] before refresh: {}", beforeRefreshEmail);

        entityManager.refresh(first);

        String afterRefreshEmail = first.getEmail();
        log.info("[checkpoint] after refresh: {}", afterRefreshEmail);

        Member second = memberRepository.findById(memberId).orElseThrow();

        log.info("[checkpoint] second: {}", second.getEmail());
        log.info("[checkpoint] same instance: {}", first == second);

        return new RefreshObservation(
                updatedRows,
                beforeRefreshEmail,
                afterRefreshEmail,
                second.getEmail(),
                first == second
        );
    }

    @Transactional
    public void deleteAndChangeManagedEntity(Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow();
        log.info("[checkpoint] before delete: {}", member.getEmail());

        int deletedRows = memberRepository.bulkDeleteById(memberId);

        log.info("[checkpoint] deleted rows: {}", deletedRows);
        log.info("[checkpoint] after delete: {}", member.getEmail());

        member.changeEmail("after-delete@example.com");

        log.info("[checkpoint] after change: {}", member.getEmail());
        log.info("[checkpoint] service method ending");
    }

    public record BulkUpdateObservation(
            int updatedRows,
            String firstEmail,
            String secondEmail,
            boolean sameInstance
    ) {
    }

    public record RefreshObservation(
            int updatedRows,
            String beforeRefreshEmail,
            String afterRefreshEmail,
            String secondEmail,
            boolean sameInstance
    ) {
    }
}
