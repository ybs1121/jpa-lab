package com.example.jpalab.query;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "lab4_teams")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "team_sequence_generator")
    @SequenceGenerator(
            name = "team_sequence_generator",
            sequenceName = "team_sequence",
            allocationSize = 1
    )
    private Long id;

    private String name;

    @OneToMany(mappedBy = "team", fetch = FetchType.LAZY)
    private List<TeamMember> members = new ArrayList<>();

    protected Team() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<TeamMember> getMembers() {
        return members;
    }
}
