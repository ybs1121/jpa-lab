# JPA Lab

JPA 코드가 만드는 SQL과 데이터베이스 동작을 직접 검증하고, 성능·트랜잭션·동시성 문제의 원인과 해결책의 트레이드오프를 판단하기 위한 학습 프로젝트다.

## 검증한 주제

- 명시적 flush 없이 `save()`했을 때 시퀀스 조회, `INSERT`, 커밋의 실행 경계
- 명시적 flush 없이 발생한 제약조건 예외의 전파 및 rollback 경계
- 명시적 flush로 잡은 예외와 rollback-only에 따른 `UnexpectedRollbackException`
- 관리 엔티티의 dirty checking과 commit 시점 `UPDATE`
- JPA 조회 직전 AUTO flush와 SQL 실행 순서
- flush로 실행된 `UPDATE`와 rollback 이후 최종 DB 상태
- 벌크 `UPDATE` 이후 DB와 1차 캐시의 상태 불일치
- `clearAutomatically`에 따른 준영속 전환과 재조회
- `refresh`를 이용한 단일 관리 엔티티 최신화
- 벌크 `DELETE` 후 stale 엔티티의 dirty checking 실패와 rollback

상세한 검증 결과는 [`docs/findings.md`](docs/findings.md)에 누적한다.
