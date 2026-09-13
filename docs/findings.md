# JPA Lab Findings

실제 Lab에서 SQL과 데이터베이스 동작을 실행해 검증한 결과만 기록한다. 예상이나 미검증 지식은 포함하지 않는다.

## 검증된 주제

### 명시적 flush 없는 `save`

#### 조건

- PostgreSQL 17
- Hibernate ORM 7.2.12
- `GenerationType.SEQUENCE`
- `allocationSize = 1`
- `TransactionTemplate`로 시작한 하나의 트랜잭션
- `save()` 이후 명시적 flush 없음

#### 관찰 결과

1. `save()` 안에서 `select nextval('member_sequence')`가 실행됐다.
2. `save()`가 반환될 때 식별자 `1`은 할당됐지만 `INSERT`는 아직 실행되지 않았다.
3. 트랜잭션 콜백이 반환된 뒤 커밋을 시작하면서 flush가 발생했다.
4. flush 과정에서 `INSERT INTO members (email, id) VALUES (?, ?)`가 실행됐다.
5. 커밋이 끝난 뒤 별도 JDBC 조회에서 저장된 행이 1개 확인됐다.
6. 전체 과정에서 예외는 발생하지 않았다.

#### 예상과의 차이

- 예상한 저장 SQL과 조회 SQL 외에 식별자를 얻는 시퀀스 SQL이 별도로 발생했다.
- `after save` 로그 자체는 flush를 유발하지 않았다.
- flush는 관찰용 JDBC 조회 직전이 아니라 트랜잭션 커밋 과정에서 이미 수행됐다.
- 커밋 후 JDBC 조회는 별도의 트랜잭션으로 묶지 않아도 정상 실행됐다.

#### 트레이드오프

`allocationSize = 1`에서는 엔티티 하나의 식별자를 만들 때마다 DB 시퀀스 접근이 발생한다. 대신 식별자 할당 흐름이 단순하고 이번 실험처럼 SQL 실행 시점을 추적하기 쉽다. 더 큰 할당 크기에 따른 왕복 감소와 식별자 공백은 별도 비교 실험 전까지 검증 결과로 단정하지 않는다.

### 명시적 flush 없는 제약조건 위반

#### 조건

- UNIQUE 제약조건이 있는 이메일을 별도 트랜잭션에서 먼저 커밋
- `@Transactional` 서비스 내부에서 같은 이메일의 엔티티를 `save()`
- 서비스 내부에서 `DataIntegrityViolationException`을 catch하도록 구성
- 명시적 flush 없음

#### 관찰 결과

1. 서비스의 `save()` 안에서 `select nextval('member_sequence')`가 실행됐다.
2. `after save`와 `service method ending` 로그가 모두 출력됐다.
3. 서비스 본문이 반환된 뒤 트랜잭션 커밋을 시작하면서 flush가 발생했다.
4. flush가 `INSERT`를 실행했을 때 PostgreSQL UNIQUE 제약조건 위반이 발생했다.
5. 서비스 내부 catch는 실행되지 않았고 호출자가 `DataIntegrityViolationException`을 받았다.
6. 트랜잭션은 rollback됐으며, 사전에 커밋된 행 한 개만 남았다.

#### 트레이드오프

명시적 flush 없이 쓰기 SQL을 커밋 시점까지 지연하면 여러 변경 작업을 한 번에 처리할 여지가 생긴다. 반면 DB 제약조건 오류가 서비스 본문 바깥의 트랜잭션 완료 과정에서 드러날 수 있으므로, 서비스 내부의 국소적인 `try-catch`만으로 해당 오류를 처리할 수 없다.

### 명시적 flush가 있는 제약조건 위반

#### 조건

- 직전 제약조건 위반 실험과 동일한 데이터 및 서비스 트랜잭션
- `save()` 다음에 Repository의 `flush()`를 서비스 내부 try 블록에서 명시적으로 호출

#### 관찰 결과

1. `save()`에서 시퀀스 값을 조회한 뒤 `after save`가 출력됐다.
2. 명시적 `flush()`가 서비스 본문 안에서 `INSERT`를 실행했다.
3. UNIQUE 제약조건 위반으로 발생한 `DataIntegrityViolationException`은 서비스 내부 catch가 잡았다.
4. 실패한 Repository 참여 트랜잭션이 기존 서비스 트랜잭션을 rollback-only로 표시했다.
5. 서비스 본문의 마지막 로그까지 실행됐지만 트랜잭션 경계를 정상적으로 빠져나오지는 못했다.
6. 호출자는 `UnexpectedRollbackException`을 받았고 기존 행 하나만 남았다.

