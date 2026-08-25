package com.example.boilerplate.features.user.entity;

import com.example.boilerplate.common.base.BaseEntity;
import com.example.boilerplate.common.constant.AccountType;
import com.example.boilerplate.common.constant.AuthProvider;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(columnNames = "username"),
        @UniqueConstraint(columnNames = "email")
})
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String email;

    @Column(nullable = true)
    private String password;

    @Column(name = "full_name", length = 100)
    private String fullName;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = false;

    // Intent đăng ký employer, chỉ có nghĩa khi user chưa active.
    // Được set lúc register, đọc & clear khi verify OTP thành công.
    // sau khi được verify thì sẽ được xóa
    @Enumerated(EnumType.STRING)
    @Column(name = "pending_account_type", length = 20)
    private AccountType pendingAccountType;

    @Column(name = "pending_company_name", length = 255)
    private String pendingCompanyName;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    // === OIDC Infor

    /**
     * Phương thức tạo tài khoản:
     * - LOCAL: Login bằng username/password truyền thống
     * - GOOGLE: Login bằng google thông qua OIDC
     * - GITHUB: Login bằng Github thông qua OIDC
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", length = 20, nullable = false)
    private AuthProvider authProvider = AuthProvider.LOCAL;

    /**
     * social_Id = sub trong token id là định danh duy nhất và ổn định của
     * người dùng đối với một provider.
     */
    @Column(name = "social_id", length = 200)
    private String socialId;

    /**
     * link url ảnh đại diện
     */
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;
}
