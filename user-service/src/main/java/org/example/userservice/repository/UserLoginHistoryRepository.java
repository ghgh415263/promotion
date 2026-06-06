package org.example.userservice.repository;

import org.example.userservice.entity.User;
import org.example.userservice.entity.UserLoginHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserLoginHistoryRepository extends JpaRepository<UserLoginHistory, Integer> {
    List<UserLoginHistory> findByUserOrderByLoginTimeDesc(User user);
}
