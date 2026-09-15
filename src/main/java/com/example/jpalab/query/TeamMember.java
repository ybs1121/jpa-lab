package com.example.jpalab.query;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "lab4_team_members")
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "team_member_sequence_generator")
    @SequenceGenerator(
            name = "team_member_sequence_generator",
            sequenceName = "team_member_sequence",
            allocationSize = 1
    )
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    protected TeamMember() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
