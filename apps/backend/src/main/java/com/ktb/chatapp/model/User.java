package com.ktb.chatapp.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ktb.chatapp.util.EncryptionUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {

    @Id
    private String id;

    private String name;

    @Indexed(unique = true)
    private String email;
    
    private String encryptedEmail;

    @JsonIgnore // 실수로 비밀번호가 JSON 응답에 노출되는 것을 방지
    private String password;

    private String profileImage;

    @CreatedDate
    @Indexed(direction = IndexDirection.DESCENDING) // 최신 가입순 조회 성능 향상
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    private LocalDateTime lastActive;

    private LocalDateTime lastLogin;

    @Builder.Default
    private boolean isOnline = false;
    
    /**
     * Email lowercase conversion before save
     */
    @Component
    @Slf4j
    public static class UserEventListener extends AbstractMongoEventListener<User> {
        
        private final EncryptionUtil encryptionUtil;
        
        // ApplicationContext 직접 조회 대신 생성자 주입 사용 (@Lazy로 순환 참조 방지)
        public UserEventListener(@Lazy EncryptionUtil encryptionUtil) {
            this.encryptionUtil = encryptionUtil;
        }
        
        @Override
        public void onBeforeConvert(BeforeConvertEvent<User> event) {
            User user = event.getSource();
            if (user.getEmail() != null) {
                user.setEmail(user.getEmail().toLowerCase());
                
                // 이메일 암호화
                try {
                    String encrypted = encryptionUtil.encrypt(user.getEmail());
                    if (encrypted != null) {
                        user.setEncryptedEmail(encrypted);
                    }
                } catch (Exception e) {
                    // System.err 대신 Logger 사용
                    log.error("Email encryption failed for user: {}", user.getEmail(), e);
                }
            }
        }
    }
}
