package com.connectx.push.repository;

import com.connectx.push.entity.UserPushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserPushSubscriptionRepository extends JpaRepository<UserPushSubscription, Long> {
    List<UserPushSubscription> findByUserId(Long userId);
    Optional<UserPushSubscription> findByEndpoint(String endpoint);
    void deleteByEndpoint(String endpoint);
    void deleteByUserId(Long userId);
}
