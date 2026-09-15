package com.example.jpalab.query;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {

    List<Team> findAllByOrderByIdAsc();

    @Query("""
            select distinct t
              from Team t
              join fetch t.members
             order by t.id
            """)
    List<Team> findAllWithMembers();

    @Query(
            value = """
                    select distinct t
                      from Team t
                      join fetch t.members
                     order by t.id
                    """,
            countQuery = "select count(t) from Team t"
    )
    Page<Team> findPageWithMembers(Pageable pageable);

    @Query(
            value = """
                    select t.id
                      from Team t
                     order by t.id
                    """,
            countQuery = "select count(t) from Team t"
    )
    Page<Long> findPageIds(Pageable pageable);

    @Query("""
            select distinct t
              from Team t
              join fetch t.members
             where t.id in :teamIds
             order by t.id
            """)
    List<Team> findAllWithMembersByIdIn(@Param("teamIds") List<Long> teamIds);
}
