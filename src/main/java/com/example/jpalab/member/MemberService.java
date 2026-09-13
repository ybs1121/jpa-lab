package com.example.jpalab.member;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    private final MemberRepository memberRepository;

    public MemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
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
}
