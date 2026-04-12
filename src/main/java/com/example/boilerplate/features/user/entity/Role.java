package com.example.boilerplate.features.user.entity;

import com.example.boilerplate.common.constant.RoleEnum;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // DB sẽ lưu tên Enum, vd: enum USER thì sẽ lưu "USER", enum "ADMIN" thì sẽ lưu ADMIN
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 20)
    private RoleEnum name;
}
