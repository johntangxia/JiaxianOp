package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "channel")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Channel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String category;
}