#### 트레이드오프

명시적 flush는 제약조건 오류를 서비스 본문 안에서 조기에 확인할 수 있게 한다. 그러나 예외를 catch해도 이미 실패한 트랜잭션이 정상 상태로 복구되는 것은 아니므로, 같은 트랜잭션을 계속 사용하거나 정상 commit을 기대할 수 없다.

### `save()` 없는 dirty checking

#### 조건

- 이미 커밋된 회원을 서비스 트랜잭션에서 조회
- 관리 중인 엔티티의 이메일 필드만 변경
- 변경 후 `save()`와 명시적 flush를 호출하지 않음

#### 관찰 결과

1. 서비스의 `findById()`에서 `SELECT`가 실행됐다.
2. 필드 변경과 서비스 본문의 마지막 로그까지 `UPDATE`는 실행되지 않았다.
3. 서비스 본문이 반환된 뒤 commit을 시작하면서 flush가 발생했다.
4. dirty checking으로 `UPDATE members SET email = ? WHERE id = ?`가 실행됐다.
5. commit 이후 호출자가 조회한 이메일은 `changed@example.com`이었다.

#### 트레이드오프

관리 엔티티 수정에는 별도의 `save()`가 필요하지 않아 갱신 코드가 단순해진다. 반면 객체를 바꾼 시점과 SQL 실행 시점이 다르므로, DB 제약조건 오류의 발생 위치와 트랜잭션 내부·외부에서 관찰되는 상태를 구분해야 한다.

### JPA 조회 직전의 AUTO flush

#### 조건

- 관리 중인 회원의 이메일 필드를 변경
- `save()`와 명시적 flush 없이 변경된 이메일을 조건으로 JPA count 조회 실행
- flush mode `AUTO`

#### 관찰 결과

1. `findById()`의 `SELECT`로 엔티티를 조회했다.
2. `before count query` 로그까지 `UPDATE`는 실행되지 않았다.
3. `countByEmail()` 실행 과정에서 dirty checking `UPDATE`가 먼저 실행됐다.
4. 이어서 변경된 이메일을 조건으로 count `SELECT`가 실행돼 `1`을 반환했다.
5. 서비스 본문이 종료된 뒤 commit됐으며 최종 이메일은 `changed@example.com`이었다.

#### 트레이드오프

AUTO flush는 같은 트랜잭션의 JPA 조회가 보류 중인 변경을 반영하도록 일관성을 지켜 준다. 반면 조회 호출이 예상보다 이른 쓰기 SQL과 제약조건 검사를 유발할 수 있으므로, SQL이 오직 commit 시점에만 실행된다고 가정해서는 안 된다.

### flush 이후 예외와 rollback

#### 조건

- AUTO flush와 count 조회까지는 직전 실험과 동일
- count 조회가 끝난 직후 서비스에서 `IllegalStateException` 발생
- 서비스 내부에서 예외를 catch하지 않음

#### 관찰 결과

1. `findById()`의 `SELECT` 이후 관리 엔티티의 이메일을 변경했다.
2. count 조회 직전 AUTO flush가 `UPDATE`를 DB에 전송했다.
3. 같은 트랜잭션의 count 조회는 변경된 이메일을 보고 `1`을 반환했다.
4. 이후 발생한 `IllegalStateException`이 호출자에게 전파됐다.
5. 트랜잭션은 rollback됐고 외부 조회의 이메일은 `original@example.com`이었다.

#### 트레이드오프

flush를 이용하면 commit 전에 SQL 결과와 제약조건을 확인할 수 있다. 그러나 flush된 변경도 아직 트랜잭션 안에 있으므로 이후 rollback될 수 있으며, SQL 실행 로그만으로 최종 반영 여부를 판단해서는 안 된다.

각 결과는 다음 내용을 포함한다.

- 문제와 사전 예상
- 실행 조건과 관찰된 SQL
- 실제 데이터베이스 동작
- 예상과 실제 결과의 차이
- 적용한 해결책
- 해결책의 트레이드오프
