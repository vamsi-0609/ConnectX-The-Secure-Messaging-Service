package com.connectx.connection.repository;

import com.connectx.connection.entity.UserConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserConnectionRepository extends JpaRepository<UserConnection, Long> {

    boolean existsByUserLowIdAndUserHighId(Long userLowId, Long userHighId);

    @Query("SELECT c FROM UserConnection c JOIN FETCH c.userLow JOIN FETCH c.userHigh " +
           "WHERE c.userLow.id = :userId OR c.userHigh.id = :userId ORDER BY c.createdAt DESC")
    List<UserConnection> findAllForUserWithUsers(@Param("userId") Long userId);
}
