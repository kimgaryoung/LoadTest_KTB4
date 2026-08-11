package com.ktb.chatapp.service.cache;

import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.service.FileUrl;

/**
 * 공개 응답에 필요한 필드만 담는 사용자 캐시 값이다.
 * 비밀번호와 인증 관련 필드는 Redis 캐시에 저장하지 않는다.
 */
public record CachedUserProfile(
        String id,
        String name,
        String email,
        String profileImage) {

    public static CachedUserProfile from(User user) {
        return new CachedUserProfile(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getProfileImage());
    }

    public UserResponse toResponse() {
        String profileImageUrl = FileUrl.of(profileImage);
        return UserResponse.builder()
                .id(id)
                .name(name)
                .email(email)
                .profileImage(profileImageUrl != null ? profileImageUrl : "")
                .build();
    }
}
