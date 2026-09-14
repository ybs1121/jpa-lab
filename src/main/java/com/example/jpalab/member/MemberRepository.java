package com.example.jpalab.member;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

    long countByEmail(String email);

    @Modifying
    @Query("""
            update Member m
               set m.email = :email
             where m.id = :memberId
            """)
    int bulkChangeEmail(
            @Param("memberId") Long memberId,
            @Param("email") String email
    );

    @Modifying(clearAutomatically = true)
    @Query("""
            update Member m
               set m.email = :email
             where m.id = :memberId
            """)
    int bulkChangeEmailAndClear(
            @Param("memberId") Long memberId,
            @Param("email") String email
    );

    @Modifying
    @Query("delete from Member m where m.id = :memberId")
    int bulkDeleteById(@Param("memberId") Long memberId);
}
