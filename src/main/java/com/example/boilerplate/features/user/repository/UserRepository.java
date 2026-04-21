package com.example.boilerplate.features.user.repository;

import com.example.boilerplate.features.user.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User,Long> {
    Optional<User> findByEmail(String email);

    /**
     * Kiểm tra có active account nào đã dùng email này chưa
     */
    @Query("""
        SELECT 1
        FROM User u
        WHERE u.email = :email and u.isActive = true and u.deleted = false
    """)
    boolean isEmailAlreadyInUse(@Param("email") String email);

    /**
     * Kiểm tra có tài khoản nào dùng email này mà bị ban chưa
     * @param email email đăng kí tài khoản
     * @return true nếu có 1 tài khoản sử dụng email này rồi và đã bị ban (is_deleted = true)
     */
    @Query("""
        SELECT 1
        FROM User u
        WHERE u.email = :email and u.isActive = true and u.deleted = true
    """)
    boolean isEmailBanned(@Param("email") String email);

    @Query("""
        SELECT 1
        FROM User u
        WHERE u.email = :email and u.isActive = false and u.deleted = false
    """)
    boolean isInactiveAccount(@Param("email") String email);

    /**
     * Kiểm tra username đăng kí có bị trùng không
     * @param username username đăng kí tài khoản
     * @param email email đăng kí tài khoản
     * @return true nếu tồn tại 1 tài khoản sử dụng 1 email khác email đăng kí nhưng trùng username
     * với username đăng kí
     */
    @Query("""
        SELECT 1
        FROM User u
        WHERE u.email <> :email and u.username = :username
    """)
    boolean isUserNameAlreadyInUse(@Param("username") String username, @Param("email") String email);
}
