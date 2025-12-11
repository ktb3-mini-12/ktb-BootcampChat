package com.ktb.chatapp.dto;

import com.ktb.chatapp.validation.ValidEmail;
import com.ktb.chatapp.validation.ValidName;
import com.ktb.chatapp.validation.ValidPassword;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원가입 요청")
public record RegisterRequest (

    @Schema(description = "사용자 이름", example = "홍길동", required = true)
    @ValidName
    String name,

    @Schema(description = "이메일 주소", example = "user@example.com", required = true)
    @ValidEmail
    String email,

    @Schema(description = "비밀번호 (최소 8자, 영문자, 숫자, 특수문자 포함)", example = "Password123!", required = true)
    @ValidPassword
    String password
){ }
