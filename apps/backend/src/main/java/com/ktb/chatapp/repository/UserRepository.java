package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmail(String email);

    // 전체 유저 조회 시 실수로 List<User> findAll()을 쓰지 않도록 Pageable 메소드 명시
    Page<User> findAll(Pageable pageable);
}
